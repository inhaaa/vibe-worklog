"""Authenticated, paced, local-only HTTP mixed load using disposable synthetic fixture metadata."""
import argparse
import calendar
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta, datetime, timezone
import hashlib
import http.cookiejar
import io
import json
import math
from pathlib import Path
import random
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

PAGE_TYPE = "application/vnd.worklog.page+json"
PAGE_OPERATIONS = {
    "login.form": "login", "login.verify": "workspace/profile", "today": "clock/history", "clock.form": "clock/history",
    "historicalDay": "clock/history", "month": "worktime/month", "adminMonthForm": "worktime/month",
    "resultDetail": "worktime/result", "myRequests": "request/mine", "leave.form": "request/mine",
    "auditPage": "worktime/audit", "employeeList": "org/employees", "approvalInbox": "request/list",
    "approve.form": "request/detail", "approved.detail": "request/detail",
}


def local_base(value):
    address = urllib.parse.urlsplit(value)
    if (address.scheme != "http" or address.hostname != "127.0.0.1" or address.username is not None
            or address.password is not None or address.path not in ("", "/") or address.query or address.fragment
            or address.port is None or not 1 <= address.port <= 65535):
        raise ValueError("Only direct http://127.0.0.1:<port> origins are allowed")
    return value.rstrip("/")


def local_location(base, value):
    source = urllib.parse.urlsplit(base)
    target = urllib.parse.urlsplit(urllib.parse.urljoin(base + "/", value))
    assert (target.scheme, target.netloc) == (source.scheme, source.netloc), "External redirect refused"
    return urllib.parse.urlunsplit(("", "", target.path, target.query, ""))


def month_bounds(value):
    first = date.fromisoformat(value + "-01")
    return first, first.replace(day=calendar.monthrange(first.year, first.month)[1])


def csrf_fields(page):
    csrf = page.get("csrf")
    assert isinstance(csrf, dict) and csrf.get("parameterName") == "_csrf", "Invalid CSRF parameter"
    assert csrf.get("headerName") == "X-CSRF-TOKEN", "Invalid CSRF header"
    assert isinstance(csrf.get("token"), str) and csrf["token"].strip(), "Missing CSRF token"
    return {"_csrf": csrf["token"]}


def command_id(value):
    try:
        assert isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, AttributeError, AssertionError):
        raise AssertionError("Invalid command identifier") from None
    return value


def form_fields(page, action):
    fields, data, name = csrf_fields(page), page["data"], page["page"]
    if (name, action) in [("login", "/login"), ("clock/history", "/logout"), ("workspace/profile", "/logout")]:
        return fields
    if name == "request/mine" and action == "/me/requests/leave":
        form = data["form"]
        fields.update({key: form[key] if form[key] is not None else "" for key in
                       ["bucketId", "from", "to", "predecessorRequestId", "reason", "commandId"]})
    elif name == "worktime/month" and action in ["/admin/calculations", "/admin/reports/exports"]:
        fields.update({key: data[key] for key in ["employeeId", "from", "to"]})
        fields["commandId"] = data["exportCommand" if action.endswith("/exports") else "calculateCommand"]
    elif name == "request/detail" and action == "/requests/" + data["request"]["id"] + "/approve":
        version = data["request"].get("version")
        assert type(version) is int and version >= 0, "Missing optimistic version"
        fields.update(expectedVersion=version, commandId=data.get("pageCommand"))
    else:
        raise AssertionError("Unknown JSON form action")
    command_id(fields.get("commandId"))
    return fields


def page_envelope(body, expected):
    try:
        page = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise AssertionError("Malformed page JSON") from None
    assert isinstance(page, dict) and page.get("page") == expected, "Unexpected page envelope"
    assert isinstance(page.get("data"), dict) and isinstance(page.get("csrf"), dict), "Invalid page schema"
    return page


def summarize(rows):
    def aggregate(items):
        latency = sorted(row["latencyMillis"] for row in items)
        return {"requests": len(items), "failures": sum(not row["ok"] for row in items),
                "expectedRejections": sum(row["expectedRejection"] for row in items),
                "p50Millis": latency[math.ceil(len(latency) * .50) - 1] if latency else None,
                "p95Millis": latency[math.ceil(len(latency) * .95) - 1] if latency else None,
                "maxMillis": latency[-1] if latency else None,
                "statusCounts": dict(Counter(str(row["status"]) for row in items))}
    result = aggregate(rows)
    operations, windows = defaultdict(list), defaultdict(list)
    for row in rows:
        operations[row["operation"]].append(row)
        windows[int(row.get("elapsedSeconds", 0) // 60)].append(row)
    result["operations"] = {key: aggregate(items) for key, items in sorted(operations.items())}
    result["minuteWindows"] = [{"minute": key, **aggregate(items)} for key, items in sorted(windows.items())]
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None


def runtime_rows(content, started_at):
    lines = content.splitlines()
    if not content.endswith("\n"):
        lines = lines[:-1]
    started = datetime.fromisoformat(started_at)
    return [row for row in (json.loads(line) for line in lines if line.strip())
            if datetime.fromisoformat(row["at"]) >= started]


class Done(Exception):
    pass


class Pacer:
    def __init__(self, duration, rate, ramp):
        self.start = time.monotonic()
        self.deadline, self.next = self.start + duration, self.start
        self.rate, self.ramp = rate, ramp
        self.lock, self.stop = threading.Lock(), threading.Event()

    def wait(self):
        with self.lock:
            now = time.monotonic()
            if now >= self.deadline or self.stop.is_set():
                raise Done()
            rate = max(.5, self.rate * min(1, (now - self.start) / max(1, self.ramp)))
            when = max(now, self.next)
            self.next = when + 1 / rate
        if when >= self.deadline or self.stop.wait(max(0, when - time.monotonic())):
            raise Done()


class Run:
    def __init__(self, metadata, duration, rate, ramp, output):
        self.data, self.base, self.output = metadata, local_base(metadata["baseUrl"]), output
        self.pacer = Pacer(duration, rate, ramp)
        self.rows, self.failures, self.lock = [], [], threading.Lock()
        self.duration = duration
        self.started_at = datetime.now(timezone.utc).isoformat()
        self.clients = [Client(self, item) for item in metadata["users"]]
        self.groups = {}
        for group in {item["group"] for item in metadata["users"]}:
            selected = [client for client in self.clients if client.data["group"] == group]
            self.groups[group] = {kind: next(c for c in selected if c.data["kind"] == kind) for kind in ["admin", "approver", "employee"]}

    def record(self, row):
        with self.lock:
            self.rows.append(row)
            with self.output.with_suffix(".requests.jsonl").open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(row) + "\n")

    def snapshot(self, final=False):
        with self.lock:
            rows, failures = list(self.rows), list(self.failures)
        result = summarize(rows)
        result.update({"startedAt": self.started_at, "elapsedSeconds": round(time.monotonic() - self.pacer.start, 3),
                       "durationSeconds": self.duration, "targetHttpRequestsPerSecond": self.pacer.rate,
                       "rampSeconds": self.pacer.ramp, "sessions": len(self.clients), "tenants": 3,
                       "employeesPerTenant": 300, "historyRawEvents": self.data["rawHistoryEvents"],
                       "final": final, "logicalOperationFailures": failures,
                       "scope": "Real local HTTP including session/CSRF/body transfer. Paced closed workload; pacing/client queue, browser rendering and external network excluded.",
                       "windows": "60s nearest-rank over all responses; expected 403/404 separate; unexpected status or semantics never ignored"})
        runtime = Path(self.data["runtimeFile"])
        if runtime.exists():
            samples = runtime_rows(runtime.read_text(encoding="utf-8"), self.started_at)
            result["runtimeSamples"] = len(samples)
            if samples:
                result["runtimeFirst"], result["runtimeLast"] = samples[0], samples[-1]
                result["runtimeMax"] = {key: max(row[key] for row in samples) for key in ["heapUsedBytes", "threadCount", "appPoolPending", "appPoolTotal", "dbConnections", "waitingAdvisoryLocks", "dbBytes"]}
        self.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return result

    def execute(self):
        # Real login/CSRF before measured traffic; no authentication bypass.
        for client in self.clients:
            client.login()
        self.output.with_suffix(".requests.jsonl").write_text("", encoding="utf-8")
        self.pacer = Pacer(self.duration, self.pacer.rate, self.pacer.ramp)
        self.started_at = datetime.now(timezone.utc).isoformat()
        with ThreadPoolExecutor(max_workers=len(self.clients)) as executor:
            futures = [executor.submit(client.work) for client in self.clients]
            try:
                while not all(future.done() for future in futures):
                    if self.pacer.stop.wait(min(10, max(.01, self.pacer.deadline - time.monotonic()))):
                        break
                    self.snapshot()
                for future in futures:
                    future.result()
            finally:
                self.pacer.stop.set()
        result = self.snapshot(True)
        assert not result["failures"] and not result["logicalOperationFailures"], "Unexpected HTTP/semantic failure; inspect result JSON"
        if time.monotonic() < self.pacer.deadline:
            time.sleep(self.pacer.deadline - time.monotonic())
            result = self.snapshot(True)
        return result


class Client:
    def __init__(self, run, data):
        self.run, self.data = run, data
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect())
        self.lock, self.random = threading.Lock(), random.Random(str(data["employeeId"]))
        self.leave_count = 0
        self.workbook_digests = {}

    def validate_page(self, operation, path, page, leave_scope=None):
        data, name = page["data"], page["page"]
        csrf_fields(page)
        assert not data.get("error"), "Page returned an application error"
        if name == "login":
            return
        marker = self.data["marker"]
        assert data["workspaceEmployee"]["companyName"].startswith(marker), "Wrong workspace identity"
        roles = data["workspacePrincipal"]["roles"]
        assert isinstance(roles, list) and "EMPLOYEE" in roles, "Employee session missing"
        if operation in ["auditPage", "employeeList", "adminMonthForm"]:
            assert "ATTENDANCE_ADMIN" in roles, "Admin authorization missing"
        if operation in ["approvalInbox", "approve.form"]:
            assert "APPROVER" in roles, "Approver authorization missing"
        if name == "workspace/profile":
            profile = data.get("profile")
            assert isinstance(profile, dict) and all(profile.get(key) == data["workspaceEmployee"][key]
                for key in ("employeeName", "companyName")), "Wrong personal profile"
        elif name == "clock/history":
            day = data["day"]
            expected = self.run.data["historyDate" if operation == "historicalDay" else "today"]
            assert day["employeeId"] == self.data["employeeId"] and day["workDate"] == expected, "Wrong employee/day"
            if operation == "historicalDay":
                assert day.get("confirmation") and {event["kind"] for event in day["effectiveEvents"]} == {"IN", "OUT"}, "Historical confirmation missing"
        elif name == "worktime/month":
            target = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query).get("employeeId", [self.data["employeeId"]])[0]
            view = data["overview"]
            assert data["employeeId"] == target and view["month"] == self.run.data["historyMonth"], "Wrong monthly scope"
            assert isinstance(view["days"], list) and view["days"], "Monthly results missing"
            first, last = month_bounds(self.run.data["historyMonth"])
            assert data["from"] == str(first) and data["to"] == str(last), "Wrong monthly command period"
            expected_dates = {str(first + timedelta(days=offset)) for offset in range(last.day)}
            assert len(view["days"]) == last.day and {day["workDate"] for day in view["days"]} == expected_dates, "Incomplete or duplicate monthly results"
            for day in view["days"]:
                assert day["employeeId"] == target and day["workDate"].startswith(view["month"] + "-"), "Foreign monthly result"
                assert day["result"]["workDate"] == day["workDate"], "Result date mismatch"
                assert day["result"]["status"] == "CALCULATED" and day["result"]["actualWorkMillis"] == 28_800_000, "Seeded eight-hour result changed"
            assert view["summary"]["result"]["confirmedWorkMillis"] == len(view["days"]) * 28_800_000, "Monthly total mismatch"
        elif name == "worktime/result":
            day = data["detail"]["day"]
            assert day["id"] == self.data["resultId"] == path.rsplit("/", 1)[1], "Wrong result identifier"
            assert day["employeeId"] == self.data["employeeId"] and day["workDate"] == self.run.data["historyDate"], "Wrong result owner/day"
            assert day["result"]["status"] == "CALCULATED" and day["result"]["actualWorkMillis"] == 28_800_000, "Seeded result changed"
        elif name == "request/mine":
            assert isinstance(data["requests"], list) and isinstance(data["buckets"], list), "Request lists missing"
            assert all(item["targetEmployeeId"] == self.data["employeeId"] for item in data["requests"]), "Foreign personal request"
            assert all(item["employeeId"] == self.data["employeeId"] for item in data["buckets"]), "Foreign leave bucket"
            form_fields(page, "/me/requests/leave")
        elif name == "request/list":
            assert data.get("title") == "결재 대기함" and data.get("completed") is False and isinstance(data.get("requests"), list), "Pending approval inbox missing"
            requested_page = int(urllib.parse.parse_qs(urllib.parse.urlsplit(path).query).get("page", ["0"])[0])
            assert urllib.parse.urlsplit(path).path == "/approvals" and type(data.get("page")) is int and 0 <= data["page"] <= 100_000 and data["page"] == requested_page, "Wrong inbox page"
            assert type(data.get("hasNext")) is bool and len(data["requests"]) <= 50 and (not data["hasNext"] or len(data["requests"]) == 50), "Invalid inbox pagination"
            assert all(isinstance(item, dict) and item.get("currentApproverId") == self.data["employeeId"] and item.get("status") == "PENDING" for item in data["requests"]), "Wrong inbox approver or nonpending request"
        elif name == "request/detail":
            request = data["request"]
            assert request["id"] == path.rsplit("/", 1)[1] and data["detail"]["request"] == request, "Wrong request detail"
            assert request["kind"] == "LEAVE_DAY" and request["quantityDays"] == 1, "Unexpected leave request"
            assert leave_scope and request["targetEmployeeId"] == leave_scope[0], "Wrong selected leave employee"
            assert request["fromDate"] == request["toDate"] == leave_scope[1], "Wrong selected leave date"
            if operation == "approve.form":
                assert request["status"] == "PENDING" and data["canDecide"] is True, "Request cannot be independently approved"
                assert request["currentApproverId"] == self.data["employeeId"] != request["targetEmployeeId"], "Self or wrong approver"
                form_fields(page, path + "/approve")
            else:
                assert request["targetEmployeeId"] == self.data["employeeId"], "Foreign approved request"
                assert request["status"] == "APPROVED" and request["effectStatus"] == "EFFECTIVE", "Leave approval effect missing"
        elif name == "worktime/audit":
            assert isinstance(data["audits"], list) and data["audits"] and data["page"]["rows"] == data["audits"], "Audit page missing"
        elif name == "org/employees":
            assert isinstance(data["employees"], list) and data["employees"], "Employee directory missing"
            assert all(item["displayName"].startswith(marker) for item in data["employees"]), "Foreign directory entry"
            assert data["pageEmployeeCount"] == len(data["employees"]), "Directory count mismatch"
        else:
            raise AssertionError("Unknown page semantics")

    def http(self, operation, path, form=None, expected=200, contains=None, paced=True, leave_scope=None):
        assert path.startswith("/") and not path.startswith("//"), "Relative local path required"
        status, ok, error = 0, False, ""
        with self.lock:
            if paced:
                self.run.pacer.wait()
            start = time.monotonic()
            try:
                data = None if form is None else urllib.parse.urlencode(form).encode()
                try:
                    response = self.opener.open(urllib.request.Request(self.run.base + path, data=data,
                        headers={"Accept": PAGE_TYPE}), timeout=15)
                except urllib.error.HTTPError as failure:
                    response = failure
                with response:
                    status, headers, body = response.code, response.headers, response.read()
                assert status == expected, f"Expected {expected}, received {status}"
                assert "no-store" in headers.get("Cache-Control", ""), "Response is cacheable"
                content_type = headers.get("Content-Type", "")
                if operation == "workbookDownload":
                    assert "spreadsheetml" in content_type, "Workbook media type missing"
                if content_type.startswith("text/") or "json" in content_type:
                    text = body.decode("utf-8")
                    for other in [1, 2, 3]:
                        if other != self.data["group"]:
                            assert f"SOAK_T{other}_" not in text, "Foreign tenant marker exposed"
                    if contains:
                        assert contains in text, "Required page semantics missing"
                elif "spreadsheetml" in content_type:
                    assert body.startswith(b"PK"), "Invalid workbook"
                    with zipfile.ZipFile(io.BytesIO(body)) as workbook:
                        sheet = workbook.read("xl/worksheets/sheet1.xml").decode("utf-8")
                    namespace = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
                    rows = [[cell.findtext(namespace + "is/" + namespace + "t", "")
                             for cell in row.findall(namespace + "c")]
                            for row in ET.fromstring(sheet).findall(namespace + "sheetData/" + namespace + "row")]
                    assert len(rows) >= 3 and rows[0] and "REVIEW" in rows[0][0], "Workbook purpose missing"
                    assert len(rows[1]) == 4 and rows[1][2:] == ["고객사 ID", self.data["tenantId"]], "Workbook tenant metadata missing"
                    first, last = month_bounds(self.run.data["historyMonth"])
                    employee = self.run.groups[self.data["group"]]["employee"].data["employeeId"]
                    assert rows[2] == ["조회 시작일", str(first), "조회 종료일", str(last), "사원 ID", employee], "Workbook export scope mismatch"
                    for other in [1, 2, 3]:
                        if other != self.data["group"]:
                            assert f"SOAK_T{other}_" not in sheet, "Foreign workbook marker exposed"
                    digest = hashlib.sha256(body).hexdigest()
                    assert self.workbook_digests.setdefault(path, digest) == digest, "Immutable workbook changed"
                if status == 200 and operation != "workbookDownload" or status in (403, 404):
                    assert content_type.split(";", 1)[0].strip().lower() == PAGE_TYPE, "Expected React page JSON media type"
                    expected_page = "org/problem" if status in (403, 404) else PAGE_OPERATIONS.get(operation)
                    assert expected_page is not None, "Unknown page operation"
                    body = page_envelope(body, expected_page)
                    if status in (403, 404):
                        assert body["data"].get("status") == status, "Wrong denial semantics"
                    else:
                        self.validate_page(operation, path, body, leave_scope)
                if status in (302, 303):
                    assert headers.get("Location"), "Redirect destination missing"
                    local_location(self.run.base, headers["Location"])
                ok = True
                return headers, body
            except Exception as failure:
                error = type(failure).__name__ + ": " + str(failure)[:180]
                raise
            finally:
                if paced:
                    self.run.record({"operation": operation, "status": status, "latencyMillis": round((time.monotonic() - start) * 1000, 3),
                                     "ok": ok, "expectedRejection": ok and expected in (403, 404), "group": self.data["group"], "actorKind": self.data["kind"],
                                     "elapsedSeconds": round(time.monotonic() - self.run.pacer.start, 3), "error": error})

    def login(self):
        _, page = self.http("login.form", "/login", paced=False)
        form = form_fields(page, "/login")
        form.update(companyCode=self.data["companyCode"], email=self.data["email"], password=self.run.data["password"])
        headers, _ = self.http("login.submit", "/login", form, expected=302, paced=False)
        assert local_location(self.run.base, headers["Location"]) == "/me/home", "No authenticated session"
        self.http("login.verify", "/me/profile", contains=self.data["marker"], paced=False)

    def work(self):
        try:
            while True:
                self.operation()
        except Done:
            return
        except Exception as failure:
            with self.run.lock:
                self.run.failures.append({"group": self.data["group"], "kind": self.data["kind"], "type": type(failure).__name__, "message": str(failure)[:200]})
            self.run.pacer.stop.set()

    def operation(self):
        roll = self.random.randrange(100)
        if roll < 25:
            self.http("today", "/me/attendance", contains=self.data["marker"])
        elif roll < 40:
            self.http("historicalDay", "/me/attendance?date=" + self.run.data["historyDate"])
        elif roll < 60:
            self.http("month", "/me/worktime?month=" + self.run.data["historyMonth"])
        elif roll < 70:
            self.http("resultDetail", "/worktime/results/" + self.data["resultId"], contains=self.data["resultId"])
        elif roll < 80:
            self.http("myRequests", "/me/requests")
        elif roll < 86:
            _, page = self.http("clock.form", "/me/attendance", contains=self.data["marker"])
            csrf = form_fields(page, "/logout")["_csrf"]
            self.http("clock.replay", "/me/attendance/events", {"_csrf": csrf, "eventKind": "IN", "commandId": self.data["clockCommand"]}, expected=303)
        elif roll < 90:
            other = next(client for client in self.run.clients if client.data["group"] != self.data["group"])
            self.http("foreignResultDenied", "/worktime/results/" + other.data["resultId"], expected=404)
        elif self.data["kind"] == "admin":
            if roll < 95:
                self.http("auditPage", "/admin/audit")
            elif roll < 97:
                self.http("employeeList", "/admin/employees", contains=self.data["marker"])
            else:
                self.administrative(export=roll == 99)
        elif self.data["kind"] == "approver":
            self.http("approvalInbox", "/approvals")
        elif roll < 94:
            self.leave_flow()
        else:
            self.http("employeeAdminDenied", "/admin/audit", expected=403)

    def administrative(self, export):
        target = self.run.groups[self.data["group"]]["employee"].data["employeeId"]
        path = "/admin/attendance?" + urllib.parse.urlencode({"employeeId": target, "month": self.run.data["historyMonth"]})
        _, page = self.http("adminMonthForm", path)
        action = "/admin/reports/exports" if export else "/admin/calculations"
        form = form_fields(page, action)
        form["reason"] = "합성 HTTP 혼합 부하 검토"
        headers, _ = self.http("exportPrepare" if export else "recalculate", action, form, expected=303)
        if export:
            path = local_location(self.run.base, headers["Location"])
            assert path.startswith("/admin/reports/exports/") and path.endswith("/download")
            self.http("workbookDownload", path)

    def leave_flow(self):
        if self.leave_count >= 90:
            self.http("myRequests", "/me/requests")
            return
        approver = self.run.groups[self.data["group"]]["approver"]
        _, page = self.http("leave.form", "/me/requests")
        used = [(date.fromisoformat(item["fromDate"]), date.fromisoformat(item["toDate"]))
                for item in page["data"]["requests"] if item.get("fromDate") and item.get("toDate")]
        day = date.fromisoformat(self.run.data["today"]) + timedelta(days=1)
        while any(start <= day <= end for start, end in used):
            day += timedelta(days=1)
        form = form_fields(page, "/me/requests/leave")
        form.update(bucketId=self.data["bucketId"], **{"from": str(day), "to": str(day)}, reason="합성 HTTP 종일 연차")
        headers, _ = self.http("leave.submit", "/me/requests/leave", form, expected=303)
        path = local_location(self.run.base, headers["Location"])
        assert path.startswith("/requests/") and uuid.UUID(path.rsplit("/", 1)[1])
        scope = (self.data["employeeId"], str(day))
        _, page = approver.http("approve.form", path, leave_scope=scope)
        action = path + "/approve"
        form = form_fields(page, action)
        form["reason"] = "합성 HTTP 독립 승인"
        approver.http("approve.submit", action, form, expected=303)
        self.http("approved.detail", path, leave_scope=scope)
        self.leave_count += 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, default=Path("target/phase6-http-fixture.json"))
    parser.add_argument("--seconds", type=int, default=1800)
    parser.add_argument("--rate", type=float, default=8)
    parser.add_argument("--ramp-seconds", type=int, default=60)
    parser.add_argument("--output", type=Path, default=Path("target/phase6-http-soak.json"))
    parser.add_argument("--stop-fixture", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 3600 or not .5 <= args.rate <= 30 or not 0 <= args.ramp_seconds <= args.seconds:
        parser.error("duration 1..3600 seconds, rate .5..30 HTTP requests/s, ramp 0..duration required")
    target = Path("target").resolve()
    if (not args.output.resolve().is_relative_to(target) or args.output.suffix != ".json"
            or not args.metadata.resolve().is_relative_to(target) or args.output.resolve() == args.metadata.resolve()):
        parser.error("metadata and distinct JSON output must stay inside ignored target/")
    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    assert metadata["schemaVersion"] == 1 and len(metadata["users"]) == 18
    args.output.parent.mkdir(exist_ok=True)
    run = Run(metadata, args.seconds, args.rate, args.ramp_seconds, args.output)
    try:
        result = run.execute()
        print(f"LOCAL_HTTP_MIXED_PASS seconds={result['elapsedSeconds']} requests={result['requests']} errors={result['failures']} expected_denials={result['expectedRejections']} result={args.output}")
    except Exception as failure:
        run.failures.append({"type": type(failure).__name__, "message": str(failure)[:200]})
        raise
    finally:
        run.snapshot(True)
        if args.stop_fixture:
            stop = Path(metadata["stopFile"]).resolve()
            assert stop.parent == Path("target").resolve() and stop.name.startswith("phase6-http-") and stop.suffix == ".stop"
            stop.write_text("HTTP runner finished\n", encoding="utf-8")


if __name__ == "__main__":
    main()
