package kr.tna.org;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AccountProvisioningTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), ZoneOffset.UTC);

    @Test void windowStartsAtLaterOfNowAndSeoulJoiningMidnight() {
        assertEquals(Instant.parse("2026-12-01T03:00:00Z"), AccountProvisioning.expiresAt(LocalDate.of(2019, 3, 4), clock));
        assertEquals(Instant.parse("2026-12-07T15:00:00Z"), AccountProvisioning.expiresAt(LocalDate.of(2026, 12, 1), clock));
    }
}
