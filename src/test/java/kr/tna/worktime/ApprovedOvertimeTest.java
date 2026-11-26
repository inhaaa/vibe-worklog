package kr.tna.worktime;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.tna.worktime.WorktimeCalculator.*;
import org.junit.jupiter.api.Test;

class ApprovedOvertimeTest {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final LocalDate DAY = LocalDate.of(2026, 11, 23); // 월요일
    static final long MIN = 60_000L;
    final WorktimeCalculator calculator = new WorktimeCalculator();

    static java.time.Instant at(int h, int m) { return DAY.atTime(h, m).atZone(SEOUL).toInstant(); }
    static Interval iv(int h1, int m1, int h2, int m2) { return new Interval(at(h1, m1), at(h2, m2)); }

    DayResult day(int outH, int outM, List<Interval> approved) {
        var events = List.of(new ClockEvent(UUID.randomUUID(), EventKind.IN, at(9, 0)),
                new ClockEvent(UUID.randomUUID(), EventKind.OUT, at(outH, outM)));
        var schedule = new Schedule(true, at(9, 0), at(18, 0), List.of(iv(12, 0, 13, 0)));
        var confirmation = new Confirmation(1, 0, true, List.of(iv(12, 0, 13, 0)));
        return calculator.day(new DayInput(DAY, events, List.of(schedule), DayKind.WORKDAY, true, 1, 0, confirmation,
                false, 0, 0, approved), AttendanceContext.NONE);
    }

    @Test void recognizesOnlyApprovedTimeActuallyWorked() {
        // 18:00~23:00 승인(연장 휴게 20:00~20:15 제외) · 22:40 퇴근 → 4시간 25분
        var approved = OvertimeIntervals.net(DAY, List.of(new OvertimeIntervals.Approved(LocalTime.of(18, 0), LocalTime.of(23, 0),
                List.of(new OvertimeIntervals.Pause(LocalTime.of(20, 0), LocalTime.of(20, 15))))));
        assertEquals(265 * MIN, day(22, 40, approved).approvedOvertimeMillis());
    }

    @Test void noApprovalMeansZeroAndUnapprovedWorkStaysOutsideSchedule() {
        var result = day(21, 0, List.of());
        assertEquals(0L, result.approvedOvertimeMillis());
        assertEquals(180 * MIN, result.outsideScheduleMillis());
    }

    @Test void leavingBeforeTheApprovedStartRecognizesNothing() {
        var approved = OvertimeIntervals.net(DAY, List.of(new OvertimeIntervals.Approved(LocalTime.of(19, 0), LocalTime.of(21, 0), List.of())));
        assertEquals(0L, day(18, 30, approved).approvedOvertimeMillis());
    }

    @Test void overlappingApprovalsAreMergedOnce() {
        var approved = OvertimeIntervals.net(DAY, List.of(
                new OvertimeIntervals.Approved(LocalTime.of(18, 0), LocalTime.of(20, 0), List.of()),
                new OvertimeIntervals.Approved(LocalTime.of(19, 0), LocalTime.of(21, 0), List.of())));
        assertEquals(List.of(iv(18, 0, 21, 0)), approved);
        assertEquals(180 * MIN, day(21, 0, approved).approvedOvertimeMillis());
    }

    @Test void unconfirmedWorkLeavesRecognitionUnknown() {
        var events = List.of(new ClockEvent(UUID.randomUUID(), EventKind.IN, at(9, 0)), new ClockEvent(UUID.randomUUID(), EventKind.OUT, at(21, 0)));
        var schedule = new Schedule(true, at(9, 0), at(18, 0), List.of(iv(12, 0, 13, 0)));
        var result = calculator.day(new DayInput(DAY, events, List.of(schedule), DayKind.WORKDAY, true, 1, 0, null, false, 0, 0,
                List.of(iv(18, 0, 21, 0))), AttendanceContext.NONE);
        assertNull(result.actualWorkMillis());
        assertNull(result.approvedOvertimeMillis());
    }
}
