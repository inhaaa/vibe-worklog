package kr.tna.worktime;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Pure CALC-3 arithmetic. Persistence, authorization and approval effects belong to the calling service. */
public final class WorktimeCalculator {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long HOUR = 3_600_000L;
    private static final String SELECTIVE_SETTLEMENT = "SELECTIVE_SETTLEMENT_APPLIES";
    private static final Set<String> INFORMATIONAL = Set.of("LEGAL_OVERTIME_UNDETERMINED",
            "APPROVED_LEAVE_NO_ATTENDANCE", "NO_CLOCK_ON_CONFIRMED_NON_WORKDAY", SELECTIVE_SETTLEMENT,
            "ABSENT", "LATE", "EARLY_LEAVE");

    public enum Status { CALCULATED, REVIEW_REQUIRED, NO_SCHEDULED_WORK }
    public enum DayKind { WORKDAY, NON_WORKDAY, HOLIDAY, UNCONFIRMED }
    public enum EventKind { IN, OUT }
    public record Interval(Instant start, Instant end) { }
    public record ClockEvent(UUID commandId, EventKind kind, Instant occurredAt) { }
    public record Schedule(boolean workday, Instant start, Instant end, List<Interval> plannedBreaks,
                           boolean selective, Integer standardMinutes, Interval core) {
        public Schedule(boolean workday, Instant start, Instant end, List<Interval> plannedBreaks) {
            this(workday, start, end, plannedBreaks, false, null, null);
        }
    }
    public record Confirmation(long confirmedInputRevision, long reviewRevision, boolean presenceAsWork,
                               List<Interval> actualBreaks) { }
    public record DayInput(LocalDate workDate, List<ClockEvent> events, List<Schedule> schedules,
                           DayKind dayKind, boolean generalRuleReviewed, long inputRevision, long reviewRevision,
                           Confirmation confirmation, boolean pendingCorrection, double approvedLeaveDays, double paidLeaveDays,
                           List<Interval> approvedOvertime) {
        public DayInput {
            if (approvedOvertime == null) approvedOvertime = List.of();
        }
        public DayInput(LocalDate workDate, List<ClockEvent> events, List<Schedule> schedules, DayKind dayKind,
                boolean generalRuleReviewed, long inputRevision, long reviewRevision, Confirmation confirmation,
                boolean pendingCorrection, double approvedLeaveDays, double paidLeaveDays) {
            this(workDate, events, schedules, dayKind, generalRuleReviewed, inputRevision, reviewRevision,
                    confirmation, pendingCorrection, approvedLeaveDays, paidLeaveDays, List.of());
        }
        public DayInput(LocalDate workDate, List<ClockEvent> events, List<Schedule> schedules, DayKind dayKind,
                boolean generalRuleReviewed, long inputRevision, long reviewRevision, Confirmation confirmation,
                boolean pendingCorrection, double approvedLeaveDays) {
            this(workDate, events, schedules, dayKind, generalRuleReviewed, inputRevision, reviewRevision,
                    confirmation, pendingCorrection, approvedLeaveDays, approvedLeaveDays, List.of());
        }
    }
    public record AttendanceContext(boolean dayEnded, String leavePart) {
        public static final AttendanceContext NONE = new AttendanceContext(false, null);
    }
    /** 지각·조퇴는 판정하지 않으면 null, 판정했으면 0 이상. absence는 FULL/HALF/null. */
    public record Attendance(Long lateMillis, Long earlyLeaveMillis, String absence) {
        public static final Attendance NONE = new Attendance(null, null, null);
    }
    public record DayResult(LocalDate workDate, Status status, Long scheduledMillis, Long presenceMillis,
                            Long actualWorkMillis, Long confirmedBreakMillis, Long outsideScheduleMillis,
                            Long over8hMillis, Long nightWorkMillis, Long holidayWorkMillis, Long legalOvertimeMillis,
                            double leaveDays, Long paidLeaveMillis, long inputRevision, long reviewRevision, Set<String> reasons,
                            Attendance attendance, Long approvedOvertimeMillis) {
        public DayResult {
            if (attendance == null) attendance = Attendance.NONE;
        }
        public DayResult(LocalDate workDate, Status status, Long scheduledMillis, Long presenceMillis,
                         Long actualWorkMillis, Long confirmedBreakMillis, Long outsideScheduleMillis,
                         Long over8hMillis, Long nightWorkMillis, Long holidayWorkMillis, Long legalOvertimeMillis,
                         double leaveDays, Long paidLeaveMillis, long inputRevision, long reviewRevision, Set<String> reasons,
                         Attendance attendance) {
            this(workDate, status, scheduledMillis, presenceMillis, actualWorkMillis, confirmedBreakMillis, outsideScheduleMillis,
                    over8hMillis, nightWorkMillis, holidayWorkMillis, legalOvertimeMillis, leaveDays, paidLeaveMillis,
                    inputRevision, reviewRevision, reasons, attendance, null);
        }
        public DayResult(LocalDate workDate, Status status, Long scheduledMillis, Long presenceMillis,
                         Long actualWorkMillis, Long confirmedBreakMillis, Long outsideScheduleMillis,
                         Long over8hMillis, Long nightWorkMillis, Long holidayWorkMillis, Long legalOvertimeMillis,
                         double leaveDays, Long paidLeaveMillis, long inputRevision, long reviewRevision, Set<String> reasons) {
            this(workDate, status, scheduledMillis, presenceMillis, actualWorkMillis, confirmedBreakMillis, outsideScheduleMillis,
                    over8hMillis, nightWorkMillis, holidayWorkMillis, legalOvertimeMillis, leaveDays, paidLeaveMillis,
                    inputRevision, reviewRevision, reasons, Attendance.NONE);
        }
    }
    public record PeriodResult(LocalDate start, LocalDate end, Status status, long confirmedWorkMillis,
                               int unconfirmedDays, Long over40hMillis, Long over8hMillis, List<DayResult> days,
                               Set<String> reasons, Long paidLeaveMillis) { }

    public DayResult day(DayInput input) { return day(input, AttendanceContext.NONE); }

    public DayResult day(DayInput input, AttendanceContext context) {
        Objects.requireNonNull(input); Objects.requireNonNull(context); Objects.requireNonNull(input.workDate());
        Objects.requireNonNull(input.events()); Objects.requireNonNull(input.schedules());
        Objects.requireNonNull(input.dayKind());
        if (input.inputRevision()<0 || input.reviewRevision()<0 || (input.approvedLeaveDays()!=0
                && input.approvedLeaveDays()!=0.5 && input.approvedLeaveDays()!=1)) throw new IllegalArgumentException("Invalid day revision or leave quantity");
        if ((input.paidLeaveDays()!=0 && input.paidLeaveDays()!=0.5 && input.paidLeaveDays()!=1)
                || input.paidLeaveDays()>input.approvedLeaveDays()) throw new IllegalArgumentException("Invalid paid leave quantity");
        var reasons = new TreeSet<String>();
        reasons.add("LEGAL_OVERTIME_UNDETERMINED");
        if (!input.generalRuleReviewed()) reasons.add("LEGAL_APPLICABILITY_UNCONFIRMED");
        if (input.dayKind()==DayKind.UNCONFIRMED) reasons.add("CALENDAR_UNCONFIRMED");
        if (input.pendingCorrection()) reasons.add("PENDING_CORRECTION");

        Schedule schedule = input.schedules().size()==1 ? input.schedules().getFirst() : null;
        boolean selective = schedule != null && schedule.selective();
        if (selective) reasons.add(SELECTIVE_SETTLEMENT);
        Long scheduled = null;
        List<Interval> scheduledWork = null;
        if (schedule==null) reasons.add(input.schedules().isEmpty()?"SCHEDULE_MISSING":"SCHEDULE_CONFLICT");
        else if (!schedule.workday()) {
            if (schedule.start()!=null || schedule.end()!=null || schedule.plannedBreaks()==null
                    || !schedule.plannedBreaks().isEmpty()) reasons.add("INVALID_SCHEDULE");
            else { scheduled=0L; scheduledWork=List.of(); }
        } else if (!valid(schedule.start(),schedule.end()) || !sameDay(schedule.start(),schedule.end(),input.workDate())) {
            reasons.add("INVALID_SCHEDULE");
        } else {
            Interval bounds = new Interval(schedule.start(),schedule.end());
            var breaks = checkedBreaks(schedule.plannedBreaks(),bounds);
            if (breaks==null || breaks.stream().mapToLong(WorktimeCalculator::length).sum()
                    != schedule.plannedBreaks().stream().mapToLong(WorktimeCalculator::length).sum()) {
                reasons.add("INVALID_SCHEDULE");
            } else if (selective && (schedule.standardMinutes() == null || schedule.standardMinutes() <= 0
                    || schedule.core() != null && (!valid(schedule.core().start(), schedule.core().end())
                    || schedule.core().start().isBefore(bounds.start()) || schedule.core().end().isAfter(bounds.end())))) {
                reasons.add("INVALID_SCHEDULE");
            } else {
                scheduledWork=subtract(bounds,breaks);
                if (!selective) scheduled=total(scheduledWork);
                else if (input.dayKind()!=DayKind.UNCONFIRMED) {
                    scheduled=input.dayKind()==DayKind.WORKDAY?schedule.standardMinutes()*60_000L:0L;
                }
            }
        }

        var uniqueEvents = new LinkedHashMap<UUID,ClockEvent>();
        for (ClockEvent event : input.events()) {
            Objects.requireNonNull(event); Objects.requireNonNull(event.commandId()); Objects.requireNonNull(event.kind());
            precise(event.occurredAt());
            ClockEvent old = uniqueEvents.putIfAbsent(event.commandId(),event);
            if (old!=null && !old.equals(event)) reasons.add("DUPLICATE_CLOCK_EVENT");
        }
        var ins = uniqueEvents.values().stream().filter(e->e.kind()==EventKind.IN).toList();
        var outs = uniqueEvents.values().stream().filter(e->e.kind()==EventKind.OUT).toList();
        boolean noEvents=uniqueEvents.isEmpty();
        boolean nonWorkday=scheduled!=null && (scheduled==0 || input.dayKind()==DayKind.NON_WORKDAY
                || input.dayKind()==DayKind.HOLIDAY) && input.dayKind()!=DayKind.UNCONFIRMED;
        boolean judged = scheduledWork != null && !selective && schedule.workday() && scheduled != null && scheduled > 0
                && input.dayKind() == DayKind.WORKDAY && input.approvedLeaveDays() < 1;
        String absence = null;
        Long presence=null, actual=null, confirmedBreak=null, outside=null, overEight=null, night=null, holiday=null, approvedOvertime=null;
        if (noEvents) {
            if (scheduled!=null && input.dayKind()!=DayKind.UNCONFIRMED && !input.pendingCorrection()
                    && (nonWorkday || input.approvedLeaveDays()==1)) {
                actual=0L; confirmedBreak=0L; outside=0L; night=0L; holiday=0L; approvedOvertime=0L;
                reasons.add(input.approvedLeaveDays()==1?"APPROVED_LEAVE_NO_ATTENDANCE":"NO_CLOCK_ON_CONFIRMED_NON_WORKDAY");
            } else if (judged && context.dayEnded() && !input.pendingCorrection()) {
                actual=0L; confirmedBreak=0L; outside=0L; night=0L; holiday=0L; approvedOvertime=0L;
                absence = input.approvedLeaveDays() == 0.5 ? "HALF" : "FULL";
                reasons.add("ABSENT");
            } else if (!input.pendingCorrection()) {
                reasons.add("MISSING_CLOCK_IN"); reasons.add("MISSING_CLOCK_OUT");
            }
        } else {
            if (input.approvedLeaveDays()==1) reasons.add("LEAVE_WORK_CONFLICT");
            if (ins.size()>1 || outs.size()>1) reasons.add("DUPLICATE_CLOCK_EVENT");
            if (ins.isEmpty()) reasons.add("MISSING_CLOCK_IN");
            if (outs.isEmpty()) reasons.add("MISSING_CLOCK_OUT");
            if (ins.size()==1 && outs.size()==1 && !reasons.contains("DUPLICATE_CLOCK_EVENT")) {
                Instant start=ins.getFirst().occurredAt(), end=outs.getFirst().occurredAt();
                if (!start.isBefore(end)) reasons.add("INVALID_CLOCK_ORDER");
                else if (!sameDay(start,end,input.workDate())) reasons.add("CROSS_MIDNIGHT_UNSUPPORTED");
                else {
                    Interval bounds=new Interval(start,end); presence=length(bounds);
                    Confirmation confirmation=input.confirmation();
                    if (confirmation==null || confirmation.actualBreaks()==null) reasons.add("BREAK_UNCONFIRMED");
                    else if (confirmation.confirmedInputRevision()!=input.inputRevision()
                            || confirmation.reviewRevision()>input.reviewRevision() || confirmation.reviewRevision()<0) {
                        reasons.add("CONFIRMATION_STALE");
                    } else if (!confirmation.presenceAsWork()) reasons.add("WORK_UNCONFIRMED");
                    else if (!input.pendingCorrection()) {
                        var breaks=checkedBreaks(confirmation.actualBreaks(),bounds);
                        if (breaks==null) reasons.add("INVALID_BREAK_INTERVAL");
                        else {
                            var work=subtract(bounds,breaks); actual=total(work); confirmedBreak=total(breaks);
                            if (selective && scheduledWork!=null && schedule.workday()) {
                                var window=new Interval(schedule.start(),schedule.end());
                                outside=actual-work.stream().mapToLong(interval->intersection(interval,window)).sum();
                                if (outside>0) reasons.add("SELECTIVE_WINDOW_REVIEW");
                                if (input.dayKind()==DayKind.WORKDAY && input.approvedLeaveDays()==0 && schedule.core()!=null
                                        && !coversCore(schedule.core(),scheduledWork,work)) reasons.add("SELECTIVE_CORE_REVIEW");
                            } else if (!selective && scheduledWork!=null) {
                                long inside=0;
                                for (var real:work) for (var planned:scheduledWork) inside+=intersection(real,planned);
                                outside=actual-inside;
                                if (outside>0) reasons.add("EXTRA_WORK_REVIEW");
                            }
                            approvedOvertime=0L;
                            for (var real:work) for (var approved:input.approvedOvertime()) approvedOvertime+=intersection(real,approved);
                            night=0L;
                            Interval early=new Interval(input.workDate().atStartOfDay(SEOUL).toInstant(),
                                    input.workDate().atTime(6,0).atZone(SEOUL).toInstant());
                            Interval late=new Interval(input.workDate().atTime(22,0).atZone(SEOUL).toInstant(),
                                    input.workDate().plusDays(1).atStartOfDay(SEOUL).toInstant());
                            for (var real:work) night+=intersection(real,early)+intersection(real,late);
                            if (input.dayKind()!=DayKind.UNCONFIRMED) holiday=input.dayKind()==DayKind.HOLIDAY?actual:0L;
                            if (input.dayKind()==DayKind.HOLIDAY && actual>0) reasons.add("HOLIDAY_WORK_REVIEW");
                            long minimumBreak=actual>=8*HOUR?HOUR:actual>=4*HOUR?HOUR/2:0;
                            if (confirmedBreak<minimumBreak) reasons.add("BREAK_INSUFFICIENT_REVIEW");
                        }
                    }
                }
            }
        }
        if (!selective && actual!=null && input.generalRuleReviewed()) overEight=Math.max(0L,actual-8*HOUR);
        Long late = null, early = null;
        if (judged && !reasons.contains("DUPLICATE_CLOCK_EVENT") && !reasons.contains("INVALID_CLOCK_ORDER")
                && !reasons.contains("CROSS_MIDNIGHT_UNSUPPORTED")) {
            Instant expectedStart = schedule.start(), expectedEnd = schedule.end();
            Instant middle = schedule.start().plus(Duration.between(schedule.start(), schedule.end()).dividedBy(2));
            // 반차 기준 휴게: 가운데 시각 전에 시작하는 첫 휴게. 없으면 가운데 시각.
            Interval first = schedule.plannedBreaks().stream().filter(b -> b.start().isBefore(middle))
                    .min(Comparator.comparing(Interval::start)).orElse(null);
            if ("AM".equals(context.leavePart())) expectedStart = first == null ? middle : first.end();
            if ("PM".equals(context.leavePart())) expectedEnd = first == null ? middle : first.start();
            if (ins.size() == 1 && sameDay(ins.getFirst().occurredAt(), ins.getFirst().occurredAt(), input.workDate())) {
                late = Math.max(0L, Duration.between(expectedStart, ins.getFirst().occurredAt()).toMillis());
                if (late > 0) reasons.add("LATE");
            }
            if (outs.size() == 1 && sameDay(outs.getFirst().occurredAt(), outs.getFirst().occurredAt(), input.workDate())) {
                early = Math.max(0L, Duration.between(outs.getFirst().occurredAt(), expectedEnd).toMillis());
                if (early > 0) reasons.add("EARLY_LEAVE");
            }
        }
        Attendance attendance = absence != null ? new Attendance(null, null, absence)
                : late == null && early == null ? Attendance.NONE : new Attendance(late, early, null);
        boolean review=reasons.stream().anyMatch(reason->!INFORMATIONAL.contains(reason));
        Status status=review || actual==null?Status.REVIEW_REQUIRED
                :noEvents && nonWorkday && input.approvedLeaveDays()==0?Status.NO_SCHEDULED_WORK:Status.CALCULATED;
        return new DayResult(input.workDate(),status,scheduled,presence,actual,confirmedBreak,outside,overEight,night,holiday,
                null,input.approvedLeaveDays(),paidLeaveMillis(input.dayKind(),scheduled,input.paidLeaveDays()),
                input.inputRevision(),input.reviewRevision(),Set.copyOf(reasons),attendance,approvedOvertime);
    }

    static Long paidLeaveMillis(DayKind dayKind, Long scheduledMillis, double paidDays) {
        if (paidDays == 0 || dayKind == DayKind.HOLIDAY || dayKind == DayKind.NON_WORKDAY) return 0L;
        if (scheduledMillis == null || dayKind == null || dayKind == DayKind.UNCONFIRMED) return null;
        return Math.round(scheduledMillis * paidDays);
    }

    public PeriodResult week(LocalDate start,List<DayResult> days,boolean weekBasisReviewed) {
        if (!weekStart(start).equals(start)) throw new IllegalArgumentException("Week must start on Monday");
        return period(start,start.plusDays(7),days,true,weekBasisReviewed);
    }
    public PeriodResult month(YearMonth month,List<DayResult> days) {
        return period(month.atDay(1),month.plusMonths(1).atDay(1),days,false,false);
    }
    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    }
    public static boolean current(DayResult result,long inputRevision,long reviewRevision) {
        return result!=null && current(result.inputRevision(),result.reviewRevision(),inputRevision,reviewRevision);
    }
    static boolean current(long savedInput,long savedReview,long inputRevision,long reviewRevision) {
        return savedInput==inputRevision && savedReview==reviewRevision;
    }

    /**
     * 확인 전: 지난 날(오늘은 퇴근 기록 뒤)인데 실근로가 표시되지 않는 날.
     * 기록 없이 0시간으로 확정되는 확정 비근무일·승인 휴가일만 제외하며, 그 판단은 {@link #day} 규칙을 그대로 쓴다.
     */
    public boolean pending(DayInput input, LocalDate today, boolean current, Long actualWorkMillis) {
        boolean out = input.events().stream().anyMatch(event -> event.kind()==EventKind.OUT);
        if (input.workDate().isAfter(today) || input.workDate().equals(today) && !out) return false;
        if (current && actualWorkMillis!=null) return false;
        if (!input.events().isEmpty()) return true;
        try { return day(input).actualWorkMillis()==null; }
        catch (IllegalArgumentException invalid) { return true; }
    }

    private PeriodResult period(LocalDate start,LocalDate end,List<DayResult> input,boolean week,boolean basisReviewed) {
        var byDate=new HashMap<LocalDate,DayResult>();
        for (var day:input) {
            if (day.workDate().isBefore(start) || !day.workDate().isBefore(end)) continue;
            if (byDate.putIfAbsent(day.workDate(),day)!=null) throw new IllegalArgumentException("Duplicate result date");
        }
        var days=byDate.values().stream().sorted(Comparator.comparing(DayResult::workDate)).toList();
        int missing=(int)java.time.temporal.ChronoUnit.DAYS.between(start,end)-days.size();
        int unconfirmed=missing+(int)days.stream().filter(day->day.actualWorkMillis()==null).count();
        long confirmed=days.stream().filter(day->day.actualWorkMillis()!=null).mapToLong(DayResult::actualWorkMillis).sum();
        boolean classificationsComplete=unconfirmed==0 && days.stream().allMatch(day->day.over8hMillis()!=null);
        Long overEight=classificationsComplete?days.stream().mapToLong(DayResult::over8hMillis).sum():null;
        Long overForty=week && basisReviewed && classificationsComplete?Math.max(0L,confirmed-40*HOUR):null;
        var reasons=new TreeSet<String>();
        if (unconfirmed>0) reasons.add("PARTIAL_TOTAL");
        if (days.stream().anyMatch(day->day.status()==Status.REVIEW_REQUIRED)) reasons.add("INPUT_REVIEW_REQUIRED");
        boolean selectiveWeek=week && days.stream().anyMatch(day->day.reasons().contains(SELECTIVE_SETTLEMENT));
        if (week && !selectiveWeek && !basisReviewed) reasons.add("WEEK_BASIS_UNCONFIRMED");
        if (week && !selectiveWeek && !classificationsComplete) reasons.add("WEEK_REFERENCE_UNCONFIRMED");
        if (overForty!=null && overForty>12*HOUR) reasons.add("WEEK_LIMIT_REVIEW");
        Status status=reasons.isEmpty()?Status.CALCULATED:Status.REVIEW_REQUIRED;
        if (selectiveWeek) reasons.add(SELECTIVE_SETTLEMENT);
        Long paidLeave = missing == 0 && days.stream().allMatch(day -> day.paidLeaveMillis() != null)
                ? days.stream().mapToLong(DayResult::paidLeaveMillis).sum() : null;
        return new PeriodResult(start,end,status,confirmed,unconfirmed,
                overForty,overEight,List.copyOf(days),Set.copyOf(reasons),paidLeave);
    }

    private static void precise(Instant value) {
        Objects.requireNonNull(value);
        if (value.getNano()%1_000_000!=0) throw new IllegalArgumentException("Only millisecond precision is supported");
    }
    private static boolean valid(Instant start,Instant end) {
        if (start==null || end==null) return false;
        precise(start);precise(end);return start.isBefore(end);
    }
    private static boolean sameDay(Instant start,Instant end,LocalDate date) {
        return start.atZone(SEOUL).toLocalDate().equals(date) && end.atZone(SEOUL).toLocalDate().equals(date);
    }
    private static List<Interval> checkedBreaks(List<Interval> intervals,Interval bounds) {
        if (intervals==null) return null;
        for (var interval:intervals) {
            if (interval==null || !valid(interval.start(),interval.end()) || interval.start().isBefore(bounds.start())
                    || interval.end().isAfter(bounds.end())) return null;
        }
        var sorted=intervals.stream().sorted(Comparator.comparing(Interval::start)).toList();
        var union=new ArrayList<Interval>();
        for (var interval:sorted) {
            if (union.isEmpty() || union.getLast().end().isBefore(interval.start())) union.add(interval);
            else {
                var previous=union.removeLast();
                union.add(new Interval(previous.start(),previous.end().isAfter(interval.end())?previous.end():interval.end()));
            }
        }
        return union;
    }
    private static List<Interval> subtract(Interval bounds,List<Interval> breaks) {
        var work=new ArrayList<Interval>();Instant cursor=bounds.start();
        for (var interval:breaks) {
            if (cursor.isBefore(interval.start())) work.add(new Interval(cursor,interval.start()));
            cursor=interval.end();
        }
        if (cursor.isBefore(bounds.end())) work.add(new Interval(cursor,bounds.end()));
        return work;
    }
    private static boolean coversCore(Interval core,List<Interval> scheduledWork,List<Interval> actualWork) {
        for (var planned:scheduledWork) {
            Instant from=core.start().isAfter(planned.start())?core.start():planned.start();
            Instant to=core.end().isBefore(planned.end())?core.end():planned.end();
            if (!from.isBefore(to)) continue;
            var required=new Interval(from,to);
            if (actualWork.stream().mapToLong(real->intersection(real,required)).sum()<length(required)) return false;
        }
        return true;
    }
    private static long intersection(Interval a,Interval b) {
        Instant start=a.start().isAfter(b.start())?a.start():b.start();
        Instant end=a.end().isBefore(b.end())?a.end():b.end();
        return start.isBefore(end)?Duration.between(start,end).toMillis():0L;
    }
    private static long length(Interval interval) { return Duration.between(interval.start(),interval.end()).toMillis(); }
    private static long total(List<Interval> intervals) { return intervals.stream().mapToLong(WorktimeCalculator::length).sum(); }
}
