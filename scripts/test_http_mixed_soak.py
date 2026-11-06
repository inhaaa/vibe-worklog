"""Local runner invariants; no application or network is needed."""
import importlib.util
import io
import json
from pathlib import Path
from types import SimpleNamespace
import time
import unittest
from unittest.mock import patch
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile


spec = importlib.util.spec_from_file_location("http_mixed_soak", Path(__file__).with_name("http-mixed-soak.py"))
soak = importlib.util.module_from_spec(spec)
spec.loader.exec_module(soak)

PAGE_TYPE = "application/vnd.worklog.page+json"
EMPLOYEE = "11111111-1111-1111-1111-111111111111"
APPROVER = "22222222-2222-2222-2222-222222222222"
COMMAND = "33333333-3333-3333-3333-333333333333"
REQUEST = "44444444-4444-4444-4444-444444444444"
TENANT = "55555555-5555-5555-5555-555555555555"
BUCKET = "66666666-6666-6666-6666-666666666666"
CSRF = {"token": "synthetic+csrf/=", "parameterName": "_csrf", "headerName": "X-CSRF-TOKEN"}


def page(name, data=None):
    return {"page": name, "data": {
        "workspaceEmployee": {"employeeName": "SOAK_T1_0003", "companyName": "SOAK_T1_COMPANY"},
        "workspacePrincipal": {"roles": ["EMPLOYEE", "APPROVER", "ATTENDANCE_ADMIN"]},
        **(data or {})}, "csrf": dict(CSRF)}


def today(employee=EMPLOYEE):
    return page("clock/history", {"day": {"employeeId": employee, "workDate": "2026-10-27"},
                                  "today": "2026-10-27", "isToday": True, "inCommand": COMMAND, "outCommand": REQUEST})


def mine(requests=None):
    return page("request/mine", {"requests": requests or [], "buckets": [
        {"id": BUCKET, "employeeId": EMPLOYEE}], "form": {
        "bucketId": None, "from": "2026-10-28", "to": "2026-10-28",
        "predecessorRequestId": None, "reason": "", "commandId": COMMAND}})


def month():
    days = [{"id": REQUEST, "employeeId": EMPLOYEE, "workDate": f"2026-08-{day:02}",
        "result": {"workDate": f"2026-08-{day:02}", "status": "CALCULATED", "actualWorkMillis": 28800000}}
        for day in range(1, 32)]
    return page("worktime/month", {"employeeId": EMPLOYEE, "from": "2026-08-01", "to": "2026-08-31",
        "calculateCommand": COMMAND, "exportCommand": REQUEST,
        "overview": {"month": "2026-08", "days": days,
            "summary": {"result": {"confirmedWorkMillis": 31 * 28800000}}}})


def workbook(employee=EMPLOYEE, start="2026-08-01", end="2026-08-31", suffix=""):
    namespace = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
    root = ET.Element(namespace + "worksheet")
    data = ET.SubElement(root, namespace + "sheetData")
    for values in [["근태 검토용 REVIEW"], ["고객사", "SOAK_T1_COMPANY", "고객사 ID", TENANT],
                   ["조회 시작일", start, "조회 종료일", end, "사원 ID", employee], [suffix]]:
        row = ET.SubElement(data, namespace + "row")
        for value in values:
            cell = ET.SubElement(row, namespace + "c", {"t": "inlineStr"})
            ET.SubElement(ET.SubElement(cell, namespace + "is"), namespace + "t").text = value
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("xl/worksheets/sheet1.xml", ET.tostring(root, encoding="utf-8"))
    return stream.getvalue()


def detail(status="PENDING"):
    request = {"id": REQUEST, "targetEmployeeId": EMPLOYEE, "currentApproverId": APPROVER,
        "kind": "LEAVE_DAY", "status": status, "version": 7, "effectStatus": "EFFECTIVE" if status == "APPROVED" else "NONE",
        "fromDate": "2026-10-31", "toDate": "2026-10-31", "quantityDays": 1}
    return page("request/detail", {"request": request, "detail": {"request": dict(request)},
        "pageCommand": COMMAND, "canDecide": status == "PENDING"})


class Response:
    def __init__(self, payload, status=200, content_type=PAGE_TYPE, headers=None):
        self.code = status
        self.headers = {"Cache-Control": "no-store", "Content-Type": content_type, **(headers or {})}
        self.body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
    def __enter__(self): return self
    def __exit__(self, *args): return None
    def read(self): return self.body


def client_with(*responses, kind="employee", employee=EMPLOYEE):
    rows, requests = [], []
    run = SimpleNamespace(base="http://127.0.0.1:1", pacer=SimpleNamespace(wait=lambda: None, start=time.monotonic()),
        data={"today": "2026-10-27", "historyDate": "2026-08-01", "historyMonth": "2026-08", "password": "synthetic password"},
        record=rows.append, groups={1: {"employee": SimpleNamespace(data={"employeeId": EMPLOYEE})}})
    client = soak.Client(run, {"employeeId": employee, "group": 1, "kind": kind, "tenantId": TENANT,
        "marker": "SOAK_T1_", "resultId": REQUEST, "bucketId": BUCKET,
        "companyCode": "synthetic-company", "email": "synthetic@example.test"})
    queue = iter(responses)
    def send(request, **kwargs):
        requests.append(request)
        return next(queue)
    client.opener = SimpleNamespace(open=send)
    return client, rows, requests


class ProbeTest(unittest.TestCase):
    def test_only_direct_loopback_http_targets_are_accepted(self):
        self.assertEqual("http://127.0.0.1:18082", soak.local_base("http://127.0.0.1:18082"))
        for value in ["https://example.test", "http://localhost:18082", "http://127.0.0.1:18082/a", "http://user@127.0.0.1:18082", "http://127.0.0.1:18082?x=1"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                soak.local_base(value)

    def test_json_forms_preserve_csrf_command_and_optimistic_version(self):
        self.assertEqual({"_csrf": CSRF["token"]}, soak.form_fields(page("login"), "/login"))
        self.assertEqual({"_csrf": CSRF["token"], "expectedVersion": 7, "commandId": COMMAND},
            soak.form_fields(detail(), "/requests/" + REQUEST + "/approve"))
        form = soak.form_fields(mine(), "/me/requests/leave")
        self.assertEqual(COMMAND, form["commandId"])
        self.assertEqual("", form["predecessorRequestId"])
        self.assertEqual({"_csrf": CSRF["token"], "employeeId": EMPLOYEE, "from": "2026-08-01",
            "to": "2026-08-31", "commandId": REQUEST}, soak.form_fields(month(), "/admin/reports/exports"))
        with self.assertRaises(AssertionError):
            soak.form_fields(mine(), "/not-a-command")

    def test_json_forms_reject_missing_csrf_commands_and_versions(self):
        for field, value in [("token", ""), ("parameterName", "other"), ("headerName", "other")]:
            payload = page("login")
            payload["csrf"][field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                soak.form_fields(payload, "/login")
        for field, value in [("pageCommand", None), ("request", {"id": REQUEST, "version": None})]:
            payload = detail()
            payload["data"][field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                soak.form_fields(payload, "/requests/" + REQUEST + "/approve")

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
        self.assertEqual("/me/attendance", soak.local_location(base, base + "/me/attendance"))
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
            client.http("today", "/me/attendance", contains="SOAK_T1_")
        self.assertEqual(1, len(rows))
        self.assertFalse(rows[0]["ok"])

    def test_real_react_json_is_requested_and_validated_as_the_expected_page(self):
        client, rows, requests = client_with(Response(today()))
        _, payload = client.http("today", "/me/attendance", contains="SOAK_T1_")
        self.assertEqual(today(), payload)
        self.assertEqual(PAGE_TYPE, requests[0].get_header("Accept"))
        self.assertTrue(rows[0]["ok"])

    def test_200_unknown_or_malformed_envelopes_and_html_shells_are_not_success(self):
        for payload in [{}, [], {"page": "unknown", "data": {}, "csrf": {}},
                        page("login"), {"page": "today", "data": [], "csrf": CSRF},
                        {"page": "today", "data": {}, "csrf": None}, b"{broken"]:
            client, rows, _ = client_with(Response(payload))
            with self.subTest(payload=payload), self.assertRaises(AssertionError):
                client.http("today", "/me/attendance")
            self.assertFalse(rows[0]["ok"])
        client, rows, _ = client_with(Response(b'<div id="root"></div>', content_type="text/html"))
        with self.assertRaises(AssertionError):
            client.http("today", "/me/attendance")

    def test_foreign_tenant_marker_or_same_tenant_wrong_employee_is_rejected(self):
        foreign = today()
        foreign["data"]["nested"] = {"name": "SOAK_T2_private"}
        for payload in [foreign, today(APPROVER)]:
            client, rows, _ = client_with(Response(payload))
            with self.subTest(payload=payload), self.assertRaises(AssertionError):
                client.http("today", "/me/attendance")
            self.assertFalse(rows[0]["ok"])

    def test_expected_denials_require_exact_safe_problem_json_and_http_status(self):
        for status, operation in [(403, "employeeAdminDenied"), (404, "foreignResultDenied")]:
            safe = {"page": "org/problem", "data": {"status": status, "message": "denied"}, "csrf": {}}
            client, rows, _ = client_with(Response(safe, status))
            client.http(operation, "/admin/audit", expected=status)
            self.assertTrue(rows[0]["expectedRejection"])
            for payload, actual in [(safe, 200), (page("today"), status),
                                    ({**safe, "data": {"status": 200}}, status)]:
                client, rows, _ = client_with(Response(payload, actual))
                with self.subTest(status=status, actual=actual), self.assertRaises(AssertionError):
                    client.http(operation, "/admin/audit", expected=status)

    def test_login_uses_json_csrf_and_the_real_urlencoded_session_flow(self):
        client, _, requests = client_with(Response(page("login")),
            Response(b"", 302, headers={"Location": "/me/home"}), Response(page("workspace/profile", {
                "profile": {"employeeName": "SOAK_T1_0003", "companyName": "SOAK_T1_COMPANY"}})))
        client.login()
        self.assertEqual(["/login", "/login", "/me/profile"], [urllib.parse.urlsplit(r.full_url).path for r in requests])
        body = urllib.parse.parse_qs(requests[1].data.decode())
        self.assertEqual([CSRF["token"]], body["_csrf"])
        self.assertEqual(["synthetic-company"], body["companyCode"])
        self.assertEqual(["synthetic@example.test"], body["email"])
        self.assertEqual(["synthetic password"], body["password"])

    def test_month_result_and_approval_semantics_are_not_only_200_checks(self):
        client, rows, _ = client_with(Response(month()))
        client.http("month", "/me/worktime?month=2026-08")
        self.assertTrue(rows[0]["ok"])
        for change in [lambda p: p["data"]["overview"].update(month="2026-07"),
                       lambda p: p["data"]["overview"]["days"][0].update(employeeId=APPROVER),
                       lambda p: p["data"]["overview"]["days"][0]["result"].update(actualWorkMillis=None)]:
            payload = month()
            change(payload)
            client, _, _ = client_with(Response(payload))
            with self.assertRaises(AssertionError):
                client.http("month", "/me/worktime?month=2026-08")
        client, _, _ = client_with(Response(detail()), employee=APPROVER, kind="approver")
        client.http("approve.form", "/requests/" + REQUEST, leave_scope=(EMPLOYEE, "2026-10-31"))
        client, _, _ = client_with(Response(detail()), employee=EMPLOYEE)
        with self.assertRaises(AssertionError):
            client.http("approved.detail", "/requests/" + REQUEST, leave_scope=(EMPLOYEE, "2026-10-31"))

    def test_month_requires_every_date_and_exact_command_period(self):
        for mutation in ["partial", "duplicate", "from", "to"]:
            payload = month()
            if mutation == "partial":
                payload["data"]["overview"]["days"] = payload["data"]["overview"]["days"][:1]
                payload["data"]["overview"]["summary"]["result"]["confirmedWorkMillis"] = 28800000
            elif mutation == "duplicate":
                payload["data"]["overview"]["days"][-1] = payload["data"]["overview"]["days"][0]
            else:
                payload["data"][mutation] = "2026-07-01"
            client, _, _ = client_with(Response(payload), kind="admin")
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                client.http("adminMonthForm", "/admin/attendance?employeeId=" + EMPLOYEE + "&month=2026-08")

    def test_leave_details_must_match_the_selected_employee_and_date_before_and_after_approval(self):
        for operation, status, actor in [("approve.form", "PENDING", APPROVER),
                                         ("approved.detail", "APPROVED", EMPLOYEE)]:
            client, _, _ = client_with(Response(detail(status)), employee=actor, kind="approver")
            client.http(operation, "/requests/" + REQUEST, leave_scope=(EMPLOYEE, "2026-10-31"))
            for field, value in [("fromDate", "2026-11-01"), ("toDate", "2026-11-01"),
                                 ("targetEmployeeId", REQUEST)]:
                payload = detail(status)
                payload["data"]["request"][field] = value
                payload["data"]["detail"]["request"][field] = value
                client, _, _ = client_with(Response(payload), employee=actor, kind="approver")
                with self.subTest(operation=operation, field=field), self.assertRaises(AssertionError):
                    client.http(operation, "/requests/" + REQUEST, leave_scope=(EMPLOYEE, "2026-10-31"))

    def test_workbook_metadata_must_match_the_export_employee_and_exact_period(self):
        content_type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        for kwargs in [{"employee": APPROVER}, {"start": "2026-07-01"}, {"end": "2026-08-30"}]:
            client, rows, _ = client_with(Response(workbook(**kwargs), content_type=content_type), kind="admin")
            with self.subTest(kwargs=kwargs), self.assertRaises(AssertionError):
                client.http("workbookDownload", "/admin/reports/exports/" + REQUEST + "/download")

    def test_administration_builds_json_period_commands_without_extra_load_requests(self):
        for export in [False, True]:
            client, _, _ = client_with(kind="admin")
            client.run.groups = {1: {"employee": SimpleNamespace(data={"employeeId": EMPLOYEE})}}
            calls = []
            def http(operation, path, form=None, **kwargs):
                calls.append((operation, path, form))
                if operation == "adminMonthForm": return {}, month()
                return {"Location": "/admin/reports/exports/" + REQUEST + "/download"}, b""
            client.http = http
            client.administrative(export)
            self.assertEqual(["adminMonthForm", "exportPrepare", "workbookDownload"] if export else
                ["adminMonthForm", "recalculate"], [item[0] for item in calls])
            self.assertEqual(CSRF["token"], calls[1][2]["_csrf"])
            self.assertEqual(EMPLOYEE, calls[1][2]["employeeId"])
            self.assertEqual(REQUEST if export else COMMAND, calls[1][2]["commandId"])

    def test_leave_flow_uses_structured_date_ranges_and_independent_approval_version(self):
        client, _, _ = client_with()
        approver, _, _ = client_with(employee=APPROVER, kind="approver")
        client.run.groups = {1: {"approver": approver}}
        calls = []
        payload = mine([{"fromDate": "2026-10-28", "toDate": "2026-10-30", "targetEmployeeId": EMPLOYEE,
            "reason": "unrelated 2026-10-31 is not a leave date"}])
        def http(operation, path, form=None, **kwargs):
            calls.append((operation, path, form, kwargs))
            return ({}, payload) if operation == "leave.form" else ({"Location": "/requests/" + REQUEST}, detail("APPROVED"))
        def approve(operation, path, form=None, **kwargs):
            calls.append((operation, path, form, kwargs))
            return ({}, detail()) if operation == "approve.form" else ({"Location": "/requests/" + REQUEST}, b"")
        client.http, approver.http = http, approve
        client.leave_flow()
        self.assertEqual(["leave.form", "leave.submit", "approve.form", "approve.submit", "approved.detail"], [c[0] for c in calls])
        self.assertEqual("2026-10-31", calls[1][2]["from"])
        self.assertEqual("2026-10-31", calls[1][2]["to"])
        self.assertEqual(7, calls[3][2]["expectedVersion"])
        self.assertEqual(COMMAND, calls[3][2]["commandId"])
        self.assertEqual((EMPLOYEE, "2026-10-31"), calls[2][3]["leave_scope"])
        self.assertEqual((EMPLOYEE, "2026-10-31"), calls[4][3]["leave_scope"])
        self.assertEqual(1, client.leave_count)

    def test_workbook_tenant_and_same_url_byte_immutability_are_verified(self):
        content_type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        original, changed = workbook(), workbook(suffix="changed")
        client, rows, _ = client_with(Response(original, content_type=content_type),
            Response(original, content_type=content_type), Response(changed, content_type=content_type))
        path = "/admin/reports/exports/" + REQUEST + "/download"
        client.http("workbookDownload", path)
        client.http("workbookDownload", path)
        with self.assertRaises(AssertionError):
            client.http("workbookDownload", path)
        self.assertFalse(rows[-1]["ok"])

    def test_runtime_window_excludes_fixture_setup_and_incomplete_trailing_write(self):
        before = {"at": "2026-10-25T01:00:00Z", "heapUsedBytes": 10}
        during = {"at": "2026-10-25T01:02:00.123Z", "heapUsedBytes": 20}
        content = json.dumps(before) + "\n" + json.dumps(during) + "\n{"
        self.assertEqual([during], soak.runtime_rows(content, "2026-10-25T01:01:00+00:00"))

    def test_pacing_occurs_inside_the_client_lock_before_the_request_timer(self):
        events, rows = [], []
        client, _, _ = client_with()
        run = client.run
        run.pacer = SimpleNamespace(start=0)
        run.record = rows.append

        def pace():
            self.assertTrue(client.lock.locked(), "Shared client must own its cookie lock before reserving a send slot")
            events.append("pacer")

        def send(*args, **kwargs):
            self.assertTrue(client.lock.locked())
            events.append("send")
            return Response(today())

        def timer():
            events.append("timer")
            return 100.0

        run.pacer.wait = pace
        client.opener = SimpleNamespace(open=send)
        with patch.object(soak.time, "monotonic", side_effect=timer):
            client.http("today", "/me/attendance", contains="SOAK_T1_")
        self.assertEqual(["pacer", "timer", "send"], events[:3])
        self.assertTrue(rows[0]["ok"])


if __name__ == "__main__":
    unittest.main()
