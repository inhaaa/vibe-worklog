"""Multipart diagnostic contracts; no application, DB, Docker, or network is used."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.parse


spec = importlib.util.spec_from_file_location("multipart_http", Path(__file__).with_name("verify-multipart-http.py"))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def metadata():
    return {"schemaVersion": 1, "fixtureScope": "owned-synthetic", "baseUrl": "http://127.0.0.1:18082",
            "password": "Synthetic password 43!", "users": [
                {"group": group, "kind": "employee", "marker": f"SOAK_T{group}_",
                 "companyCode": "SOAK-AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "email": f"http{index:04}@example.test"}
                for group in range(1, 4) for index in range(1, 7)]}


CSRF = {"token": "synthetic-csrf-secret", "parameterName": "_csrf", "headerName": "X-CSRF-TOKEN"}


def bootstrap(page="login", data=None, csrf=None):
    payload = {"page": page, "data": data if data is not None else {},
               "csrf": CSRF if csrf is None else csrf}
    return ('<script id="worklog-page" type="application/json">' + json.dumps(payload)
            + '</script>').encode()


FORM = bootstrap()
PROFILE = bootstrap("workspace/profile", {"workspaceEmployee": {
    "employeeName": "SOAK_T1_0001", "companyName": "SOAK_T1_COMPANY"}})


def response(status, location=None, body=b"", content_type="text/html;charset=UTF-8"):
    headers = {"Content-Type": content_type, "Cache-Control": "no-store"}
    if location is not None:
        headers["Location"] = location
    return status, headers, body


class FakeSession:
    def __init__(self, responses):
        self.responses, self.requests = list(responses), []

    def request(self, path, data=None, headers=None):
        self.requests.append((path, data, headers or {}))
        return self.responses.pop(0)


def sessions(multipart_enabled=False, header_status=None, positive_type="text/html;charset=UTF-8",
             login_body=FORM, positive_body=PROFILE):
    positive = FakeSession([response(200, body=login_body), response(302, "/me/home"),
                            response(200, body=positive_body, content_type=positive_type)])
    missing = FakeSession([response(200, body=login_body), response(403), response(302, "/login")])
    enabled = multipart_enabled
    valid = FakeSession([response(200, body=login_body),
                         response(header_status or 302, "/me/home" if enabled else "/login?error"),
                         response(200, body=PROFILE) if enabled else response(302, "/login")])
    pending = [positive, missing, valid]
    return pending, lambda base: pending.pop(0)


class MultipartHttpTest(unittest.TestCase):
    def test_metadata_requires_explicit_owned_synthetic_fixture_and_eighteen_users(self):
        good = metadata()
        self.assertEqual((good["baseUrl"], good["users"][0], good["password"]), probe.validate_metadata(good))
        for invalid in [{**good, "fixtureScope": "production"}, {**good, "schemaVersion": 2},
                        {**good, "users": good["users"][:1]}, {**good, "password": "secret\r\ninjection"}]:
            with self.subTest(kind=list(invalid)), self.assertRaises(ValueError):
                probe.validate_metadata(invalid)
        for key, value in [("email", "real@example.com"), ("companyCode", "REAL-COMPANY"), ("marker", "real")]:
            invalid = metadata()
            invalid["users"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.validate_metadata(invalid)

    def test_origins_reject_remote_aliases_credentials_bad_ports_and_control_characters(self):
        for origin in ["https://127.0.0.1:18082", "http://localhost:18082", "http://example.test:18082",
                       "http://user:secret@127.0.0.1:18082", "http://127.0.0.1:0", "http://127.0.0.1:65536",
                       "http://127.0.0.1:18082/path", "http://127.0.0.1:\n18082", "http://127.0.0.1:18082#x"]:
            invalid = metadata()
            invalid["baseUrl"] = origin
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                probe.validate_metadata(invalid)

    def test_input_and_distinct_json_output_must_stay_in_designated_target_paths(self):
        root = Path.cwd().resolve()
        output = root / "target/runtime-revalidation-20261026/multipart-probe/result.json"
        fixture = root / "target/synthetic-fixture.json"
        self.assertEqual((fixture, output), probe.checked_paths(fixture, output))
        fresh = root / "target/react-runtime-20261027/multipart-probe/result.json"
        self.assertEqual((fixture, fresh), probe.checked_paths(fixture, fresh))
        for source, destination in [(root / "README.md", output), (fixture, root / "result.json"),
                                    (fixture, fixture), (fixture, output.with_suffix(".txt")),
                                    (fixture, root / "target/other/result.json")]:
            with self.subTest(destination=destination), self.assertRaises(ValueError):
                probe.checked_paths(source, destination)

    def test_multipart_has_credentials_and_optional_small_harmless_file(self):
        fields = {"companyCode": "SOAK-company", "email": "http@example.test", "password": "Synthetic pass!"}
        media_type, body = probe.multipart(fields, with_file=True)
        self.assertTrue(media_type.startswith("multipart/form-data; boundary="))
        self.assertLess(len(body), 10_000)
        self.assertIn(b'name="companyCode"', body)
        self.assertIn(b'filename="sentinel.txt"', body)
        self.assertIn(b"synthetic multipart sentinel", body)
        self.assertNotIn(b'name="_csrf"', body)
        self.assertNotIn(b'filename=', probe.multipart(fields)[1])

    def test_urlencoded_positive_and_multipart_negatives_use_separate_sessions(self):
        pending, factory = sessions()
        clients = list(pending)
        result = probe.run_probe(metadata(), with_file=True, session_factory=factory)
        self.assertTrue(result["passed"])
        self.assertEqual(6, len(result["checks"]))
        self.assertFalse(result["tempfileInspectionPerformed"])
        self.assertEqual([3, 3, 3], [len(client.requests) for client in clients])
        positive = urllib.parse.parse_qs(clients[0].requests[1][1].decode())
        self.assertEqual([metadata()["password"]], positive["password"])
        self.assertEqual(["synthetic-csrf-secret"], positive["_csrf"])
        self.assertNotIn("X-CSRF-TOKEN", clients[1].requests[1][2])
        self.assertEqual("synthetic-csrf-secret", clients[2].requests[1][2]["X-CSRF-TOKEN"])
        serialized = json.dumps(result)
        for secret in [metadata()["password"], "synthetic-csrf-secret", "http0001@example.test", "SOAK_T1_"]:
            self.assertNotIn(secret, serialized)

    def test_enabled_multipart_authentication_is_reported_as_failure(self):
        pending, factory = sessions(multipart_enabled=True)
        result = probe.run_probe(metadata(), session_factory=factory)
        self.assertEqual(6, len(result["checks"]))
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"][-2]["passed"])
        self.assertFalse(result["checks"][-1]["passed"])

    def test_login_requires_one_valid_react_bootstrap_and_its_csrf_contract(self):
        legacy = b'<form action="/login"><input name="_csrf" value="legacy-token"></form>'
        malformed = [legacy, FORM + FORM, FORM.replace(b'</script>', b''),
                     FORM.replace(b'application/json', b'text/javascript'),
                     b'<script id="worklog-page" type="application/json">broken</script>',
                     bootstrap("today"), bootstrap(csrf={}),
                     bootstrap(csrf={**CSRF, "token": ""}),
                     bootstrap(csrf={**CSRF, "parameterName": "other"}),
                     bootstrap(csrf={**CSRF, "headerName": "Other-CSRF"})]
        for body in malformed:
            _, factory = sessions(login_body=body)
            with self.subTest(body=body), self.assertRaises(ValueError):
                probe.run_probe(metadata(), session_factory=factory)

    def test_positive_login_requires_today_bootstrap_with_matching_tenant_identity(self):
        invalid = [b'<p>SOAK_T1_</p>', bootstrap("login"), bootstrap("today"),
                   bootstrap("today", {"workspaceEmployee": {
                       "employeeName": "SOAK_T2_0001", "companyName": "SOAK_T2_COMPANY"}})]
        for body in invalid:
            _, factory = sessions(positive_body=body + b'<p>SOAK_T1_</p>')
            with self.subTest(body=body):
                result = probe.run_probe(metadata(), session_factory=factory)
                self.assertFalse(result["passed"])
                self.assertFalse(result["checks"][1]["passed"])

    def test_valid_header_csrf_must_reach_authentication_rejection_not_403(self):
        pending, factory = sessions(header_status=403)
        result = probe.run_probe(metadata(), session_factory=factory)
        self.assertEqual(6, len(result["checks"]))
        self.assertFalse(result["passed"])

    def test_successful_page_status_with_wrong_media_type_does_not_prove_login(self):
        pending, factory = sessions(positive_type="application/json")
        result = probe.run_probe(metadata(), session_factory=factory)
        self.assertEqual(6, len(result["checks"]))
        self.assertFalse(result["passed"])

    def test_session_bypasses_proxy_and_never_follows_external_redirect(self):
        class Reply:
            code, headers = 302, {"Location": "http://example.test/secret"}
            def __enter__(self): return self
            def __exit__(self, *args): return None
            def read(self, size): return b""
        opener = SimpleNamespace(open=lambda *args, **kwargs: Reply())
        with patch.object(probe.urllib.request, "build_opener", return_value=opener) as build:
            client = probe.Session("http://127.0.0.1:18082")
            self.assertEqual(1, build.call_count)
            proxy = next(handler for handler in build.call_args.args if isinstance(handler, probe.urllib.request.ProxyHandler))
            self.assertEqual({}, proxy.proxies)
            with self.assertRaises(ValueError):
                client.request("/login")

    def test_cli_failure_output_never_contains_exception_or_credentials(self):
        capture = io.StringIO()
        with patch.object(probe, "checked_paths", return_value=(Path("fixture.json"), Path("result.json"))), \
             patch.object(Path, "read_text", return_value=json.dumps(metadata())), \
             patch.object(Path, "mkdir"), patch.object(Path, "write_text") as write, \
             patch.object(probe, "run_probe", side_effect=ValueError("secret PASSWORD synthetic-csrf-secret")), \
             contextlib.redirect_stdout(capture), contextlib.redirect_stderr(capture):
            self.assertEqual(1, probe.main(["--metadata", "fixture.json", "--output", "result.json"]))
            self.assertTrue(write.called)
            rendered = capture.getvalue() + write.call_args.args[0]
            self.assertNotIn("secret", rendered)
            self.assertNotIn(metadata()["password"], rendered)


if __name__ == "__main__":
    unittest.main()
