package kr.tna.worktime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class WorktimeDurationTest {
    @Test void durationShowsHoursMinutesSecondsAndDashWhenUnknown() {
        assertEquals("8:09:58", WorktimeController.duration(29_398_999L));
        assertEquals("0:00:00", WorktimeController.duration(0L));
        assertEquals("27:00:00", WorktimeController.duration(97_200_000L));
        assertEquals("-", WorktimeController.duration(null));
    }
}
