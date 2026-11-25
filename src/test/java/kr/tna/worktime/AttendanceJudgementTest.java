package kr.tna.worktime;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import java.util.UUID;
import kr.tna.worktime.WorktimeCalculator.*;
import org.junit.jupiter.api.Test;

class AttendanceJudgementTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 23); // 월요일
    private static final long MIN = 60_000L;
    private final WorktimeCalculator calculator = new WorktimeCalculator();

    private static Instant at(String hhmm) { return DATE.atTime(LocalTime.parse(hhmm)).atZone(SEOUL).toInstant(); }
    private static Schedule fixed(String start, String end, String... breaks) {
        var list = new java.util.ArrayList<Interval>();
        for (int i = 0; i < breaks.length; i += 2) list.add(new Interval(at(breaks[i]), at(breaks[i + 1])));
        return new Schedule(true, at(start), at(end), List.copyOf(list));
    }
    private static List<ClockEvent> events(String in, String out) {
        var list = new java.util.ArrayList<ClockEvent>();
        if (in != null) list.add(new ClockEvent(UUID.randomUUID(), EventKind.IN, at(in)));
        if (out != null) list.add(new ClockEvent(UUID.randomUUID(), EventKind.OUT, at(out)));
        return List.copyOf(list);
    }
    private static Confirmation breaks(String... b) {
        var list = new java.util.ArrayList<Interval>();
        for (int i = 0; i < b.length; i += 2) list.add(new Interval(at(b[i]), at(b[i + 1])));
        return new Confirmation(0, 0, true, List.copyOf(list));
    }
    private DayResult run(List<ClockEvent> events, Schedule schedule, Confirmation c, double leave, String part, boolean ended, boolean pending) {
        return calculator.day(new DayInput(DATE, events, List.of(schedule), DayKind.WORKDAY, true, 0, 0, c, pending, leave, leave),
                new AttendanceContext(ended, part));
    }

    @Test void onTimeDayIsNormal() {
        var r = run(events("08:00", "17:00"), fixed("08:00", "17:00", "12:00", "13:00"), breaks("12:00", "13:00"), 0, null, true, false);
        assertEquals(0L, r.attendance().lateMillis());
        assertEquals(0L, r.attendance().earlyLeaveMillis());
        assertNull(r.attendance().absence());
        assertEquals("정상", AttendanceLabel.of(r.attendance()));
    }
    @Test void oneSecondAfterStartIsLateAndRoundsUpToOneMinute() {
        var r = run(List.of(new ClockEvent(UUID.randomUUID(), EventKind.IN, at("08:00").plusSeconds(1)),
                new ClockEvent(UUID.randomUUID(), EventKind.OUT, at("17:00"))),
                fixed("08:00", "17:00", "12:00", "13:00"), breaks("12:00", "13:00"), 0, null, true, false);
        assertEquals(1000L, r.attendance().lateMillis());
        assertTrue(r.reasons().contains("LATE"));
        assertEquals(WorktimeCalculator.Status.CALCULATED, r.status());
        assertEquals("지각 1분", AttendanceLabel.of(r.attendance()));
    }
    @Test void lateAndEarlyLeaveAreBothShown() {
        var r = run(events("08:07", "16:30"), fixed("08:00", "17:00", "12:00", "13:00"), breaks("12:00", "13:00"), 0, null, true, false);
        assertEquals(7 * MIN, r.attendance().lateMillis());
        assertEquals(30 * MIN, r.attendance().earlyLeaveMillis());
        assertEquals("지각 7분 · 조퇴 30분", AttendanceLabel.of(r.attendance()));
    }
    @Test void morningHalfDayUsesFirstBreakEndAsStart() {
        var r = run(events("13:00", "17:00"), fixed("08:00", "17:00", "12:00", "13:00"), breaks(), 0.5, "AM", true, false);
        assertEquals(0L, r.attendance().lateMillis());
        var late = run(events("13:10", "17:00"), fixed("08:00", "17:00", "12:00", "13:00"), breaks(), 0.5, "AM", true, false);
        assertEquals(10 * MIN, late.attendance().lateMillis());
    }
    @Test void afternoonHalfDayUsesFirstBreakStartAsEnd() {
        var r = run(events("08:00", "12:00"), fixed("08:00", "17:00", "12:00", "13:00"), breaks(), 0.5, "PM", true, false);
        assertEquals(0L, r.attendance().earlyLeaveMillis());
    }
    @Test void codeWithoutBreakUsesMidpointForHalfDay() {
        var r = run(events("08:00", "12:00"), fixed("08:00", "16:00"), breaks(), 0.5, "PM", true, false);
        assertEquals(0L, r.attendance().earlyLeaveMillis());
        var am = run(events("12:30", "16:00"), fixed("08:00", "16:00"), breaks(), 0.5, "AM", true, false);
        assertEquals(30 * MIN, am.attendance().lateMillis());
    }
    @Test void endedWorkdayWithoutRecordsIsAbsentWithZeroWork() {
        var r = run(List.of(), fixed("08:00", "17:00", "12:00", "13:00"), null, 0, null, true, false);
        assertEquals("FULL", r.attendance().absence());
        assertEquals(0L, r.actualWorkMillis());
        assertTrue(r.reasons().contains("ABSENT"));
        assertFalse(r.reasons().contains("MISSING_CLOCK_IN"));
        assertEquals(WorktimeCalculator.Status.CALCULATED, r.status());
        assertEquals("결근", AttendanceLabel.of(r.attendance()));
    }
    @Test void halfDayLeaveWithoutRecordsIsHalfAbsent() {
        var r = run(List.of(), fixed("08:00", "17:00", "12:00", "13:00"), null, 0.5, "AM", true, false);
        assertEquals("HALF", r.attendance().absence());
        assertEquals("결근(반일)", AttendanceLabel.of(r.attendance()));
    }
    @Test void todayWithoutRecordsIsNotAbsentYet() {
        var r = run(List.of(), fixed("08:00", "17:00", "12:00", "13:00"), null, 0, null, false, false);
        assertNull(r.attendance().absence());
        assertTrue(r.reasons().contains("MISSING_CLOCK_IN"));
    }
    @Test void pendingCorrectionKeepsReviewInsteadOfAbsence() {
        var r = run(List.of(), fixed("08:00", "17:00", "12:00", "13:00"), null, 0, null, true, true);
        assertNull(r.attendance().absence());
        assertTrue(r.reasons().contains("PENDING_CORRECTION"));
    }
    @Test void notJudgedOnHolidayFullLeaveAndSelective() {
        var holiday = calculator.day(new DayInput(DATE, List.of(), List.of(fixed("08:00", "17:00")), DayKind.HOLIDAY, true, 0, 0, null, false, 0, 0),
                new AttendanceContext(true, null));
        assertEquals(Attendance.NONE, holiday.attendance());
        var leave = run(List.of(), fixed("08:00", "17:00"), null, 1, "FULL", true, false);
        assertEquals(Attendance.NONE, leave.attendance());
        var selective = new Schedule(true, at("07:00"), at("22:00"), List.of(), true, 480, null);
        var sel = calculator.day(new DayInput(DATE, events("10:00", "19:00"), List.of(selective), DayKind.WORKDAY, true, 0, 0, breaks(), false, 0, 0),
                new AttendanceContext(true, null));
        assertEquals(Attendance.NONE, sel.attendance());
        assertNull(AttendanceLabel.of(Attendance.NONE));
    }
    @Test void onlyClockInJudgesLatenessAndKeepsReview() {
        var r = run(events("08:05", null), fixed("08:00", "17:00"), null, 0, null, true, false);
        assertEquals(5 * MIN, r.attendance().lateMillis());
        assertNull(r.attendance().earlyLeaveMillis());
        assertTrue(r.reasons().contains("MISSING_CLOCK_OUT"));
        assertEquals(WorktimeCalculator.Status.REVIEW_REQUIRED, r.status());
    }
    @Test void legacyDayOverloadStillWorks() {
        var r = calculator.day(new DayInput(DATE, events("08:00", "17:00"), List.of(fixed("08:00", "17:00", "12:00", "13:00")),
                DayKind.WORKDAY, true, 0, 0, breaks("12:00", "13:00"), false, 0, 0));
        assertEquals(8 * 3_600_000L, r.actualWorkMillis());
        assertNotNull(r.attendance());
    }
}
