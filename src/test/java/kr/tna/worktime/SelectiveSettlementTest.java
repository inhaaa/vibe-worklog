package kr.tna.worktime;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SelectiveSettlementTest {
    private static final long HOUR = 3_600_000L;
    private static final YearMonth MONTH = YearMonth.of(2026, 10);
    private static final LocalDate AFTER = LocalDate.of(2026, 12, 1);

    @Test void monthlyAndBoundaryWeekUseOnlyTheirAssignedDatesAndEachDaysStandard() {
        var days = List.of(day("2026-09-30", "CALENDAR_MONTH", 360, 1, 0L),
                day("2026-10-01", "CALENDAR_MONTH", 420, 1, 0L),
                day("2026-10-02", "CALENDAR_MONTH", 450, .5, 4 * HOUR),
                day("2026-10-30", "ISO_WEEK", 420, 0, 8 * HOUR),
                day("2026-11-01", "ISO_WEEK", 0, 0, 2 * HOUR),
                day("2026-11-02", "ISO_WEEK", 420, 0, HOUR));
        var periods = SelectiveSettlement.periods(MONTH, AFTER, days);
        assertEquals(2, periods.size());
        var month = periods.getFirst();
        assertEquals(MONTH.atDay(1), month.fromDate());
        assertEquals(MONTH.atEndOfMonth(), month.toDate());
        assertEquals(2, month.appliedDays());
        assertEquals(870, month.targetMinutes());
        assertEquals(4 * HOUR, month.confirmedWorkMillis());
        assertEquals(645 * 60_000L, month.paidLeaveMillis());
        assertEquals(15 * 60_000L, month.differenceMillis());
        var week = periods.getLast();
        assertEquals(LocalDate.parse("2026-10-26"), week.fromDate());
        assertEquals(LocalDate.parse("2026-11-01"), week.toDate());
        assertEquals(420, week.targetMinutes());
        assertEquals(10 * HOUR, week.confirmedWorkMillis());
        assertEquals(3 * HOUR, week.differenceMillis());
    }

    @Test void incompleteFactsAndPendingCorrectionsNeverConfirmAZeroOrUseAnOldTotal() {
        var days = List.of(day("2026-10-01", "CALENDAR_MONTH", 420, .5, null),
                new SelectiveSettlement.Day(LocalDate.parse("2026-10-02"), "CALENDAR_MONTH", 420, 0,
                        9 * HOUR, true, Set.of()),
                new SelectiveSettlement.Day(LocalDate.parse("2026-10-03"), "CALENDAR_MONTH", null, 0,
                        2 * HOUR, false, Set.of("SELECTIVE_WINDOW_REVIEW")),
                day("2026-10-04", "CALENDAR_MONTH", 420, 0, 8 * HOUR));
        var result = SelectiveSettlement.periods(MONTH, AFTER, days).getFirst();
        assertEquals(3, result.unconfirmedDays());
        assertEquals(10 * HOUR, result.confirmedWorkMillis());
        assertNull(result.targetMinutes());
        assertNull(result.paidLeaveMillis());
        assertNull(result.remainingMillis());
        assertNull(result.differenceMillis());
        assertEquals(List.of("CALENDAR_UNCONFIRMED", "PENDING_CORRECTION", "SELECTIVE_WINDOW_REVIEW"), result.warnings());
    }

    @Test void showsActionableReasonsForUnconfirmedWorkWithoutRepeatingThem() {
        Set<String> reasons = Set.of("BREAK_UNCONFIRMED", "SELECTIVE_SETTLEMENT_APPLIES", "LEGAL_OVERTIME_UNDETERMINED");
        var days = List.of(new SelectiveSettlement.Day(MONTH.atDay(1), "CALENDAR_MONTH", 420, 0, null, false, reasons),
                new SelectiveSettlement.Day(MONTH.atDay(2), "CALENDAR_MONTH", 420, 0, null, false, reasons));
        var period = SelectiveSettlement.periods(MONTH, AFTER, days).getFirst();
        assertEquals(List.of("BREAK_UNCONFIRMED"), period.warnings());
        assertEquals(2, period.unconfirmedDays());
    }

    @Test void halfDayLeaveUsesMillisecondsAndTodayDoesNotFinalizeAnOpenPeriod() {
        var days = List.of(day("2026-10-01", "CALENDAR_MONTH", 421, .5, null));
        var period = SelectiveSettlement.periods(MONTH, AFTER, days).getFirst();
        assertEquals(421 * 30_000L, period.paidLeaveMillis());
        assertEquals(421 * 30_000L, period.remainingMillis());
        assertEquals(1, period.unconfirmedDays());
        assertNull(period.differenceMillis());
        var complete = List.of(day("2026-10-01", "CALENDAR_MONTH", 421, 1, 0L));
        assertNull(SelectiveSettlement.periods(MONTH, MONTH.atEndOfMonth(), complete).getFirst().differenceMillis());
        assertEquals(0L, SelectiveSettlement.periods(MONTH, AFTER, complete).getFirst().differenceMillis());
    }

    @Test void anOpenPeriodShowsRemainingWorkFromConfirmedHoursAndApprovedHalfDayLeave() {
        var days = List.of(day("2026-10-01", "CALENDAR_MONTH", 420, .5, 4 * HOUR),
                day("2026-10-02", "CALENDAR_MONTH", 420, 0, null));
        var period = SelectiveSettlement.periods(MONTH, MONTH.atDay(1), days).getFirst();
        assertEquals(390 * 60_000L, period.remainingMillis());
        assertEquals(1, period.unconfirmedDays());
        assertNull(period.differenceMillis());
        var excess = List.of(day("2026-10-01", "CALENDAR_MONTH", 420, 0, 8 * HOUR));
        assertEquals(0L, SelectiveSettlement.periods(MONTH, MONTH.atDay(1), excess).getFirst().remainingMillis());
    }

    private static SelectiveSettlement.Day day(String date, String period, Integer target, double leave, Long actual) {
        return new SelectiveSettlement.Day(LocalDate.parse(date), period, target, leave, actual, false, Set.of());
    }
}
