package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import java.util.TimeZone;
import kr.tna.common.web.SeoulTimeDisplay;
import org.junit.jupiter.api.Test;

class SeoulTimeDisplayTest {
    private final SeoulTimeDisplay display = new SeoulTimeDisplay();

    @Test void instantUsesSeoulAcrossMidnightAndYearBoundariesWithMilliseconds() {
        assertEquals("2027-01-01 00:00:00.123", display.instant(Instant.parse("2026-12-31T15:00:00.123Z")));
        assertEquals("2026-12-31 23:59:59.999", display.instant(Instant.parse("2026-12-31T14:59:59.999999999Z")));
        assertEquals("2026-01-01 09:00:00.000", display.instant(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test void dateAndTimeHaveStableIsoAndMillisecondDisplays() {
        assertEquals("2026-10-24", display.date(LocalDate.of(2026, 10, 24)));
        assertEquals("09:05:00.007", display.time(LocalTime.of(9, 5, 0, 7_000_000)));
        assertEquals("00:00:00.000", display.time(LocalTime.MIDNIGHT));
    }

    @Test void displayDoesNotDependOnHostTimezoneOrLocale() {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"));
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals("2027-01-01 00:00:00.123", display.instant(Instant.parse("2026-12-31T15:00:00.123Z")));
            assertEquals("2026-10-24", display.date(LocalDate.of(2026, 10, 24)));
            assertEquals("09:05:00.007", display.time(LocalTime.of(9, 5, 0, 7_000_000)));
        } finally {
            TimeZone.setDefault(originalZone);
            Locale.setDefault(originalLocale);
        }
    }

    @Test void absentValuesAreExplicitlyUnknownAndNeverInventedAsMidnight() {
        assertEquals("미확인", display.instant(null));
        assertEquals("미확인", display.date(null));
        assertEquals("미확인", display.time(null));
        assertEquals("미확인", display.intervals(null));
    }

    @Test void intervalsUseFullSeoulDatesAndWhitelistOnlyStartAndEndTimes() {
        String data = """
                [{"startAt":"2026-12-31T14:30:00.123Z","endAt":"2026-12-31T15:00:00.456Z","secret":"DO_NOT_REFLECT"},
                 {"startAt":"2026-12-31T16:00:00Z","endAt":"2026-12-31T16:15:00Z"}]
                """;
        assertEquals("2026-12-31 23:30:00.123 ~ 2027-01-01 00:00:00.456; 2027-01-01 01:00:00.000 ~ 2027-01-01 01:15:00.000", display.intervals(data));
        assertFalse(display.intervals(data).contains("DO_NOT_REFLECT"));
    }

    @Test void explicitEmptyIntervalsAreDistinctFromUnknownOrMalformedEvidence() {
        assertEquals("입력된 휴게 없음", display.intervals("[]"));
        for (String invalid : new String[]{"", "PRIVATE_SOURCE", "null", "{}", "[1]", "[{}]",
                "[{\"startAt\":null,\"endAt\":\"2026-10-24T00:00:00Z\"}]",
                "[{\"startAt\":3,\"endAt\":\"2026-10-24T00:00:00Z\"}]",
                "[{\"startAt\":\"PRIVATE_SOURCE\",\"endAt\":\"2026-10-24T00:00:00Z\"}]",
                "[{\"startAt\":\"2026-10-24T01:00:00Z\",\"endAt\":\"2026-10-24T00:00:00Z\"}]",
                "[{\"startAt\":\"2026-10-24T00:00:00Z\",\"endAt\":\"2026-10-24T00:00:00Z\"}]"}) {
            assertEquals("확인 필요", display.intervals(invalid));
        }
    }
}
