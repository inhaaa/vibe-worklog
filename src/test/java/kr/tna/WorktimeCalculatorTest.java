package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.worktime.WorktimeCalculator;
import kr.tna.worktime.WorktimeCalculator.*;
import org.junit.jupiter.api.Test;

class WorktimeCalculatorTest {
    private static final long HOUR = 3_600_000L;
    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);
    private final WorktimeCalculator calculator = new WorktimeCalculator();

    @Test void c01ConfirmedNormalDayKeepsPresenceAndWorkSeparate() {
        var result = calculator.day(normal());
        assertEquals(Status.CALCULATED, result.status());
        assertEquals(9*HOUR, result.presenceMillis());
        assertEquals(8*HOUR, result.actualWorkMillis());
        assertEquals(HOUR, result.confirmedBreakMillis());
        assertEquals(8*HOUR, result.scheduledMillis());
        assertNull(result.legalOvertimeMillis());
    }
    @Test void c02LateArrivalIsNotFilledToEightHours() {
        var result = calculator.day(withEvents(normal(), events("09:07", "18:00")));
        assertEquals(8*HOUR-7*60_000L, result.actualWorkMillis());
        assertEquals(0L, result.over8hMillis());
    }
    @Test void c03MissingClockOutHasNoInventedAttendance() {
        var result = calculator.day(withEvents(normal(), List.of(event(EventKind.IN,"09:00"))));
        assertEquals(Status.REVIEW_REQUIRED, result.status());
        assertNull(result.presenceMillis()); assertNull(result.actualWorkMillis());
        assertTrue(result.reasons().contains("MISSING_CLOCK_OUT"));
    }
    @Test void c04PlannedBreakIsNotActualEvidence() {
        var result = calculator.day(withConfirmation(normal(), null));
        assertEquals(9*HOUR, result.presenceMillis()); assertNull(result.actualWorkMillis());
        assertTrue(result.reasons().contains("BREAK_UNCONFIRMED"));
    }
    @Test void c05ShortenedActualBreakPreservesAdditionalWork() {
        var result = calculator.day(withConfirmation(normal(), confirmation(List.of(interval("12:00","12:30")))));
        assertEquals(8*HOUR+30*60_000L, result.actualWorkMillis());
        assertEquals(30*60_000L, result.outsideScheduleMillis());
        assertEquals(30*60_000L, result.over8hMillis());
        assertTrue(result.reasons().contains("BREAK_INSUFFICIENT_REVIEW"));
    }
    @Test void c06ActualBreakUnionIsSubtractedOnlyOnce() {
        var result = calculator.day(withConfirmation(normal(), confirmation(List.of(
                interval("12:00","12:45"),interval("12:30","13:00")))));
        assertEquals(HOUR, result.confirmedBreakMillis()); assertEquals(8*HOUR, result.actualWorkMillis());
    }
    @Test void c07BreakOutsidePresenceIsRejectedWithoutClipping() {
        var result = calculator.day(withConfirmation(normal(), confirmation(List.of(interval("08:30","09:30")))));
        assertNull(result.actualWorkMillis()); assertTrue(result.reasons().contains("INVALID_BREAK_INTERVAL"));
    }
    @Test void c08SameCommandReplayIsOneEventAndDifferentPayloadIsAConflict() {
        var input = normal(); var events = new ArrayList<>(input.events()); events.add(input.events().getFirst());
        assertEquals(8*HOUR, calculator.day(withEvents(input, events)).actualWorkMillis());
        events.add(new ClockEvent(events.getFirst().commandId(),EventKind.IN,at("09:05")));
        assertTrue(calculator.day(withEvents(input,events)).reasons().contains("DUPLICATE_CLOCK_EVENT"));
    }
    @Test void c09DifferentClockCommandsDoNotSilentlyPickTheFirst() {
        var events = new ArrayList<>(normal().events()); events.add(event(EventKind.IN,"09:05"));
        var result = calculator.day(withEvents(normal(),events));
        assertNull(result.actualWorkMillis()); assertTrue(result.reasons().contains("DUPLICATE_CLOCK_EVENT"));
    }
    @Test void c10OutsideScheduleWorkIsPreservedWithoutLegalOvertimeCertification() {
        var result = calculator.day(withEvents(normal(),events("09:00","20:00")));
        assertEquals(10*HOUR,result.actualWorkMillis()); assertEquals(2*HOUR,result.over8hMillis());
        assertEquals(2*HOUR,result.outsideScheduleMillis()); assertNull(result.legalOvertimeMillis());
        assertEquals(Status.REVIEW_REQUIRED,result.status()); assertTrue(result.reasons().contains("EXTRA_WORK_REVIEW"));
    }
    @Test void c11CrossMidnightRemainsUnsupportedAndUnassigned() {
        var result = calculator.day(withEvents(normal(),List.of(event(EventKind.IN,"22:00"),
                new ClockEvent(UUID.randomUUID(),EventKind.OUT,at("06:00").plusSeconds(86400)))));
        assertNull(result.actualWorkMillis()); assertNull(result.nightWorkMillis());
        assertTrue(result.reasons().contains("CROSS_MIDNIGHT_UNSUPPORTED"));
    }
    @Test void c12NightIntersectionUsesTheExact2200Boundary() {
        var input = withEvents(normal(),events("21:00","23:00"));
        var result = calculator.day(withConfirmation(input,confirmation(List.of())));
        assertEquals(2*HOUR,result.actualWorkMillis()); assertEquals(HOUR,result.nightWorkMillis());
    }
    @Test void c13HolidayWorkIsNotDiscarded() {
        var input = withKind(withEvents(normal(),events("09:00","12:00")),DayKind.HOLIDAY);
        var result = calculator.day(withConfirmation(input,confirmation(List.of())));
        assertEquals(3*HOUR,result.actualWorkMillis()); assertEquals(3*HOUR,result.holidayWorkMillis());
        assertTrue(result.reasons().contains("HOLIDAY_WORK_REVIEW"));
    }
    @Test void c14ConfirmedNonWorkdayWithoutEventsHasExplainedZero() {
        var result = calculator.day(nonWorkday(DayKind.NON_WORKDAY,0,false));
        assertEquals(Status.NO_SCHEDULED_WORK,result.status()); assertEquals(0L,result.actualWorkMillis());
    }
    @Test void c15WeekSpansAdjacentMonthsWhileMonthIncludesOnlyItsDates() {
        LocalDate monday=LocalDate.of(2026,10,26); var days=new ArrayList<DayResult>();
        for(int i=0;i<7;i++) days.add(result(monday.plusDays(i),i<6 ? 7*HOUR : 0L,0));
        var week=calculator.week(monday,days,true);
        assertEquals(42*HOUR,week.confirmedWorkMillis()); assertEquals(2*HOUR,week.over40hMillis());
        assertEquals(42*HOUR,calculator.month(YearMonth.of(2026,10),days).confirmedWorkMillis());
        var boundary=new ArrayList<DayResult>();monday=LocalDate.of(2026,11,30);
        for(int i=0;i<7;i++) boundary.add(result(monday.plusDays(i),i<6 ? 7*HOUR : 0L,0));
        assertEquals(42*HOUR,calculator.week(monday,boundary,true).confirmedWorkMillis());
        assertEquals(7*HOUR,calculator.month(YearMonth.of(2026,11),boundary).confirmedWorkMillis());
        assertEquals(35*HOUR,calculator.month(YearMonth.of(2026,12),boundary).confirmedWorkMillis());
    }
    @Test void c16DailyOverEightDoesNotReplaceWeeklyOverForty() {
        var days=new ArrayList<DayResult>();
        for(int i=0;i<7;i++)days.add(result(DATE.plusDays(i),i<3?10*HOUR:0L,i<3?2*HOUR:0));
        var result=calculator.week(DATE,days,true);
        assertEquals(0L,result.over40hMillis()); assertEquals(6*HOUR,result.over8hMillis());
    }
    @Test void c17MillisecondsSurviveEveryAggregation() {
        var a=result(DATE,59_800L,0);var b=result(DATE.plusDays(1),300L,0);
        var result=calculator.month(YearMonth.from(DATE),List.of(a,b));
        assertEquals(60_100L,result.confirmedWorkMillis());
    }
    @Test void c26ApprovedLeaveAndAttendanceConflictPreservesLeaveAndRawPresence() {
        var input=normal(); input=copy(input,input.events(),input.confirmation(),input.dayKind(),false,1);
        var result=calculator.day(input);
        assertEquals(1,result.leaveDays()); assertEquals(9*HOUR,result.presenceMillis());
        assertTrue(result.reasons().contains("LEAVE_WORK_CONFLICT")); assertEquals(Status.REVIEW_REQUIRED,result.status());
    }
    @Test void c29IncompleteWeekShowsPartialSumAndDoesNotReportACompleteTotal() {
        var days=new ArrayList<DayResult>();
        for(int i=0;i<7;i++)days.add(result(DATE.plusDays(i),i==0?null:i<5?8*HOUR:0L,0));
        var result=calculator.week(DATE,days,true);
        assertEquals(32*HOUR,result.confirmedWorkMillis());assertEquals(1,result.unconfirmedDays());
        assertNull(result.over40hMillis());assertEquals(Status.REVIEW_REQUIRED,result.status());
    }
    @Test void c30MissingAndConflictingSchedulesAreNeverSelectedArbitrarily() {
        var input=normal();
        for(var schedules:List.of(List.<Schedule>of(),List.of(input.schedules().getFirst(),input.schedules().getFirst()))) {
            var changed=new DayInput(input.workDate(),input.events(),schedules,input.dayKind(),true,7,4,input.confirmation(),false,0);
            var result=calculator.day(changed);
            assertNull(result.scheduledMillis());assertEquals(Status.REVIEW_REQUIRED,result.status());
            assertTrue(result.reasons().contains(schedules.isEmpty()?"SCHEDULE_MISSING":"SCHEDULE_CONFLICT"));
        }
    }
    @Test void c31ApprovedFullDayLeaveWithoutEventsIsZeroWorkAndOneDayLeave() {
        var input=normal();input=copy(input,List.of(),null,input.dayKind(),false,1);
        var result=calculator.day(input);
        assertEquals(8*HOUR,result.scheduledMillis());assertEquals(0L,result.actualWorkMillis());assertEquals(1,result.leaveDays());
    }
    @Test void c32PendingCorrectionInvalidatesZeroForLeaveAndNonWorkdays() {
        for(var input:List.of(nonWorkday(DayKind.NON_WORKDAY,0,true),nonWorkday(DayKind.WORKDAY,1,true))) {
            var result=calculator.day(input);assertNull(result.actualWorkMillis());
            assertTrue(result.reasons().contains("PENDING_CORRECTION"));assertEquals(Status.REVIEW_REQUIRED,result.status());
        }
    }
    @Test void c33StaleFactConfirmationIsNeverReusedButReviewOnlyChangesDoNotChangeFacts() {
        var input=normal();var stale=new Confirmation(6,3,true,List.of(interval("12:00","13:00")));
        var result=calculator.day(withConfirmation(input,stale));
        assertNull(result.actualWorkMillis());assertTrue(result.reasons().contains("CONFIRMATION_STALE"));
        var valid=new Confirmation(7,3,true,List.of(interval("12:00","13:00")));
        assertEquals(8*HOUR,calculator.day(withConfirmation(input,valid)).actualWorkMillis());
    }
    @Test void c34CurrentResultRequiresBothFactAndReviewRevisions() {
        var result=calculator.day(normal());assertTrue(WorktimeCalculator.current(result,7,4));
        assertFalse(WorktimeCalculator.current(result,7,5));assertFalse(WorktimeCalculator.current(result,8,4));
        assertEquals(7,result.inputRevision());assertEquals(4,result.reviewRevision());
    }
    @Test void morePreciseThanMillisecondsIsRejectedRatherThanTruncated() {
        var input=normal();var event=new ClockEvent(UUID.randomUUID(),EventKind.IN,at("09:00").plusNanos(1));
        assertThrows(IllegalArgumentException.class,()->calculator.day(withEvents(input,List.of(event,input.events().getLast()))));
    }
    @Test void unreviewedLegalScopeAndCalendarNeverBecomeAQualifiedNormalResult() {
        var input=normal();input=new DayInput(DATE,input.events(),input.schedules(),DayKind.UNCONFIRMED,false,7,4,input.confirmation(),false,0);
        var result=calculator.day(input);assertEquals(Status.REVIEW_REQUIRED,result.status());
        assertNull(result.over8hMillis());assertNull(result.holidayWorkMillis());assertNull(result.legalOvertimeMillis());
        assertTrue(result.reasons().contains("LEGAL_APPLICABILITY_UNCONFIRMED"));
        assertTrue(result.reasons().contains("CALENDAR_UNCONFIRMED"));
    }
    @Test void weekRequiresMondayAndSevenDistinctDatesForACompleteReferenceQuantity() {
        assertEquals(DATE,WorktimeCalculator.weekStart(DATE.plusDays(6)));
        var one=result(DATE,8*HOUR,0);
        assertEquals(6,calculator.week(DATE,List.of(one),true).unconfirmedDays());
        assertThrows(IllegalArgumentException.class,()->calculator.week(DATE.plusDays(1),List.of(),true));
        assertThrows(IllegalArgumentException.class,()->calculator.week(DATE,List.of(one,one),true));
    }
    @Test void pendingCountsOnlyPastDaysWhoseWorkIsNotShown() {
        LocalDate today = DATE.plusDays(1);
        assertTrue(calculator.pending(normal(), today, false, null), "기록이 있는데 계산 전이면 확인 전입니다.");
        assertFalse(calculator.pending(normal(), today, true, 8*HOUR), "현재 계산 결과가 있으면 확인 전이 아닙니다.");
        assertTrue(calculator.pending(normal(), today, false, 8*HOUR), "오래된 결과는 값이 보이지 않으므로 확인 전입니다.");
        assertFalse(calculator.pending(normal(), DATE.minusDays(1), false, null), "미래 날짜는 세지 않습니다.");
        assertTrue(calculator.pending(normal(), DATE, false, null), "오늘은 퇴근 기록이 있으면 셉니다.");
        assertFalse(calculator.pending(withEvents(normal(), List.of(event(EventKind.IN, "09:00"))), DATE, false, null), "오늘 퇴근 전은 세지 않습니다.");
    }
    @Test void pendingSkipsOnlyRestDaysTheCalculatorResolvesWithoutRecords() {
        LocalDate today = DATE.plusDays(1);
        assertFalse(calculator.pending(nonWorkday(DayKind.NON_WORKDAY, 0, false), today, false, null), "기록 없는 확정 비근무일");
        assertFalse(calculator.pending(nonWorkday(DayKind.HOLIDAY, 0, false), today, false, null), "기록 없는 확정 휴일");
        assertFalse(calculator.pending(nonWorkday(DayKind.WORKDAY, 1, false), today, false, null), "기록 없는 승인 휴가일");
        assertTrue(calculator.pending(withEvents(normal(), List.of()), today, false, null), "기록 없는 근무일");
        assertTrue(calculator.pending(nonWorkday(DayKind.UNCONFIRMED, 0, false), today, false, null), "캘린더 미확인");
        assertTrue(calculator.pending(nonWorkday(DayKind.HOLIDAY, 0, true), today, false, null), "대기 정정이 있는 휴일");
        var missingSchedule = new DayInput(DATE, List.of(), List.of(), DayKind.WORKDAY, true, 7, 4, null, false, 0);
        assertTrue(calculator.pending(missingSchedule, today, false, null), "일정 미배정");
    }

    private DayInput normal() {
        return new DayInput(DATE,events("09:00","18:00"),List.of(new Schedule(true,at("09:00"),at("18:00"),
                List.of(interval("12:00","13:00")))),DayKind.WORKDAY,true,7,4,
                confirmation(List.of(interval("12:00","13:00"))),false,0);
    }
    private DayInput nonWorkday(DayKind kind,int leave,boolean pending) {
        var input=normal();var schedule=kind==DayKind.WORKDAY?input.schedules():List.of(new Schedule(false,null,null,List.of()));
        return new DayInput(DATE,List.of(),schedule,kind,true,7,4,null,pending,leave);
    }
    private DayInput withEvents(DayInput input,List<ClockEvent> events) { return copy(input,events,input.confirmation(),input.dayKind(),input.pendingCorrection(),input.approvedLeaveDays()); }
    private DayInput withConfirmation(DayInput input,Confirmation confirmation) { return copy(input,input.events(),confirmation,input.dayKind(),input.pendingCorrection(),input.approvedLeaveDays()); }
    private DayInput withKind(DayInput input,DayKind kind) { return copy(input,input.events(),input.confirmation(),kind,input.pendingCorrection(),input.approvedLeaveDays()); }
    private DayInput copy(DayInput i,List<ClockEvent> events,Confirmation confirmation,DayKind kind,boolean pending,int leave) {
        return new DayInput(i.workDate(),events,i.schedules(),kind,i.generalRuleReviewed(),i.inputRevision(),i.reviewRevision(),confirmation,pending,leave);
    }
    private Confirmation confirmation(List<Interval> breaks) { return new Confirmation(7,4,true,breaks); }
    private List<ClockEvent> events(String in,String out) { return List.of(event(EventKind.IN,in),event(EventKind.OUT,out)); }
    private ClockEvent event(EventKind kind,String time) { return new ClockEvent(UUID.randomUUID(),kind,at(time)); }
    private Interval interval(String start,String end) { return new Interval(at(start),at(end)); }
    private Instant at(String time) { return DATE.atTime(java.time.LocalTime.parse(time)).atZone(ZoneId.of("Asia/Seoul")).toInstant(); }
    private DayResult result(LocalDate date,Long work,long overEight) {
        return new DayResult(date,work==null?Status.REVIEW_REQUIRED:Status.CALCULATED,null,work,work,0L,0L,
                work==null?null:overEight,0L,0L,null,0,1,1,work==null?Set.of("MISSING_CLOCK_OUT"):Set.of());
    }
}
