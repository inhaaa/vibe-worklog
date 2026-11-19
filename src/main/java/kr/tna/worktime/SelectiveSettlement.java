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
    public record Period(String settlementPeriod, LocalDate fromDate, LocalDate toDate, int appliedDays,
            Integer targetMinutes, long confirmedWorkMillis, Long paidLeaveMillis, Long differenceMillis,
            int unconfirmedDays, List<String> warnings) {
        @JsonProperty("remainingMillis")
        public Long remainingMillis() {
            if (targetMinutes == null || paidLeaveMillis == null) return null;
            return Math.max(0L, targetMinutes * 60_000L - confirmedWorkMillis - paidLeaveMillis);
        }
    }
    record Day(LocalDate date, String settlementPeriod, Integer targetMinutes, double approvedLeaveDays,
            Long actualWorkMillis, boolean pendingCorrection, Set<String> reasons) { }

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
        int target = 0, unconfirmed = 0;
        long work = 0, paid = 0;
        boolean knownCalendar = true;
        var warnings = new TreeSet<String>();
        for (var day : days) {
            if (day.targetMinutes() == null) {
                knownCalendar = false;
                warnings.add("CALENDAR_UNCONFIRMED");
            }
            else {
                target += day.targetMinutes();
                paid += Math.round(day.targetMinutes() * 60_000L * day.approvedLeaveDays());
            }
            if (day.actualWorkMillis() != null && !day.pendingCorrection()) work += day.actualWorkMillis();
            if (day.pendingCorrection()) warnings.add("PENDING_CORRECTION");
            if (day.actualWorkMillis() == null || day.pendingCorrection() || day.targetMinutes() == null
                    || day.reasons().contains("LEAVE_WORK_CONFLICT")) unconfirmed++;
            for (String reason : day.reasons()) {
                boolean missingPastClock = !day.date().isAfter(today)
                        && ("MISSING_CLOCK_IN".equals(reason) || "MISSING_CLOCK_OUT".equals(reason));
                if (missingPastClock || PERIOD_WARNINGS.contains(reason)) warnings.add(reason);
            }
        }
        Long difference = unconfirmed == 0 && today.isAfter(range.to()) ? work + paid - target * 60_000L : null;
        return new Period(range.period(), range.from(), range.to(), days.size(),
                knownCalendar ? target : null, work, knownCalendar ? paid : null, difference, unconfirmed, List.copyOf(warnings));
    }
}
