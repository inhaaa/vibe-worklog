package kr.tna.worktime;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.tna.worktime.WorktimeCalculator.*;
import org.junit.jupiter.api.Test;

class SelectiveWorktimeTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 30);
    private static final long HOUR = 3_600_000L;
    private final WorktimeCalculator calculator = new WorktimeCalculator();

    @Test void flexibleStartAndNineHoursUseThePeriodInsteadOfFixedDailyClassifications() {
        var result = day("08:00", "18:00", DayKind.WORKDAY, 0, false, confirmed("12:00", "13:00"));
        assertEquals(9 * HOUR, result.actualWorkMillis());
        assertEquals(HOUR, result.confirmedBreakMillis());
        assertEquals(7 * HOUR, result.scheduledMillis());
        assertEquals(0L, result.outsideScheduleMillis());
        assertNull(result.over8hMillis());
        assertEquals(Status.CALCULATED, result.status());
    }

    @Test void timeOutsideTheWindowIsPreservedAndCoreAbsenceIsFlaggedWithoutClippingWork() {
        var result = day("10:30", "21:00", DayKind.WORKDAY, 0, false, confirmed("12:00", "13:00"));
        assertEquals(9 * HOUR + HOUR / 2, result.actualWorkMillis());
        assertEquals(HOUR, result.outsideScheduleMillis());
        assertTrue(result.reasons().containsAll(List.of("SELECTIVE_CORE_REVIEW", "SELECTIVE_WINDOW_REVIEW")));
        assertFalse(result.reasons().contains("EXTRA_WORK_REVIEW"));
    }

    @Test void theAgreedLunchInsideCoreDoesNotExcuseOtherMissingCoreHours() {
        var missing = day("08:00", "18:00", DayKind.WORKDAY, 0, false, confirmed("12:00", "13:30"));
        assertTrue(missing.reasons().contains("SELECTIVE_CORE_REVIEW"));
        var half = day("13:30", "18:00", DayKind.WORKDAY, 0.5, false, confirmed("15:00", "15:30"));
        assertFalse(half.reasons().contains("SELECTIVE_CORE_REVIEW"));
        assertEquals(4 * HOUR, half.actualWorkMillis());
    }

    @Test void holidayWorkHasZeroTargetAndNoCoreAbsenceAndUnconfirmedInputsStayUnknown() {
        var holiday = day("15:00", "18:00", DayKind.HOLIDAY, 0, false, new Confirmation(1, 1, true, List.of()));
        assertEquals(3 * HOUR, holiday.holidayWorkMillis());
        assertEquals(0L, holiday.scheduledMillis());
        assertFalse(holiday.reasons().contains("SELECTIVE_CORE_REVIEW"));
        assertNull(day("08:00", "18:00", DayKind.WORKDAY, 0, true, confirmed("12:00", "13:00")).actualWorkMillis());
        assertNull(day("08:00", "18:00", DayKind.WORKDAY, 0, false, null).actualWorkMillis());
    }

    @Test void aConfirmedSelectiveWeekShowsTheSettlementBasisWithoutAFalseMissingWeeklyReference() {
        var day = day("08:00", "18:00", DayKind.WORKDAY, 0, false, confirmed("12:00", "13:00"));
        var monday = WorktimeCalculator.weekStart(DATE);
        var days = monday.datesUntil(monday.plusDays(7)).map(date -> new DayResult(date, day.status(), day.scheduledMillis(),
                day.presenceMillis(), day.actualWorkMillis(), day.confirmedBreakMillis(), day.outsideScheduleMillis(),
                day.over8hMillis(), day.nightWorkMillis(), day.holidayWorkMillis(), day.legalOvertimeMillis(),
                day.leaveDays(), day.paidLeaveMillis(), day.inputRevision(), day.reviewRevision(), day.reasons())).toList();
        var week = calculator.week(monday, days, false);
        assertEquals(63 * HOUR, week.confirmedWorkMillis());
        assertNull(week.over40hMillis());
        assertNull(week.over8hMillis());
        assertEquals(Status.CALCULATED, week.status());
        assertEquals(java.util.Set.of("SELECTIVE_SETTLEMENT_APPLIES"), week.reasons());
    }

    private DayResult day(String from, String through, DayKind kind, double leave, boolean pending, Confirmation confirmation) {
        var schedule = new Schedule(true, at("07:00"), at("20:00"), List.of(interval("12:00", "13:00")),
                true, 420, interval("10:00", "15:00"));
        var events = List.of(new ClockEvent(UUID.randomUUID(), EventKind.IN, at(from)),
                new ClockEvent(UUID.randomUUID(), EventKind.OUT, at(through)));
        return calculator.day(new DayInput(DATE, events, List.of(schedule), kind, true, 1, 1, confirmation, pending, leave));
    }

    private static Confirmation confirmed(String from, String through) {
        return new Confirmation(1, 1, true, List.of(interval(from, through)));
    }

    private static Interval interval(String from, String through) { return new Interval(at(from), at(through)); }
    private static Instant at(String time) { return DATE.atTime(LocalTime.parse(time)).atZone(ZoneId.of("Asia/Seoul")).toInstant(); }
}
