"""Compare URL-encoded and multipart authentication on an owned synthetic local fixture."""
import argparse
from datetime import datetime, timezone
import http.cookiejar
import importlib.util
import json
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUTPUT_ROOT = ROOT / "target/runtime-revalidation-20261026/multipart-probe"
MAX_BODY = 1_048_576
helper_spec = importlib.util.spec_from_file_location("multipart_soak_helpers", Path(__file__).with_name("http-mixed-soak.py"))
helpers = importlib.util.module_from_spec(helper_spec)
helper_spec.loader.exec_module(helpers)
page_spec = importlib.util.spec_from_file_location("multipart_page_helpers", Path(__file__).with_name("verify-local-http.py"))
pages = importlib.util.module_from_spec(page_spec)
page_spec.loader.exec_module(pages)


def require(condition):
    if not condition:
        raise ValueError("Multipart probe contract was not satisfied")


def local_redirect(base, location):
    require(isinstance(location, str) and location and not any(ord(char) < 32 for char in location))
    try:
        return helpers.local_location(base, location)
    except (AssertionError, ValueError):
        raise ValueError("Non-local redirect refused") from None


def validate_metadata(data):
    require(isinstance(data, dict) and data.get("schemaVersion") == 1 and data.get("fixtureScope") == "owned-synthetic")
    base, users, password = data.get("baseUrl"), data.get("users"), data.get("password")
    require(isinstance(base, str) and not any(ord(char) < 32 for char in base))
    base = helpers.local_base(base)
    require(isinstance(password, str) and 12 <= len(password) <= 128 and not any(ord(char) < 32 for char in password))
    require(isinstance(users, list) and len(users) == 18)
    for user in users:
        require(isinstance(user, dict) and user.get("group") in (1, 2, 3))
        require(user.get("marker") == f"SOAK_T{user['group']}_")
        require(isinstance(user.get("companyCode"), str)
                and re.fullmatch(r"SOAK-[0-9A-F]{8}(?:-[0-9A-F]{4}){3}-[0-9A-F]{12}", user["companyCode"]))
        require(isinstance(user.get("email"), str) and re.fullmatch(r"http\d{4}@example\.test", user["email"]))
    selected = next((user for user in users if user.get("kind") == "employee"), None)
    require(selected is not None)
    return base, selected, password


def checked_paths(metadata, output):
    source, destination = Path(metadata).resolve(), Path(output).resolve()
    require(source.is_relative_to(ROOT / "target") and source.suffix == ".json")
    require(destination.is_relative_to(ROOT / "target") and destination.parent.name == "multipart-probe"
            and destination.suffix == ".json" and destination != source)
    return source, destination


def page_bootstrap(body, expected_page):
    try:
        parser = pages.BootstrapParser()
        parser.feed(body.decode("utf-8"))
        parser.close()
        require(parser.parts is None and len(parser.payloads) == 1)
        page = pages.json_object(parser.payloads[0])
        require(page.get("page") == expected_page and isinstance(page.get("data"), dict))
        csrf = page.get("csrf")
        require(isinstance(csrf, dict) and csrf.get("parameterName") == "_csrf"
                and csrf.get("headerName") == "X-CSRF-TOKEN"
                and isinstance(csrf.get("token"), str) and csrf["token"].strip())
        return page
    except (AssertionError, UnicodeError):
        raise ValueError("Multipart probe contract was not satisfied") from None


def multipart(fields, with_file=False):
    boundary = "synthetic-" + uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        require(name in ("companyCode", "email", "password") and isinstance(value, str)
                and not any(ord(char) < 32 for char in value))
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n')
    if with_file:
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="sentinel"; filename="sentinel.txt"\r\n'
                     'Content-Type: text/plain\r\n\r\nsynthetic multipart sentinel\r\n')
    parts.append(f"--{boundary}--\r\n")
    body = "".join(parts).encode("utf-8")
    require(len(body) < 10_000)
    return "multipart/form-data; boundary=" + boundary, body


class Session:
    def __init__(self, base):
        self.base = helpers.local_base(base)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), helpers.NoRedirect())

    def request(self, path, data=None, headers=None):
        require(path in ("/login", "/me/profile"))
        try:
            reply = self.opener.open(urllib.request.Request(self.base + path, data=data, headers=headers or {}), timeout=15)
        except urllib.error.HTTPError as failure:
            reply = failure
        with reply:
            status, response_headers, body = reply.code, reply.headers, reply.read(MAX_BODY + 1)
        require(len(body) <= MAX_BODY)
        if 300 <= status < 400:
            local_redirect(self.base, response_headers.get("Location"))
        return status, response_headers, body


def run_probe(data, with_file=False, session_factory=Session):
    base, user, password = validate_metadata(data)
    credentials = {"companyCode": user["companyCode"], "email": user["email"], "password": password}
    checks = []

    def record(name, response, expected_status, location=None, marker=None):
        status, headers, body = response
        passed = status == expected_status and "no-store" in headers.get("Cache-Control", "")
        if 300 <= status < 400:
            redirect = local_redirect(base, headers.get("Location"))
            passed = passed and redirect == location
        elif location is not None:
            passed = False
        if marker is not None:
            try:
                require("text/html" in headers.get("Content-Type", ""))
                identity = page_bootstrap(body, "workspace/profile")["data"].get("workspaceEmployee")
                require(isinstance(identity, dict) and all(isinstance(identity.get(key), str)
                        and identity[key].startswith(marker) for key in ("employeeName", "companyName")))
            except ValueError:
                passed = False
        checks.append({"name": name, "status": status, "passed": passed})

    def session_with_token():
        client = session_factory(base)
        status, headers, page = client.request("/login")
        require(status == 200 and "text/html" in headers.get("Content-Type", "")
                and "no-store" in headers.get("Cache-Control", ""))
        return client, page_bootstrap(page, "login")["csrf"]["token"]

    positive, token = session_with_token()
    encoded = urllib.parse.urlencode({**credentials, "_csrf": token}).encode("utf-8")
    record("urlencoded_login", positive.request("/login", encoded,
            {"Content-Type": "application/x-www-form-urlencoded"}), 302, location="/me/home")
    record("urlencoded_authenticated_page", positive.request("/me/profile"), 200, marker=user["marker"])

    missing, _ = session_with_token()
    content_type, body = multipart(credentials, with_file)
    record("multipart_missing_csrf", missing.request("/login", body, {"Content-Type": content_type}), 403)
    record("multipart_missing_csrf_unauthenticated", missing.request("/me/profile"), 302, location="/login")

    header, token = session_with_token()
    content_type, body = multipart(credentials, with_file)
    record("multipart_valid_header_rejected", header.request("/login", body,
            {"Content-Type": content_type, "X-CSRF-TOKEN": token}), 302, location="/login?error")
    record("multipart_valid_header_unauthenticated", header.request("/me/profile"), 302, location="/login")
    return {"schemaVersion": 1, "atUtc": datetime.now(timezone.utc).isoformat(),
            "scope": "owned-synthetic-loopback-http", "passed": all(check["passed"] for check in checks),
            "withFilePart": with_file, "tempfileInspectionPerformed": False, "checks": checks}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=OUTPUT_ROOT / "result.json")
    parser.add_argument("--with-file", action="store_true")
    args = parser.parse_args(argv)
    try:
        source, destination = checked_paths(args.metadata, args.output)
    except (ValueError, OSError):
        print("LOCAL_MULTIPART_FAIL reason=unsafe_paths")
        return 1
    try:
        result = run_probe(json.loads(source.read_text(encoding="utf-8-sig")), args.with_file)
    except Exception:
        # Do not serialize exception messages, credential-bearing metadata, CSRF, or response bodies.
        result = {"schemaVersion": 1, "atUtc": datetime.now(timezone.utc).isoformat(), "passed": False,
                  "reason": "validation_or_transport_failure", "checks": [], "tempfileInspectionPerformed": False}
    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except OSError:
        print("LOCAL_MULTIPART_FAIL reason=result_write_failure")
        return 1
    print(f"LOCAL_MULTIPART_{'PASS' if result['passed'] else 'FAIL'} checks={len(result['checks'])}")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
