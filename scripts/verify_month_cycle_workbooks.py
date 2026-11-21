"""마감 후 직원 화면과 브라우저에서 받은 회사·부서 Excel을 날짜별 합성 근무 근거로 검산한다."""
import argparse
from collections import defaultdict
from datetime import date, datetime
import hashlib
import json
import math
from operator import itemgetter
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import BadZipFile, ZipFile


DEVICES = ("pc", "mobile")
MEASURES = ("actualMinutes", "paidLeaveMinutes", "leaveDays")
SUMMARY_HEADER = ["집계 범위", "부서코드", "사원 수(명)", "재직일수(일)", "실근로 발생 일수(일)",
                  "실근로시간(시간)", "사용 휴가(일)", "유급휴가 인정시간(시간)"]
DETAIL_HEADER = ["사번", "성명", "부서코드", "부서명", *SUMMARY_HEADER[3:]]
REPORT_FIELDS = ("employeeCount", "employedDays", "workedDays", "actualHours", "leaveDays", "paidLeaveHours")
NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
REL = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"


def same(actual, expected, label):
    if actual != expected:
        raise ValueError(f"{label} · 예상 {expected!r}, 실제 {actual!r}")


def unique(rows, key, label):
    index = {}
    for row in rows:
        identifier = key(row)
        if identifier in index:
            raise ValueError(f"{label} 중복 {identifier!r}")
        index[identifier] = row
    return index


def numbers(row, fields, label):
    values = [row[field] for field in fields]
    if any(type(value) not in (int, float) or not math.isfinite(value) or value < 0 for value in values):
        raise ValueError(f"{label} · 숫자 필요 {dict(zip(fields, values))!r}")
    return values


def daily_totals(days):
    return [sum(day[field] for day in days) for field in MEASURES]


def check_screens(expected, screens):
    same(screens["month"], expected["month"], "직원 화면 월")
    people = unique(expected["employees"], itemgetter("employeeNo"), "기대 사번")
    by_employee = {}
    for no, person in people.items():
        days = unique(person["days"], itemgetter("date"), f"기대 {no} 재직일")
        for key, day in days.items():
            same(date.fromisoformat(key).strftime("%Y-%m"), expected["month"], f"기대 {no} {key} 월")
            numbers(day, MEASURES, f"기대 {no} {key}")
        by_employee[no] = days
    for device in DEVICES:
        observed = unique(screens[device], itemgetter("employeeNo"), f"{device} 사번")
        same(observed.keys(), people.keys(), f"{device} 직원 화면 사번")
        for no, days in by_employee.items():
            month = observed[no]
            label = f"{device} {no}"
            daily = unique(month["days"], itemgetter("date"), f"{label} 근무일")
            same(daily.keys(), days.keys(), f"{label} 재직일")
            for key, expected_day in days.items():
                same(numbers(daily[key], MEASURES, f"{label} {key}"),
                     [expected_day[field] for field in MEASURES], f"{label} {key} {MEASURES}")
            same(numbers(month, (*MEASURES, "pendingDays"), f"{label} 월"),
                 [*daily_totals(days.values()), 0], f"{label} 월 실근로·유급휴가·사용일·확인 전")
    return people


def worksheet(path):
    # WorkLog ReviewWorkbook은 사용자 텍스트를 inlineStr로, 확정 수치를 숫자 셀로 기록한다.
    with ZipFile(path) as book:
        sheets = ET.fromstring(book.read("xl/workbook.xml")).findall(f"{NS}sheets/{NS}sheet")
        same([sheet.get("name") for sheet in sheets], ["월 마감 결과"], "시트 이름")
        relations = ET.fromstring(book.read("xl/_rels/workbook.xml.rels"))
        targets = [r.get("Target") for r in relations if r.get("Id") == sheets[0].get(REL + "id")]
        same(len(targets), 1, "시트 연결 수")
        target = targets[0]
        sheet = ET.fromstring(book.read(target.lstrip("/") if target.startswith("/") else "xl/" + target))
    rows = []
    for i, row in enumerate(sheet.findall(f"{NS}sheetData/{NS}row"), 1):
        same(row.get("r"), str(i), "행 주소")
        values = []
        for j, cell in enumerate(row.findall(NS + "c")):
            address = f"{chr(65 + j)}{i}"
            same(cell.get("r"), address, "셀 주소")
            if cell.find(NS + "f") is not None:
                raise ValueError(f"{address} 수식 셀")
            if cell.get("t") == "inlineStr":
                values.append("".join(node.text or "" for node in cell.iter(NS + "t")))
            elif cell.get("t", "n") == "n":
                number = float(cell.findtext(NS + "v", ""))
                if not math.isfinite(number):
                    raise ValueError(f"{address} 유한 숫자 필요")
                values.append(int(number) if number.is_integer() else number)
            else:
                raise ValueError(f"{address} 지원하지 않는 셀 형식 {cell.get('t')!r}")
        rows.append(values)
    return rows


def metrics(records):
    days = [day for _, day in records]
    actual, paid, leave = daily_totals(days)
    return [len({no for no, _ in records}), len(days), sum(day["actualMinutes"] > 0 for day in days),
            actual / 60, leave, paid / 60]


def check_rows(rows, expected, key, label):
    observed = unique(rows, key, label)
    same(observed.keys(), expected.keys(), f"{label} 대상")
    for identifier, row in expected.items():
        same(observed[identifier], row, f"{label} {identifier}")


def check_workbook(path, scope, records, expected, people, departments, closed_at):
    rows = worksheet(path)
    same(rows[0], ["월 마감 결과", expected["month"], scope], "월·범위")
    same(rows[1][:-1], ["마감 버전", expected["version"], "마감자", expected["closedBy"], "마감 일시(서울)"], "마감 이력")
    timestamp = rows[1][-1]
    parsed = datetime.strptime(timestamp, "%Y-%m-%d %H:%M:%S")
    same(timestamp, parsed.isoformat(sep=" "), "서울 마감시각")
    if closed_at is not None:
        same(timestamp, closed_at, "같은 마감의 시각")
    same(rows[2], ["자료 기준", "마감 시점 기준", "근무일 당시 소속"], "자료 기준")
    same(rows[3], SUMMARY_HEADER, "합계 열")
    total = metrics(records)
    same(rows[4], ["범위 합계", "", *total], "범위 합계")

    by_department = defaultdict(list)
    by_person = defaultdict(list)
    for no, day in records:
        code = day["departmentCode"]
        by_department[code].append((no, day))
        by_person[no, code].append((no, day))
    subtotals = {code: [departments[code], code, *metrics(days)] for code, days in by_department.items()}
    details = {(no, code): [no, people[no]["name"], code, departments[code], *metrics(days)[1:]]
               for (no, code), days in by_person.items()}
    detail_start = rows.index(DETAIL_HEADER, 5)
    check_rows([row for row in rows[5:detail_start] if row], subtotals, itemgetter(1), "부서 소계")
    check_rows(rows[detail_start + 1:], details, itemgetter(0, 2), "사원·부서 명세")
    return {"scope": scope, "closedAtSeoul": timestamp, **dict(zip(REPORT_FIELDS, rows[4][2:])),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def verify(fixture, evidence):
    expected = json.loads(fixture.read_text(encoding="utf-8-sig"))["expected"]
    screens = json.loads((evidence / "employee-screen.json").read_text(encoding="utf-8-sig"))
    people = check_screens(expected, screens)
    departments = {code: row["name"] for code, row in
                   unique(expected["departments"], itemgetter("code"), "기대 부서코드").items()}
    departments.setdefault("", "소속 미등록")
    records = [(no, day) for no, person in people.items() for day in person["days"]]
    scopes = [("company", "회사 전체", records)]
    for code in sorted({day["departmentCode"] for _, day in records}):
        subset = [(no, day) for no, day in records if day["departmentCode"] == code]
        scopes.append((code or "unassigned", departments[code], subset))
    workbooks = {}
    for device, companion in (("pc", "mobile"), ("mobile", "pc")):
        closed_at = None
        for code, scope, subset in scopes:
            primary = f"{device}-{code}.xlsx"
            paired = f"{device}-same-close/{companion}-{code}.xlsx"
            for filename in (primary, paired):
                try:
                    result = check_workbook(evidence / filename, scope, subset, expected, people, departments, closed_at)
                    if filename == paired:
                        same(result["sha256"], workbooks[primary]["sha256"], "같은 마감의 PC·모바일 SHA-256")
                except (ValueError, KeyError, IndexError, TypeError, OSError, BadZipFile, ET.ParseError) as error:
                    raise ValueError(f"{filename} · {error}") from error
                closed_at = result["closedAtSeoul"]
                workbooks[filename] = result
    return {"month": expected["month"], "version": expected["version"], "employeesPerDevice": len(people),
            "daysPerDevice": len(records), "workbooks": workbooks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, required=True, help="날짜별 expected를 포함한 로컬 fixture JSON")
    parser.add_argument("--evidence", type=Path, required=True, help="employee-screen.json과 pc/mobile 범위별 .xlsx 폴더")
    args = parser.parse_args()
    try:
        result = verify(args.fixture, args.evidence)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"검산 실패 · {error}\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
