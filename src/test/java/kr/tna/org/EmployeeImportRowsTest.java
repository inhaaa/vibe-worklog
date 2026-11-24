package kr.tna.org;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EmployeeImportRowsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private static EmployeeImportRows.Row row(String no, String name, String joined, String birth, String org, String grade, String position) {
        return new EmployeeImportRows.Row("r1", no, name, joined, birth, org, grade, position);
    }

    private static String error(EmployeeImportRows.Parsed parsed, String field) {
        return parsed.errors().stream().filter(e -> e.field().equals(field)).map(EmployeeImportRows.FieldError::message)
                .findFirst().orElse(null);
    }

    @Test void acceptsFourDateFormatsAndTrimsCells() {
        for (String joined : new String[] {"2019-03-04", "2019.03.04", "2019/03/04", "20190304", " 2019-03-04 "}) {
            var parsed = EmployeeImportRows.check(row(" 19001 ", " 홍길동 ", joined, "1990-05-12", " D10 ", "", " 팀장 "), TODAY);
            assertEquals(java.util.List.of(), parsed.errors(), joined);
            assertEquals(LocalDate.of(2019, 3, 4), parsed.joinedOn());
            assertEquals("19001", parsed.employeeNo());
            assertEquals("홍길동", parsed.displayName());
            assertEquals("D10", parsed.organizationCode());
            assertNull(parsed.gradeName());
            assertEquals("팀장", parsed.positionName());
        }
    }

    @Test void reportsEachInvalidCellOnItsOwnField() {
        var parsed = EmployeeImportRows.check(row("a@b", "", "45231", "2019-02-30", "", "x".repeat(121), ""), TODAY);
        assertEquals("사번은 1~40자이며 @를 쓸 수 없습니다.", error(parsed, "employeeNo"));
        assertNotNull(error(parsed, "displayName"));
        assertEquals("날짜 형식으로 표시한 뒤 복사하세요.", error(parsed, "joinedOn"));
        assertEquals("올바른 날짜가 아닙니다.", error(parsed, "birthDate"));
        assertEquals("조직코드를 입력해 주세요.", error(parsed, "organizationCode"));
        assertNotNull(error(parsed, "gradeName"));
    }

    @Test void joiningDateMustNotBeInTheFutureAndBirthDateMustPrecedeIt() {
        assertEquals("입사일이 오늘 이후면 입사 예정 관리에서 등록해 주세요.",
                error(EmployeeImportRows.check(row("", "가", "2026-11-25", "1990-01-01", "D10", "", ""), TODAY), "joinedOn"));
        assertEquals("생년월일은 입사일 이전이어야 합니다.",
                error(EmployeeImportRows.check(row("", "가", "2019-03-04", "2019-03-04", "D10", "", ""), TODAY), "birthDate"));
        assertEquals("생년월일은 1900-01-01 이후여야 합니다.",
                error(EmployeeImportRows.check(row("", "가", "2019-03-04", "1899-12-31", "D10", "", ""), TODAY), "birthDate"));
        assertNull(EmployeeImportRows.check(row("", "가", "2026-11-24", "1990-01-01", "D10", "", ""), TODAY).employeeNo());
    }

    @Test void employeeNumberUsesSharedTextCheckAndRejectsReservedNumber() {
        assertNotNull(error(EmployeeImportRows.check(row("19\u000701", "가", "2019-03-04", "1990-01-01", "D10", "", ""), TODAY), "employeeNo"));
        assertNotNull(error(EmployeeImportRows.check(row("19\ud80001", "가", "2019-03-04", "1990-01-01", "D10", "", ""), TODAY), "employeeNo"));
        assertNotNull(error(EmployeeImportRows.check(row("1".repeat(41), "가", "2019-03-04", "1990-01-01", "D10", "", ""), TODAY), "employeeNo"));
        assertEquals("예약 사번 99999는 사용할 수 없습니다.",
                error(EmployeeImportRows.check(row("99999", "가", "2019-03-04", "1990-01-01", "D10", "", ""), TODAY), "employeeNo"));
    }

    @Test void mixedDateSeparatorsAreRejected() {
        assertEquals("YYYY-MM-DD 형식으로 입력해 주세요.",
                error(EmployeeImportRows.check(row("", "가", "2019-03.04", "1990-01-01", "D10", "", ""), TODAY), "joinedOn"));
    }

    @Test void rowCountIsOneToFiveHundred() {
        assertThrows(OrgException.class, () -> EmployeeImportRows.checkCount(0));
        assertThrows(OrgException.class, () -> EmployeeImportRows.checkCount(501));
        assertDoesNotThrow(() -> EmployeeImportRows.checkCount(500));
    }
}
