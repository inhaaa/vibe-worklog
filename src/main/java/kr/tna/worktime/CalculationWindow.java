package kr.tna.worktime;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** 요청일과 그 주의 집계에 추가되는 날짜를 구분한다. 두 경계는 포함한다. */
record CalculationWindow(LocalDate start, LocalDate end) {
    static CalculationWindow weeks(LocalDate from, LocalDate through) {
        return new CalculationWindow(WorktimeCalculator.weekStart(from), WorktimeCalculator.weekStart(through).plusDays(6));
    }

    CalculationWindow openDates(LocalDate from, LocalDate through, List<LocalDate> closedMonths) {
        var firstMonth = from.withDayOfMonth(1);
        var lastMonth = through.withDayOfMonth(1);
        for (var month : closedMonths) {
            if (!month.isBefore(firstMonth) && !month.isAfter(lastMonth)) throw ClosedAttendanceMonths.closedMonth(month);
        }
        var openStart = closedMonths.contains(start.withDayOfMonth(1)) ? firstMonth : start;
        var openEnd = closedMonths.contains(end.withDayOfMonth(1)) ? YearMonth.from(through).atEndOfMonth() : end;
        return new CalculationWindow(openStart, openEnd);
    }
}
