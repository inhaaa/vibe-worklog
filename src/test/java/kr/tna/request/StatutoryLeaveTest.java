package kr.tna.request;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.request.StatutoryLeave.Evidence;
import kr.tna.worktime.WorktimeService.LeaveAttendanceDay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatutoryLeaveTest {
    private static LocalDate d(String value) { return LocalDate.parse(value); }
    private static void days(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " != " + actual); }

    @ParameterizedTest @CsvSource({"1,15", "2,15", "3,16", "4,16", "5,17", "21,25", "30,25"})
    void serviceYearDays(int year, String expected) { days(expected, StatutoryLeave.serviceDays(year)); }

    @Test void serviceYearMustBePositive() { assertThrows(IllegalArgumentException.class, () -> StatutoryLeave.serviceDays(0)); }

    @ParameterizedTest @CsvSource({
            "2025-07-01,2026-01-01,8",
            "2025-12-31,2026-01-01,0.5",
            "2025-03-15,2026-01-01,12",
            "2025-01-01,2026-01-01,15",
            "2024-01-01,2025-01-01,15",
            "2025-10-01,2026-04-01,7.5"})
    void firstFiscalYearIsProratedUpToHalfDays(String joined, String fiscalStart, String expected) {
        days(expected, StatutoryLeave.prorated(d(joined), d(fiscalStart)));
    }

    @Test void fiscalServiceYearCountsFromTheJoiningFiscalYearStart() {
        assertEquals(LocalDate.of(2024, 1, 1), StatutoryLeave.fiscalStart(d("2024-07-01"), 1));
        assertEquals(LocalDate.of(2025, 4, 1), StatutoryLeave.fiscalStart(d("2026-03-31"), 4));
        assertEquals(1, StatutoryLeave.fiscalServiceYear(d("2024-07-01"), d("2025-01-01"), 1));
        assertEquals(2, StatutoryLeave.fiscalServiceYear(d("2024-07-01"), d("2026-01-01"), 1));
        assertEquals(3, StatutoryLeave.fiscalServiceYear(d("2024-07-01"), d("2027-01-01"), 1));
        assertEquals(5, StatutoryLeave.fiscalServiceYear(d("2024-07-01"), d("2029-01-01"), 1));
    }

    @Test void february29thAnniversariesMoveToMarch1stInCommonYears() {
        assertEquals(d("2025-03-01"), StatutoryLeave.anniversary(d("2024-02-29"), 1));
        assertEquals(d("2028-02-29"), StatutoryLeave.anniversary(d("2024-02-29"), 4));
        assertEquals(d("2026-01-31"), StatutoryLeave.anniversary(d("2025-01-31"), 1));
        assertEquals(d("2025-01-31"), StatutoryLeave.anniversary(d("2025-01-31"), 0));
    }

    @Test void annualAttendanceRateBoundaries() {
        var exact = StatutoryLeave.annual(new Evidence(10, new BigDecimal("2.0"), 0));
        assertEquals(StatutoryLeave.GRANT, exact.decision());
        days("80", exact.attendanceRate());
        assertNull(exact.reason());
        var below = StatutoryLeave.annual(new Evidence(1000, new BigDecimal("201.0"), 0));
        assertEquals(StatutoryLeave.NEEDS_REVIEW, below.decision());
        assertEquals("출근율 79.9%", below.reason());
        assertEquals("출근율 75.0%", StatutoryLeave.annual(new Evidence(10, new BigDecimal("2.5"), 0)).reason());
        assertEquals(StatutoryLeave.GRANT, StatutoryLeave.annual(new Evidence(5, new BigDecimal("0.5"), 0)).decision());
        var unresolved = StatutoryLeave.annual(new Evidence(200, BigDecimal.ZERO, 3));
        assertEquals("근태 확정 전 3일", unresolved.reason());
        assertNull(unresolved.attendanceRate());
        assertEquals("소정근로일 0일", StatutoryLeave.annual(new Evidence(0, BigDecimal.ZERO, 0)).reason());
    }

    @Test void monthlyDecisionPrefersAbsenceThenUnresolvedDays() {
        assertEquals(StatutoryLeave.FULL, StatutoryLeave.monthly(new Evidence(22, BigDecimal.ZERO, 0)));
        assertEquals(StatutoryLeave.NOT_FULL, StatutoryLeave.monthly(new Evidence(22, new BigDecimal("0.5"), 2)));
        assertEquals(StatutoryLeave.NEEDS_REVIEW, StatutoryLeave.monthly(new Evidence(22, BigDecimal.ZERO, 1)));
        assertEquals(StatutoryLeave.FULL, StatutoryLeave.monthly(new Evidence(0, BigDecimal.ZERO, 0)));
    }

    @Test void evidenceAddsFullAndHalfAbsences() {
        var day = d("2026-01-05");
        var evidence = Evidence.of(List.of(
                new LeaveAttendanceDay(day, true, false, false, false, 1, "s", UUID.randomUUID(), 1),
                new LeaveAttendanceDay(day.plusDays(1), true, false, true, false, 0.5, "s", UUID.randomUUID(), 1),
                new LeaveAttendanceDay(day.plusDays(2), true, false, false, true, 0, "s", null, 0),
                new LeaveAttendanceDay(day.plusDays(3), false, false, false, false, 0, "s", null, 0)));
        assertEquals(3, evidence.scheduledDays());
        days("1.5", evidence.absenceDays());
        assertEquals(1, evidence.unresolvedDays());
    }
}
