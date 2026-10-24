"""Authenticated, paced, local-only HTTP mixed load using disposable synthetic fixture metadata."""
import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta, datetime, timezone
from html.parser import HTMLParser
import http.cookiejar
import io
import json
import math
from pathlib import Path
import random
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile


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


class Forms(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.forms, self.current = [], None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form":
            self.current = (attrs.get("action", ""), {})
        elif tag == "input" and self.current is not None and attrs.get("name"):
            self.current[1][attrs["name"]] = attrs.get("value", "")

    def handle_endtag(self, tag):
        if tag == "form" and self.current is not None:
            self.forms.append(self.current)
            self.current = None


def form_fields(html, action):
    parser = Forms()
    parser.feed(html)
    found = [fields for name, fields in parser.forms if name == action]
    assert len(found) == 1 and found[0].get("_csrf"), "Expected one form with CSRF: " + action
    return found[0]


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

    def http(self, operation, path, form=None, expected=200, contains=None, paced=True):
        assert path.startswith("/") and not path.startswith("//"), "Relative local path required"
        status, ok, error = 0, False, ""
        with self.lock:
            if paced:
                self.run.pacer.wait()
            start = time.monotonic()
            try:
                data = None if form is None else urllib.parse.urlencode(form).encode()
                try:
                    response = self.opener.open(urllib.request.Request(self.run.base + path, data=data), timeout=15)
                except urllib.error.HTTPError as failure:
                    response = failure
                with response:
                    status, headers, body = response.code, response.headers, response.read()
                assert status == expected, f"Expected {expected}, received {status}"
                assert "no-store" in headers.get("Cache-Control", ""), "Response is cacheable"
                content_type = headers.get("Content-Type", "")
                if contains:
                    assert "text/html" in content_type, "Required page did not return HTML"
                    assert contains in body.decode("utf-8"), "Required page semantics missing"
                if operation == "workbookDownload":
                    assert "spreadsheetml" in content_type, "Workbook media type missing"
                if content_type.startswith("text/") or "json" in content_type:
                    text = body.decode("utf-8")
                    for other in [1, 2, 3]:
                        if other != self.data["group"]:
                            assert f"SOAK_T{other}_" not in text, "Foreign tenant marker exposed"
                elif "spreadsheetml" in content_type:
                    assert body.startswith(b"PK"), "Invalid workbook"
                    with zipfile.ZipFile(io.BytesIO(body)) as workbook:
                        sheet = workbook.read("xl/worksheets/sheet1.xml").decode("utf-8")
                    assert "REVIEW" in sheet and self.data["tenantId"] in sheet, "Workbook tenant metadata missing"
                    for other in [1, 2, 3]:
                        if other != self.data["group"]:
                            assert f"SOAK_T{other}_" not in sheet, "Foreign workbook marker exposed"
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
        form = form_fields(page.decode(), "/login")
        form.update(companyCode=self.data["companyCode"], email=self.data["email"], password=self.run.data["password"])
        headers, _ = self.http("login.submit", "/login", form, expected=302, paced=False)
        assert local_location(self.run.base, headers["Location"]) == "/me/today", "No authenticated session"
        self.http("login.verify", "/me/today", contains=self.data["marker"], paced=False)

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
            self.http("today", "/me/today", contains=self.data["marker"])
        elif roll < 40:
            self.http("historicalDay", "/me/attendance?date=" + self.run.data["historyDate"], contains="실제 근로")
        elif roll < 60:
            self.http("month", "/me/worktime?month=" + self.run.data["historyMonth"], contains="월 요약")
        elif roll < 70:
            self.http("resultDetail", "/worktime/results/" + self.data["resultId"], contains=self.data["resultId"])
        elif roll < 80:
            self.http("myRequests", "/me/requests", contains="내 근태 신청")
        elif roll < 86:
            _, page = self.http("clock.form", "/me/today", contains=self.data["marker"])
            csrf = form_fields(page.decode(), "/logout")["_csrf"]
            self.http("clock.replay", "/me/attendance/events", {"_csrf": csrf, "eventKind": "IN", "commandId": self.data["clockCommand"]}, expected=303)
        elif roll < 90:
            other = next(client for client in self.run.clients if client.data["group"] != self.data["group"])
            self.http("foreignResultDenied", "/worktime/results/" + other.data["resultId"], expected=404)
        elif self.data["kind"] == "admin":
            if roll < 95:
                self.http("auditPage", "/admin/audit", contains="감사")
            elif roll < 97:
                self.http("employeeList", "/admin/employees", contains=self.data["marker"])
            else:
                self.administrative(export=roll == 99)
        elif self.data["kind"] == "approver":
            self.http("approvalInbox", "/approvals", contains="지정 승인함")
        elif roll < 94:
            self.leave_flow()
        else:
            self.http("employeeAdminDenied", "/admin/audit", expected=403)

    def administrative(self, export):
        target = self.run.groups[self.data["group"]]["employee"].data["employeeId"]
        path = "/admin/attendance?" + urllib.parse.urlencode({"employeeId": target, "month": self.run.data["historyMonth"]})
        _, page = self.http("adminMonthForm", path, contains="월 요약")
        action = "/admin/reports/exports" if export else "/admin/calculations"
        form = form_fields(page.decode(), action)
        form["reason"] = "합성 HTTP 혼합 부하 검토"
        headers, _ = self.http("exportPrepare" if export else "recalculate", action, form, expected=303)
        if export:
            path = local_location(self.run.base, headers["Location"])
            assert path.startswith("/admin/reports/exports/") and path.endswith("/download")
            self.http("workbookDownload", path)

    def leave_flow(self):
        if self.leave_count >= 90:
            self.http("myRequests", "/me/requests", contains="내 근태 신청")
            return
        approver = self.run.groups[self.data["group"]]["approver"]
        _, page = self.http("leave.form", "/me/requests", contains="내 근태 신청")
        used = set(re.findall(r"\d{4}-\d{2}-\d{2}", page.decode()))
        day = date.fromisoformat(self.run.data["today"]) + timedelta(days=1)
        while str(day) in used:
            day += timedelta(days=1)
        form = form_fields(page.decode(), "/me/requests/leave")
        form.update(bucketId=self.data["bucketId"], **{"from": str(day), "to": str(day)}, reason="합성 HTTP 종일 연차")
        headers, _ = self.http("leave.submit", "/me/requests/leave", form, expected=303)
        path = local_location(self.run.base, headers["Location"])
        assert path.startswith("/requests/") and uuid.UUID(path.rsplit("/", 1)[1])
        _, page = approver.http("approve.form", path, contains="PENDING")
        action = path + "/approve"
        form = form_fields(page.decode(), action)
        form["reason"] = "합성 HTTP 독립 승인"
        approver.http("approve.submit", action, form, expected=303)
        self.http("approved.detail", path, contains="APPROVED")
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
