package kr.tna.clock;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 내장 공휴일(개통 준비 설계 §2.4). 매년 한국천문연구원 월력요항이 관보에 공고되면(보통 6월 말) 다음 해 목록을 더한다.
 * 절차는 docs/development/local.md '휴일 관리와 공휴일 연례 추가'를 따른다.
 */
public final class KoreanPublicHolidays {
    public record Holiday(LocalDate date, String name, String basis) { }

    public static final String SMALL_WORKPLACE_NOTICE =
            "상시 4인 이하 사업장은 노동절 외 공휴일의 유급휴일 의무가 없습니다. 회사 기준에 맞게 날짜를 바꿀 수 있습니다.";
    private static final String Y2026 = "2026년 월력요항(우주항공청공고 제2025-0044호)";
    private static final String RULE = "관공서의 공휴일에 관한 규정(대통령령 제36290호)";
    private static final String Y2027 = "2027년 월력요항(우주항공청공고 제2026-0078호)";
    private static final String PUBLIC = "공휴일", NATIONAL = "공휴일(국경일)", SUBSTITUTE = "대체공휴일",
            LABOUR = "공휴일·근로자 유급휴일", ELECTION = "선거일";

    private static final Map<Integer, List<Holiday>> YEARS = Map.of(
            2026, List.of(
                    h("2026-01-01", "1월 1일", PUBLIC, Y2026), h("2026-02-16", "설날 전날", PUBLIC, Y2026),
                    h("2026-02-17", "설날", PUBLIC, Y2026), h("2026-02-18", "설날 다음 날", PUBLIC, Y2026),
                    h("2026-03-01", "3·1절", NATIONAL, Y2026), h("2026-03-02", "대체공휴일(3·1절)", SUBSTITUTE, Y2026),
                    h("2026-05-01", "노동절", LABOUR, RULE), h("2026-05-05", "어린이날", PUBLIC, Y2026),
                    h("2026-05-24", "부처님 오신 날", PUBLIC, Y2026), h("2026-05-25", "대체공휴일(부처님 오신 날)", SUBSTITUTE, Y2026),
                    h("2026-06-03", "제9회 전국동시지방선거", ELECTION, Y2026), h("2026-06-06", "현충일", PUBLIC, Y2026),
                    h("2026-07-17", "제헌절", NATIONAL, RULE), h("2026-08-15", "광복절", NATIONAL, Y2026),
                    h("2026-08-17", "대체공휴일(광복절)", SUBSTITUTE, Y2026), h("2026-09-24", "추석 전날", PUBLIC, Y2026),
                    h("2026-09-25", "추석", PUBLIC, Y2026), h("2026-09-26", "추석 다음 날", PUBLIC, Y2026),
                    h("2026-10-03", "개천절", NATIONAL, Y2026), h("2026-10-05", "대체공휴일(개천절)", SUBSTITUTE, Y2026),
                    h("2026-10-09", "한글날", NATIONAL, Y2026), h("2026-12-25", "기독탄신일", PUBLIC, Y2026)),
            2027, List.of(
                    h("2027-01-01", "1월 1일", PUBLIC, Y2027), h("2027-02-06", "설날 전날", PUBLIC, Y2027),
                    h("2027-02-07", "설날", PUBLIC, Y2027), h("2027-02-08", "설날 다음 날", PUBLIC, Y2027),
                    h("2027-02-09", "대체공휴일(설날)", SUBSTITUTE, Y2027), h("2027-03-01", "3·1절", NATIONAL, Y2027),
                    h("2027-05-01", "노동절", LABOUR, Y2027), h("2027-05-03", "대체공휴일(노동절)", SUBSTITUTE, Y2027),
                    h("2027-05-05", "어린이날", PUBLIC, Y2027), h("2027-05-13", "부처님 오신 날", PUBLIC, Y2027),
                    h("2027-06-06", "현충일", PUBLIC, Y2027), h("2027-07-17", "제헌절", NATIONAL, Y2027),
                    h("2027-07-19", "대체공휴일(제헌절)", SUBSTITUTE, Y2027), h("2027-08-15", "광복절", NATIONAL, Y2027),
                    h("2027-08-16", "대체공휴일(광복절)", SUBSTITUTE, Y2027), h("2027-09-14", "추석 전날", PUBLIC, Y2027),
                    h("2027-09-15", "추석", PUBLIC, Y2027), h("2027-09-16", "추석 다음 날", PUBLIC, Y2027),
                    h("2027-10-03", "개천절", NATIONAL, Y2027), h("2027-10-04", "대체공휴일(개천절)", SUBSTITUTE, Y2027),
                    h("2027-10-09", "한글날", NATIONAL, Y2027), h("2027-10-11", "대체공휴일(한글날)", SUBSTITUTE, Y2027),
                    h("2027-12-25", "기독탄신일", PUBLIC, Y2027), h("2027-12-27", "대체공휴일(기독탄신일)", SUBSTITUTE, Y2027)));

    private KoreanPublicHolidays() { }

    /** 내장되지 않은 연도는 빈 목록이다. */
    public static List<Holiday> of(int year) {
        return YEARS.getOrDefault(year, List.of());
    }

    private static Holiday h(String date, String name, String kind, String source) {
        return new Holiday(LocalDate.parse(date), name, kind + " · " + source);
    }
}
