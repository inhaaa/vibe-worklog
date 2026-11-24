package kr.tna.clock;

import static kr.tna.clock.HolidayReflection.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HolidayReflectionTest {
    private static final List<Period> YEAR_2026 = List.of(new Period(d(2026, 1, 1), d(2027, 1, 1)));

    private static LocalDate d(int y, int m, int day) { return LocalDate.of(y, m, day); }

    /** 기존 기간 등록과 데모 시드처럼 평일 WORKDAY, 주말 NON_WORKDAY인 한 해. */
    private static Map<LocalDate, Effective> weekdayCalendar(int year) {
        var map = new HashMap<LocalDate, Effective>();
        for (LocalDate date = d(year, 1, 1); date.getYear() == year; date = date.plusDays(1)) {
            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            map.put(date, new Effective(date, weekend ? "NON_WORKDAY" : "WORKDAY", weekend ? "주말" : "검토한 기본 근로일"));
        }
        return map;
    }

    @Test void anUncoveredYearBecomesOnePeriodWithEveryDate() {
        var plan = plan(2027, Map.of(
                d(2027, 1, 1), new Holiday("PUBLIC", "1월 1일", "공휴일 · 근거"),
                d(2027, 3, 2), new Holiday("NON_WORKDAY", "창립 휴무", null)), Map.of(), List.of(), Set.of());
        assertEquals(List.of(), plan.days());
        assertEquals(1, plan.periods().size());
        var days = plan.periods().getFirst();
        assertEquals(365, days.size());
        assertEquals(new Day(d(2027, 1, 1), "HOLIDAY", "1월 1일", "공휴일 · 근거"), days.get(0));
        assertEquals(new Day(d(2027, 1, 2), "WORKDAY", WORKDAY_NAME, DEFAULT_BASIS), days.get(1));
        assertEquals(new Day(d(2027, 3, 2), "NON_WORKDAY", "창립 휴무", DEFAULT_BASIS), days.get(60));
    }

    @Test void onlyDatesWhoseKindOrHolidayNameDiffersGetADayRevision() {
        var current = weekdayCalendar(2026);
        current.put(d(2026, 5, 5), new Effective(d(2026, 5, 5), "HOLIDAY", "어린이 날"));
        current.put(d(2026, 8, 17), new Effective(d(2026, 8, 17), "HOLIDAY", "대체공휴일(광복절)"));
        var plan = plan(2026, Map.of(
                d(2026, 5, 5), new Holiday("PUBLIC", "어린이날", null),
                d(2026, 8, 17), new Holiday("PUBLIC", "대체공휴일(광복절)", "근거만 바뀜"),
                d(2026, 9, 24), new Holiday("PUBLIC", "추석 전날", null)), current, YEAR_2026, Set.of());
        assertEquals(List.of(), plan.periods());
        assertEquals(List.of(d(2026, 5, 5), d(2026, 9, 24)), plan.dates());
    }

    @Test void datesWithoutAHolidayReturnToWorkdaysExceptExistingWeekendNonWorkdays() {
        var current = weekdayCalendar(2026);
        current.put(d(2026, 3, 1), new Effective(d(2026, 3, 1), "HOLIDAY", "3·1절"));            // 일요일
        current.put(d(2026, 3, 4), new Effective(d(2026, 3, 4), "NON_WORKDAY", "창립 휴무"));     // 수요일
        var plan = plan(2026, Map.of(), current, YEAR_2026, Set.of());
        assertEquals(List.of(
                new Day(d(2026, 3, 1), "WORKDAY", WORKDAY_NAME, DEFAULT_BASIS),
                new Day(d(2026, 3, 4), "WORKDAY", WORKDAY_NAME, DEFAULT_BASIS)), plan.days());
    }

    @Test void closedMonthsAreSkippedAndSplitTheUncoveredPeriod() {
        var plan = plan(2026, Map.of(d(2026, 1, 2), new Holiday("COMPANY", "시무식", null)), Map.of(), List.of(),
                Set.of(YearMonth.of(2026, 1), YearMonth.of(2026, 6)));
        assertEquals(2, plan.periods().size());
        assertEquals(d(2026, 2, 1), plan.periods().get(0).getFirst().date());
        assertEquals(d(2026, 5, 31), plan.periods().get(0).getLast().date());
        assertEquals(d(2026, 7, 1), plan.periods().get(1).getFirst().date());
        assertEquals(d(2026, 12, 31), plan.periods().get(1).getLast().date());
        assertEquals(365 - 31 - 30, plan.dates().size());
    }

    @Test void aPeriodDateWithoutADayRowIsFilledByADayRevisionNotAnOverlappingPeriod() {
        var current = new HashMap<LocalDate, Effective>();
        current.put(d(2026, 3, 1), new Effective(d(2026, 3, 1), "NON_WORKDAY", "주말"));
        current.put(d(2026, 3, 3), new Effective(d(2026, 3, 3), "WORKDAY", "근로일"));
        var plan = plan(2026, Map.of(), current, List.of(new Period(d(2026, 3, 1), d(2026, 3, 4))), Set.of());
        assertEquals(List.of(new Day(d(2026, 3, 2), "WORKDAY", WORKDAY_NAME, DEFAULT_BASIS)), plan.days());
        assertEquals(2, plan.periods().size());
        assertEquals(d(2026, 2, 28), plan.periods().get(0).getLast().date());
        assertEquals(d(2026, 3, 4), plan.periods().get(1).getFirst().date());
    }

    @Test void aDayRevisionIsNeverOverriddenByAGapFillingPeriod() {
        // 5/5은 기간 밖에서 날짜 반영분(DAY)만 있다. 빈 구간을 채우는 PERIOD는 이 날짜를 덮으면 안 된다.
        var current = new HashMap<LocalDate, Effective>();
        current.put(d(2026, 5, 5), new Effective(d(2026, 5, 5), "HOLIDAY", "어린이날"));
        var plan = plan(2026, Map.of(d(2026, 5, 5), new Holiday("PUBLIC", "어린이날", null)), current, List.of(), Set.of());
        assertEquals(List.of(), plan.days());
        assertEquals(2, plan.periods().size());
        assertEquals(d(2026, 5, 4), plan.periods().get(0).getLast().date());
        assertEquals(d(2026, 5, 6), plan.periods().get(1).getFirst().date());
        assertFalse(plan.dates().contains(d(2026, 5, 5)));
    }
}
