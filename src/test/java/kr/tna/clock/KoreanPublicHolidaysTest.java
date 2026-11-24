package kr.tna.clock;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class KoreanPublicHolidaysTest {
    private static List<String> lines(int year) {
        return KoreanPublicHolidays.of(year).stream().map(h -> h.date() + " " + h.name()).toList();
    }

    @Test void year2026MatchesTheOfficialTable() {
        assertEquals(List.of(
                "2026-01-01 1월 1일", "2026-02-16 설날 전날", "2026-02-17 설날", "2026-02-18 설날 다음 날",
                "2026-03-01 3·1절", "2026-03-02 대체공휴일(3·1절)", "2026-05-01 노동절", "2026-05-05 어린이날",
                "2026-05-24 부처님 오신 날", "2026-05-25 대체공휴일(부처님 오신 날)", "2026-06-03 제9회 전국동시지방선거",
                "2026-06-06 현충일", "2026-07-17 제헌절", "2026-08-15 광복절", "2026-08-17 대체공휴일(광복절)",
                "2026-09-24 추석 전날", "2026-09-25 추석", "2026-09-26 추석 다음 날", "2026-10-03 개천절",
                "2026-10-05 대체공휴일(개천절)", "2026-10-09 한글날", "2026-12-25 기독탄신일"), lines(2026));
    }

    @Test void year2027MatchesTheOfficialTable() {
        assertEquals(List.of(
                "2027-01-01 1월 1일", "2027-02-06 설날 전날", "2027-02-07 설날", "2027-02-08 설날 다음 날",
                "2027-02-09 대체공휴일(설날)", "2027-03-01 3·1절", "2027-05-01 노동절", "2027-05-03 대체공휴일(노동절)",
                "2027-05-05 어린이날", "2027-05-13 부처님 오신 날", "2027-06-06 현충일", "2027-07-17 제헌절",
                "2027-07-19 대체공휴일(제헌절)", "2027-08-15 광복절", "2027-08-16 대체공휴일(광복절)", "2027-09-14 추석 전날",
                "2027-09-15 추석", "2027-09-16 추석 다음 날", "2027-10-03 개천절", "2027-10-04 대체공휴일(개천절)",
                "2027-10-09 한글날", "2027-10-11 대체공휴일(한글날)", "2027-12-25 기독탄신일", "2027-12-27 대체공휴일(기독탄신일)"),
                lines(2027));
    }

    @Test void basisNamesTheKindAndTheOfficialSource() {
        var labour = KoreanPublicHolidays.of(2026).stream().filter(h -> h.name().equals("노동절")).findFirst().orElseThrow();
        assertEquals("공휴일·근로자 유급휴일 · 관공서의 공휴일에 관한 규정(대통령령 제36290호)", labour.basis());
        var election = KoreanPublicHolidays.of(2026).stream().filter(h -> h.name().equals("제9회 전국동시지방선거")).findFirst().orElseThrow();
        assertEquals("선거일 · 2026년 월력요항(우주항공청공고 제2025-0044호)", election.basis());
        assertTrue(KoreanPublicHolidays.of(2027).stream().allMatch(h -> h.basis().endsWith("2027년 월력요항(우주항공청공고 제2026-0078호)")));
        assertTrue(KoreanPublicHolidays.of(2027).stream().allMatch(h -> h.basis().length() <= 500 && h.name().length() <= 120));
    }

    @Test void yearsWithoutAnOfficialListAreEmpty() {
        assertEquals(List.of(), KoreanPublicHolidays.of(2025));
        assertEquals(List.of(), KoreanPublicHolidays.of(2028));
        assertTrue(KoreanPublicHolidays.SMALL_WORKPLACE_NOTICE.startsWith("상시 4인 이하 사업장은"));
    }
}
