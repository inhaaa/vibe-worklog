"""월 마감의 화면·회사·부서 대조. 실제 업무 규칙과 독립적인 작은 XLSX로 검산기를 시험한다."""
import copy
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from xml.etree import ElementTree as ET
from zipfile import ZipFile


SCRIPT = Path(__file__).with_name("verify_month_cycle_workbooks.py")
MONTH = "2026-09"
DEPARTMENTS = {"A01": "연구팀", "B02": "고객팀"}
SUMMARY_HEADER = ["집계 범위", "부서코드", "사원 수(명)", "재직일수(일)", "실근로 발생 일수(일)",
                  "실근로시간(시간)", "사용 휴가(일)", "유급휴가 인정시간(시간)"]
DETAIL_HEADER = ["사번", "성명", "부서코드", "부서명", *SUMMARY_HEADER[3:]]
COMPANY = [3, 4, 2, 11.5, 1.5, 8]
SUBTOTALS = {"A01": [2, 2, 1, 7.5, 0, 0], "B02": [2, 2, 1, 4, 1.5, 8]}
DETAILS = [
    ["001", "합성이동", "A01", "연구팀", 1, 1, 7.5, 0, 0],
    ["003", "합성휴무", "A01", "연구팀", 1, 0, 0, 0, 0],
    ["001", "합성이동", "B02", "고객팀", 1, 0, 0, 1, 8],
    ["002", "합성대조", "B02", "고객팀", 1, 1, 4, 0.5, 0],
]


def day(date, actual, paid, leave, **extra):
    return {"date": MONTH + "-" + date, "actualMinutes": actual, "paidLeaveMinutes": paid, "leaveDays": leave, **extra}


def employee(no, actual, paid, leave, *days):
    return {"employeeNo": no, "actualMinutes": actual, "paidLeaveMinutes": paid, "leaveDays": leave,
            "pendingDays": 0, "days": list(days)}


EXPECTED = {
    "month": MONTH, "version": 1, "closedBy": "합성담당",
    "departments": [{"code": code, "name": name} for code, name in DEPARTMENTS.items()],
    "employees": [
        {"employeeNo": "001", "name": "합성이동", "days": [
            day("29", 450, 0, 0, departmentCode="A01"), day("30", 0, 480, 1, departmentCode="B02")]},
        {"employeeNo": "002", "name": "합성대조", "days": [day("30", 240, 0, 0.5, departmentCode="B02")]},
        {"employeeNo": "003", "name": "합성휴무", "days": [day("30", 0, 0, 0, departmentCode="A01")]},
    ],
}
SCREEN = [
    employee("001", 450, 480, 1, day("29", 450, 0, 0), day("30", 0, 480, 1)),
    employee("002", 240, 0, 0.5, day("30", 240, 0, 0.5)),
    employee("003", 0, 0, 0, day("30", 0, 0, 0)),
]


def rows_for(scope):
    codes = DEPARTMENTS if scope == "company" else [scope]
    total = COMPANY if scope == "company" else SUBTOTALS[scope]
    return copy.deepcopy([
        ["월 마감 결과", MONTH, DEPARTMENTS.get(scope, "회사 전체")],
        ["마감 버전", 1, "마감자", "합성담당", "마감 일시(서울)", "2026-10-01 11:00:00"],
        ["자료 기준", "마감 시점 기준", "근무일 당시 소속"], SUMMARY_HEADER, ["범위 합계", "", *total],
        *[[DEPARTMENTS[code], code, *SUBTOTALS[code]] for code in codes], [], DETAIL_HEADER,
        *[row for row in DETAILS if row[2] in codes],
    ])


def write_book(path, rows):
    ns = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
    root = ET.Element(ns + "worksheet")
    data = ET.SubElement(root, ns + "sheetData")
    for number, values in enumerate(rows, 1):
        row = ET.SubElement(data, ns + "row", r=str(number))
        for col, value in enumerate(values):
            cell = ET.SubElement(row, ns + "c", r=f"{chr(65 + col)}{number}")
            if isinstance(value, str):
                cell.set("t", "inlineStr")
                ET.SubElement(ET.SubElement(cell, ns + "is"), ns + "t").text = value
            else:
                cell.set("t", "n")
                if isinstance(value, tuple):
                    formula, value = value
                    ET.SubElement(cell, ns + "f").text = formula
                ET.SubElement(cell, ns + "v").text = str(value)
    with ZipFile(path, "w") as book:
        book.writestr("xl/worksheets/sheet1.xml", ET.tostring(root, encoding="utf-8"))
        book.writestr("xl/workbook.xml", f'<workbook xmlns="{ns[1:-1]}" '
                      'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                      '<sheets><sheet name="월 마감 결과" sheetId="1" r:id="rId1"/></sheets></workbook>')
        book.writestr("xl/_rels/workbook.xml.rels", '<Relationships '
                      'xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                      '<Relationship Id="rId1" Target="worksheets/sheet1.xml" '
                      'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"/>'
                      '</Relationships>')


class MonthCycleWorkbookTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        self.fixture = self.directory / "fixture.json"
        self.fixture.write_text(json.dumps({"expected": EXPECTED}), encoding="utf-8")
        self.screens = {"month": MONTH, "pc": copy.deepcopy(SCREEN), "mobile": copy.deepcopy(SCREEN)}
        for device in ("pc", "mobile"):
            companion = "mobile" if device == "pc" else "pc"
            same_close = self.directory / f"{device}-same-close"
            same_close.mkdir()
            for scope in ("company", *DEPARTMENTS):
                path = self.directory / f"{device}-{scope}.xlsx"
                write_book(path, rows_for(scope))
                shutil.copyfile(path, same_close / f"{companion}-{scope}.xlsx")

    def run_check(self):
        (self.directory / "employee-screen.json").write_text(json.dumps(self.screens), encoding="utf-8")
        return subprocess.run([sys.executable, "-X", "utf8", str(SCRIPT), "--fixture", str(self.fixture),
                               "--evidence", str(self.directory)], text=True, encoding="utf-8", capture_output=True)

    def assert_rejected(self, message):
        result = self.run_check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(message, result.stderr)

    def test_company_counts_the_transferred_employee_once_and_retains_paid_and_unpaid_leave(self):
        result = self.run_check()
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(12, len(report["workbooks"]))
        self.assertEqual(3, report["workbooks"]["pc-company.xlsx"]["employeeCount"])
        self.assertEqual(8, report["workbooks"]["pc-B02.xlsx"]["paidLeaveHours"])

    def test_employee_daily_values_are_checked_even_if_month_is_correct(self):
        self.screens["mobile"][0]["days"].reverse()
        self.screens["mobile"][0]["days"][0]["date"] = MONTH + "-29"
        self.screens["mobile"][0]["days"][1]["date"] = MONTH + "-30"
        self.assert_rejected("mobile 001 2026-09-29")

    def test_employee_month_and_its_pending_count_are_checked_even_if_days_match(self):
        for field in ("actualMinutes", "paidLeaveMinutes", "leaveDays", "pendingDays"):
            with self.subTest(field=field):
                self.screens["pc"][0][field] += 1
                self.assert_rejected("pc 001 월")
                self.screens["pc"][0][field] -= 1

    def test_duplicate_employee_or_date_cannot_replace_another_row(self):
        self.screens["pc"][2] = self.screens["pc"][1]
        self.assert_rejected("중복")
        self.screens["pc"] = copy.deepcopy(SCREEN)
        self.screens["pc"][0]["days"][1] = self.screens["pc"][0]["days"][0]
        self.assert_rejected("중복")

    def test_worked_hours_and_paid_hours_cannot_be_merged_in_both_downloads(self):
        rows = rows_for("B02")
        rows[-2][-3:] = [8, 1, 0]
        for device in ("pc", "mobile"):
            write_book(self.directory / f"{device}-B02.xlsx", rows)
        self.assert_rejected("B02.xlsx")

    def test_actual_hours_missing_paid_column_or_numeric_text_cannot_pass(self):
        for change in ("legacy", "text", "formula"):
            with self.subTest(change=change):
                rows = rows_for("company")
                if change == "legacy":
                    for row in rows[3:]:
                        if row:
                            row.pop()
                else:
                    rows[4][-1] = "8" if change == "text" else ("4*2", 8)
                write_book(self.directory / "mobile-company.xlsx", rows)
                self.assert_rejected("mobile-company.xlsx")

    def test_department_rows_cannot_leak_or_repeat_with_unchanged_scope_totals(self):
        for wrong_row in (DETAILS[2], DETAILS[0]):
            with self.subTest(wrong_row=wrong_row):
                rows = rows_for("A01")
                rows[-1] = wrong_row
                write_book(self.directory / "pc-A01.xlsx", rows)
                self.assert_rejected("pc-A01.xlsx")

    def test_company_headcount_and_department_subtotals_are_checked_individually(self):
        for row, col, wrong in [(4, 2, 4), (6, -1, 0), (5, 0, "고객팀")]:
            with self.subTest(row=row, col=col):
                rows = rows_for("company")
                rows[row][col] = wrong
                write_book(self.directory / "pc-company.xlsx", rows)
                self.assert_rejected("pc-company.xlsx")

    def test_close_version_source_and_timestamp_must_match_the_downloaded_scope(self):
        for row, col, wrong in [(0, 1, "2026-08"), (1, 1, 3), (2, 1, "기존 마감 · 이관 시점 기준"),
                                (1, 5, "2026-10-01 11:01:00")]:
            with self.subTest(row=row, col=col):
                rows = rows_for("A01")
                rows[row][col] = wrong
                write_book(self.directory / "mobile-A01.xlsx", rows)
                self.assert_rejected("mobile-A01.xlsx")

    def test_same_close_second_device_download_is_required_and_compared(self):
        path = self.directory / "pc-same-close" / "mobile-B02.xlsx"
        path.rename(path.with_suffix(".missing"))
        self.assert_rejected("pc-same-close/mobile-B02.xlsx")
        rows = rows_for("B02")
        rows[1][-1] = "2026-10-01 10:45:00"
        write_book(path, rows)
        self.assert_rejected("pc-same-close/mobile-B02.xlsx")


if __name__ == "__main__":
    unittest.main()
