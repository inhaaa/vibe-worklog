"""Anonymous local HTTP security smoke. No credentials, external targets or redirects."""
import argparse
import http.cookiejar
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None


def csrf(html):
    found = re.search(r'<input[^>]+name="_csrf"[^>]+value="([^"]+)"', html)
    if not found:
        raise AssertionError("Missing CSRF input")
    return found.group(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    if args.self_test:
        assert csrf('<input type="hidden" name="_csrf" value="synthetic">') == "synthetic"
        try:
            csrf("<p>no token</p>")
        except AssertionError:
            print("HTTP probe parser self-check PASS; no connection opened")
            return
        raise AssertionError("Parser accepted a missing token")

    base = f"http://127.0.0.1:{args.port}"
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect())
    checks = []

    def request(path, form=None, headers=None):
        assert path.startswith("/") and not path.startswith("//")
        data = None if form is None else urllib.parse.urlencode(form).encode()
        try:
            response = opener.open(urllib.request.Request(base + path, data=data, headers=headers or {}), timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.code, response.headers, response.read().decode("utf-8")

    def passed(name):
        checks.append(name)

    status, headers, body = request("/actuator/health")
    health = json.loads(body)
    assert status == 200 and health.get("status") == "UP" and set(health) <= {"status", "groups"}
    passed("health exposes status and probe group names only")
    status, headers, body = request("/login")
    assert status == 200 and csrf(body)
    assert "no-store" in headers.get("Cache-Control", "")
    assert headers.get("X-Content-Type-Options") == "nosniff"
    assert "frame-ancestors 'none'" in headers.get("Content-Security-Policy", "")
    passed("login has CSRF, no-store, nosniff and restrictive CSP")
    for path in ["/login", "/logout", "/password-reset/request", "/password-reset/complete"]:
        assert request(path, {})[0] == 403, path
        passed(f"CSRF missing denied: {path}")
    for path in ["/me/today", "/me/attendance", "/me/worktime", "/me/requests", "/approvals",
                 "/admin/employees", "/admin/schedules", "/admin/leave-balances", "/admin/audit",
                 "/worktime/results/00000000-0000-0000-0000-000000000000",
                 "/admin/reports/exports/00000000-0000-0000-0000-000000000000/download"]:
        status, headers, body = request(path)
        assert status in (401, 403) or (status == 302 and headers.get("Location", "").endswith("/login")), path
        assert "no-store" in headers.get("Cache-Control", ""), path
        passed(f"anonymous business path denied: {path}")
    status, headers, body = request("/me/today/status", headers={"HX-Request": "true"})
    assert status == 401 and headers.get("HX-Redirect") == "/login"
    passed("expired htmx redirects to login without private fragments")
    for path in ["/actuator/env", "/actuator/configprops", "/actuator/heapdump", "/operator/login", "/operator/tenants"]:
        status, headers, body = request(path)
        assert status in (401, 403) or (status == 302 and headers.get("Location", "").endswith("/login")), path
        passed(f"default sensitive or operator path denied: {path}")
    status, headers, body = request("/password-reset")
    assert status == 200 and csrf(body) and "no-referrer" in body and "no-store" in headers.get("Cache-Control", "")
    passed("reset form protects token referrer and cache")
    output = Path("target/phase6-http-result.json")
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps({"base": base, "passed": len(checks), "checks": checks}, indent=2) + "\n", encoding="utf-8")
    print(f"Local HTTP security PASS: {len(checks)} checks; {output}")


if __name__ == "__main__":
    main()
