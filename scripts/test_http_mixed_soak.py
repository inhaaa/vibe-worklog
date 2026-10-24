"""Local runner invariants; no application or network is needed."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import time
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("http_mixed_soak", Path(__file__).with_name("http-mixed-soak.py"))
soak = importlib.util.module_from_spec(spec)
spec.loader.exec_module(soak)


class ProbeTest(unittest.TestCase):
    def test_only_direct_loopback_http_targets_are_accepted(self):
        self.assertEqual("http://127.0.0.1:18082", soak.local_base("http://127.0.0.1:18082"))
        for value in ["https://example.test", "http://localhost:18082", "http://127.0.0.1:18082/a", "http://user@127.0.0.1:18082", "http://127.0.0.1:18082?x=1"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                soak.local_base(value)

    def test_form_parser_preserves_literal_values_and_selects_exact_action(self):
        html = '<form action="/wrong"><input name="_csrf" value="wrong"></form><form action="/right" method="post"><input type="hidden" name="_csrf" value="x&amp;y"><input name="expectedVersion" value="2"><textarea name="reason">old</textarea></form>'
        self.assertEqual({"_csrf": "x&y", "expectedVersion": "2"}, soak.form_fields(html, "/right"))
        with self.assertRaises(AssertionError):
            soak.form_fields(html, "/absent")

    def test_latency_summary_counts_expected_rejections_separately(self):
        rows = [{"operation": "read", "status": 200, "latencyMillis": n, "ok": True, "expectedRejection": False} for n in [1, 2, 3, 4]]
        rows.append({"operation": "denial", "status": 404, "latencyMillis": 9, "ok": True, "expectedRejection": True})
        rows.append({"operation": "read", "status": 500, "latencyMillis": 10, "ok": False, "expectedRejection": False})
        result = soak.summarize(rows)
        self.assertEqual(6, result["requests"])
        self.assertEqual(1, result["failures"])
        self.assertEqual(1, result["expectedRejections"])
        self.assertEqual(10, result["p95Millis"])
        self.assertEqual(3, result["p50Millis"])
        self.assertEqual(5, result["operations"]["read"]["requests"])

    def test_redirect_must_stay_on_the_observed_local_origin(self):
        base = "http://127.0.0.1:18082"
        self.assertEqual("/requests/123", soak.local_location(base, "/requests/123"))
        self.assertEqual("/me/today", soak.local_location(base, base + "/me/today"))
        for value in ["https://example.test/login", "//example.test/login", "http://127.0.0.1:1/login"]:
            with self.assertRaises(AssertionError):
                soak.local_location(base, value)

    def test_success_status_without_the_required_page_type_is_a_failure(self):
        rows = []
        run = SimpleNamespace(base="http://127.0.0.1:1", pacer=SimpleNamespace(wait=lambda: None, start=time.monotonic()), record=rows.append)
        client = soak.Client(run, {"employeeId": "synthetic", "group": 1, "kind": "employee"})

        class Response:
            code = 200
            headers = {"Cache-Control": "no-store", "Content-Type": "application/json"}
            def __enter__(self): return self
            def __exit__(self, *args): return None
            def read(self): return b'{}'

        client.opener = SimpleNamespace(open=lambda *args, **kwargs: Response())
        with self.assertRaises(AssertionError):
            client.http("today", "/me/today", contains="SOAK_T1_")
        self.assertEqual(1, len(rows))
        self.assertFalse(rows[0]["ok"])

    def test_runtime_window_excludes_fixture_setup_and_incomplete_trailing_write(self):
        before = {"at": "2026-10-25T01:00:00Z", "heapUsedBytes": 10}
        during = {"at": "2026-10-25T01:02:00.123Z", "heapUsedBytes": 20}
        content = json.dumps(before) + "\n" + json.dumps(during) + "\n{"
        self.assertEqual([during], soak.runtime_rows(content, "2026-10-25T01:01:00+00:00"))

    def test_pacing_occurs_inside_the_client_lock_before_the_request_timer(self):
        events, rows = [], []
        run = SimpleNamespace(base="http://127.0.0.1:1", pacer=SimpleNamespace(start=0), record=rows.append)
        client = soak.Client(run, {"employeeId": "synthetic", "group": 1, "kind": "employee"})

        def pace():
            self.assertTrue(client.lock.locked(), "Shared client must own its cookie lock before reserving a send slot")
            events.append("pacer")

        class Response:
            code = 200
            headers = {"Cache-Control": "no-store", "Content-Type": "text/html;charset=UTF-8"}
            def __enter__(self): return self
            def __exit__(self, *args): return None
            def read(self): return b'<p>SOAK_T1_</p>'

        def send(*args, **kwargs):
            self.assertTrue(client.lock.locked())
            events.append("send")
            return Response()

        def timer():
            events.append("timer")
            return 100.0

        run.pacer.wait = pace
        client.opener = SimpleNamespace(open=send)
        with patch.object(soak.time, "monotonic", side_effect=timer):
            client.http("today", "/me/today", contains="SOAK_T1_")
        self.assertEqual(["pacer", "timer", "send"], events[:3])
        self.assertTrue(rows[0]["ok"])


if __name__ == "__main__":
    unittest.main()
