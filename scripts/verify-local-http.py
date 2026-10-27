"""Anonymous loopback HTTP security smoke; one exact /demo redirect, no login POST."""
import argparse
import http.cookiejar
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from pathlib import Path

PAGE_TYPE = "application/vnd.worklog.page+json"


class BootstrapParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.payloads = []
        self.parts = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "script" and attrs.get("id") == "worklog-page":
            assert attrs.get("type") == "application/json", "Bootstrap must be non-executable JSON"
            self.parts = []

    def handle_data(self, data):
        if self.parts is not None:
            self.parts.append(data)

    def handle_endtag(self, tag):
        if tag == "script" and self.parts is not None:
            self.payloads.append("".join(self.parts))
            self.parts = None


def json_object(body):
    try:
        value = json.loads(body)
    except (json.JSONDecodeError, TypeError) as error:
        raise AssertionError("Invalid page JSON") from error
    assert isinstance(value, dict), "Expected a page JSON object"
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None


def csrf(html):
    parser = BootstrapParser()
    parser.feed(html)
    parser.close()
    assert parser.parts is None, "Unclosed page bootstrap"
    if parser.payloads:
        assert len(parser.payloads) == 1, "Duplicate page bootstrap"
        contract = json_object(parser.payloads[0]).get("csrf")
        assert isinstance(contract, dict) and contract.get("parameterName") == "_csrf", "Invalid CSRF parameter name"
        token = contract.get("token")
        assert isinstance(token, str) and token.strip(), "Missing CSRF bootstrap token"
        return token
    found = re.search(r'<input[^>]+name="_csrf"[^>]+value="([^"]+)"', html)
    if not found:
        raise AssertionError("Missing CSRF input")
    return found.group(1)


def anonymous_react_page(request):
    status, headers, body = request("/me/today/status", headers={"Accept": PAGE_TYPE})
    assert status == 401, "Anonymous React navigation must return 401"
    assert "no-store" in headers.get("Cache-Control", ""), "React errors must not be cached"
    assert headers.get("Content-Type", "").split(";", 1)[0].strip().lower() == PAGE_TYPE, "Expected page JSON MIME"
    page = json_object(body)
    assert page.get("page") == "org/problem" and isinstance(page.get("data"), dict), "Expected safe problem page"
    assert page["data"].get("status") == 401, "Expected unauthorized problem status"


def login_page(request, base=None):
    status, headers, body = request("/login")
    if status == 302:
        location = headers.get("Location")
        assert location == "/demo" or (base is not None and location == base + "/demo"), "Unexpected login redirect"
        status, headers, body = request("/demo")
    assert status == 200, "Expected a login form or demo introduction without further redirects"
    csrf(body)
    return headers, body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    if args.self_test:
        assert csrf('<input type="hidden" name="_csrf" value="synthetic">') == "synthetic"
        assert csrf('<script id="worklog-page" type="application/json">'
                    '{"csrf":{"token":"synthetic","parameterName":"_csrf"}}</script>') == "synthetic"
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
    headers, body = login_page(request, base)
    assert "no-store" in headers.get("Cache-Control", "")
    assert headers.get("X-Content-Type-Options") == "nosniff"
    assert "frame-ancestors 'none'" in headers.get("Content-Security-Policy", "")
    passed("login or one-hop demo entry has CSRF, no-store, nosniff and restrictive CSP")
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
    anonymous_react_page(request)
    passed("anonymous React navigation returns safe 401 page JSON with no-store")
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
