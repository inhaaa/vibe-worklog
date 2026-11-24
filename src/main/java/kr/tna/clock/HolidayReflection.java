package kr.tna.clock;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 휴일 표를 날짜별 근태 달력 종류로 바꾸고, 지금 근태 달력과 다른 날만 고른다. DB를 모른다. */
public final class HolidayReflection {
    public static final String WORKDAY_NAME = "근로일";
    /** calendar_day.basis는 비울 수 없다. 휴일 행 근거가 없거나 근로일일 때 쓴다. */
    public static final String DEFAULT_BASIS = "휴일 관리 반영";

    public record Holiday(String category, String name, String basis) { }
    public record Effective(LocalDate date, String kind, String name) { }
    public record Period(LocalDate validFrom, LocalDate validTo) { }
    public record Day(LocalDate date, String kind, String name, String basis) { }
    /** periods: 근태 달력이 없는 연속 구간(PERIOD 버전 하나씩). days: 날짜 하나씩 고치는 반영분(DAY 버전 하나씩). */
    public record Plan(List<List<Day>> periods, List<Day> days) {
        public List<LocalDate> dates() {
            return Stream.concat(periods.stream().flatMap(List::stream), days.stream()).map(Day::date).sorted().toList();
        }
    }

    private HolidayReflection() { }

    public static Day desired(LocalDate date, Holiday holiday) {
        if (holiday == null) return new Day(date, "WORKDAY", WORKDAY_NAME, DEFAULT_BASIS);
        String kind = "NON_WORKDAY".equals(holiday.category()) ? "NON_WORKDAY" : "HOLIDAY";
        return new Day(date, kind, holiday.name(), holiday.basis() == null ? DEFAULT_BASIS : holiday.basis());
    }

    /**
     * 근로일끼리는 이름을 비교하지 않는다(기존 기간 등록의 "검토한 기본 근로일"). 휴일 행이 없는 주말의 기존 비근로일은
     * 근로일과 같다고 본다. 근무 요일은 근무조가 정한다(설계 §2.2-4). 근거만 바뀐 날은 반영하지 않는다.
     */
    static boolean same(Day wanted, Effective current) {
        if (wanted.kind().equals("WORKDAY")) {
            return current.kind().equals("WORKDAY") || current.kind().equals("NON_WORKDAY") && weekend(wanted.date());
        }
        return wanted.kind().equals(current.kind()) && wanted.name().equals(current.name());
    }

    public static Plan plan(int year, Map<LocalDate, Holiday> holidays, Map<LocalDate, Effective> effective,
                            List<Period> periods, Set<YearMonth> closed) {
        var runs = new ArrayList<List<Day>>();
        var days = new ArrayList<Day>();
        List<Day> run = null;
        for (LocalDate date = LocalDate.of(year, 1, 1); date.getYear() == year; date = date.plusDays(1)) {
            if (closed.contains(YearMonth.from(date))) { run = null; continue; }
            Day wanted = desired(date, holidays.get(date));
            Effective current = effective.get(date);
            if (current == null && !covered(date, periods)) {
                if (run == null) { run = new ArrayList<>(); runs.add(run); }
                run.add(wanted);
                continue;
            }
            run = null;
            // 기간 안인데 날짜 행이 없는 날은 기간을 겹쳐 만들 수 없으므로 DAY로 채운다.
            if (current == null || !same(wanted, current)) days.add(wanted);
        }
        return new Plan(runs.stream().map(List::copyOf).toList(), List.copyOf(days));
    }

    private static boolean covered(LocalDate date, List<Period> periods) {
        return periods.stream().anyMatch(period -> !date.isBefore(period.validFrom()) && date.isBefore(period.validTo()));
    }

    private static boolean weekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
