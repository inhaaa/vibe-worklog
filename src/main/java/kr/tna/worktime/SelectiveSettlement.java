package kr.tna.worktime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** 선택근무 기간의 목표와 유급휴가, 확인 실근로를 각각 집계한다. */
public final class SelectiveSettlement {
    private static final Set<String> PERIOD_WARNINGS = Set.of("SELECTIVE_WINDOW_REVIEW", "SELECTIVE_CORE_REVIEW",
            "LEAVE_WORK_CONFLICT", "NOT_CALCULATED", "STALE_RESULT", "BREAK_UNCONFIRMED", "WORK_UNCONFIRMED",
            "CONFIRMATION_STALE", "INVALID_BREAK_INTERVAL", "INVALID_CLOCK_ORDER", "DUPLICATE_CLOCK_EVENT",
            "CROSS_MIDNIGHT_UNSUPPORTED", "INVALID_SCHEDULE");
    private SelectiveSettlement() { }

    public record Overview(YearMonth month, UUID employeeId, String employeeNo, String employeeName, List<Period> periods) { }
    public enum Action { CONFIRM, CORRECT, REQUEST, CALENDAR, RECALCULATE, REVIEW, NONE }
    public record UnconfirmedDate(LocalDate workDate, List<String> reasons, Action employeeAction, Action adminAction, UUID requestId) { }
    public record Period(String settlementPeriod, LocalDate fromDate, LocalDate toDate, int appliedDays,
            Integer targetMinutes, long confirmedWorkMillis, Long paidLeaveMillis, Long differenceMillis,
            int unconfirmedDays, List<String> warnings, List<UnconfirmedDate> unconfirmedDates, int upcomingDays) {
        @JsonProperty("remainingMillis")
        public Long remainingMillis() {
            if (targetMinutes == null || paidLeaveMillis == null) return null;
            return Math.max(0L, targetMinutes * 60_000L - confirmedWorkMillis - paidLeaveMillis);
        }
    }
    record Day(LocalDate date, String settlementPeriod, Integer targetMinutes, double approvedLeaveDays,
            Long actualWorkMillis, UUID pendingRequestId, Set<String> reasons, Action employeeClockAction, boolean closed) {
        UnconfirmedDate detail(Set<String> warnings) {
            var causes = new TreeSet<>(warnings);
            causes.remove("SELECTIVE_CORE_REVIEW");
            causes.remove("SELECTIVE_WINDOW_REVIEW");
            if (reasons.contains("DAY_IN_PROGRESS")) causes.add("DAY_IN_PROGRESS");
            if (causes.isEmpty()) causes.add("WORK_UNCONFIRMED");
            if (closed) {
                causes.add("MONTH_CLOSED");
                return new UnconfirmedDate(date, List.copyOf(causes), Action.NONE, Action.NONE, null);
            }
            var codes = List.copyOf(causes);
            if (pendingRequestId != null) return new UnconfirmedDate(date, codes, Action.REQUEST, Action.REQUEST, pendingRequestId);
            if (targetMinutes == null) return new UnconfirmedDate(date, codes, Action.NONE, Action.CALENDAR, null);
            boolean justCalculation = causes.size() == 1 && (causes.contains("NOT_CALCULATED") || causes.contains("STALE_RESULT"));
            return new UnconfirmedDate(date, codes, employeeClockAction, justCalculation ? Action.RECALCULATE : Action.REVIEW, null);
        }
    }

    static List<Period> periods(YearMonth month, LocalDate today, List<Day> days) {
        var grouped = new HashMap<Range, List<Day>>();
        for (var day : days) {
            var range = Range.of(day);
            if (!range.to().isBefore(month.atDay(1)) && !range.from().isAfter(month.atEndOfMonth())) {
                grouped.computeIfAbsent(range, ignored -> new ArrayList<>()).add(day);
            }
        }
        return grouped.entrySet().stream().map(entry -> summarize(entry.getKey(), today, entry.getValue()))
                .sorted(Comparator.comparing(Period::fromDate).thenComparing(Period::settlementPeriod)).toList();
    }

    private record Range(String period, LocalDate from, LocalDate to) {
        static Range of(Day day) {
            return switch (day.settlementPeriod()) {
                case "CALENDAR_MONTH" -> new Range(day.settlementPeriod(), day.date().withDayOfMonth(1),
                        YearMonth.from(day.date()).atEndOfMonth());
                case "ISO_WEEK" -> {
                    var monday = WorktimeCalculator.weekStart(day.date());
                    yield new Range(day.settlementPeriod(), monday, monday.plusDays(6));
                }
                default -> throw new IllegalArgumentException("정산기간을 확인해 주세요.");
            };
        }
    }

    private static Period summarize(Range range, LocalDate today, List<Day> days) {
        int target = 0, upcoming = 0;
        long work = 0, paid = 0;
        boolean knownCalendar = true;
        var warnings = new TreeSet<String>();
        var dates = new ArrayList<UnconfirmedDate>();
        for (var day : days) {
            var dayWarnings = new TreeSet<String>();
            if (day.targetMinutes() == null) {
                knownCalendar = false;
                dayWarnings.add("CALENDAR_UNCONFIRMED");
            }
            else {
                target += day.targetMinutes();
                paid += Math.round(day.targetMinutes() * 60_000L * day.approvedLeaveDays());
            }
            boolean pending = day.pendingRequestId() != null;
            if (day.actualWorkMillis() != null && !pending) work += day.actualWorkMillis();
            if (pending) dayWarnings.add("PENDING_CORRECTION");
            for (String reason : day.reasons()) {
                boolean missingPastClock = !day.date().isAfter(today)
                        && ("MISSING_CLOCK_IN".equals(reason) || "MISSING_CLOCK_OUT".equals(reason));
                if (missingPastClock || PERIOD_WARNINGS.contains(reason)) dayWarnings.add(reason);
            }
            warnings.addAll(dayWarnings);
            if (day.actualWorkMillis() == null || pending || day.targetMinutes() == null || day.reasons().contains("LEAVE_WORK_CONFLICT")) {
                if (day.date().isAfter(today)) upcoming++;
                else dates.add(day.detail(dayWarnings));
            }
        }
        dates.sort(Comparator.comparing(UnconfirmedDate::workDate));
        int unconfirmed = dates.size() + upcoming;
        Long difference = unconfirmed == 0 && today.isAfter(range.to()) ? work + paid - target * 60_000L : null;
        return new Period(range.period(), range.from(), range.to(), days.size(),
                knownCalendar ? target : null, work, knownCalendar ? paid : null, difference, unconfirmed,
                List.copyOf(warnings), List.copyOf(dates), upcoming);
    }
}
