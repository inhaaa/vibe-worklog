package kr.tna.org;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AccountStatusTest {
    private static final Instant NOW = Instant.parse("2026-11-24T00:00:00Z");

    @Test void ordersConditionsFromMissingAccountToPendingActivation() {
        assertEquals(AccountStatus.NONE, AccountStatus.of(false, false, null, 0, true, NOW));
        assertEquals(AccountStatus.ACTIVE, AccountStatus.of(true, true, null, 0, true, NOW));
        assertEquals(AccountStatus.PREVIOUS_INVITATION, AccountStatus.of(true, false, null, 0, true, NOW));
        assertEquals(AccountStatus.BIRTH_DATE_REQUIRED, AccountStatus.of(true, false, NOW.plusSeconds(60), 5, false, NOW));
        assertEquals(AccountStatus.LOCKED, AccountStatus.of(true, false, NOW.plusSeconds(60), 5, true, NOW));
        assertEquals(AccountStatus.EXPIRED, AccountStatus.of(true, true, NOW, 0, true, NOW));
        assertEquals(AccountStatus.PENDING, AccountStatus.of(true, true, NOW.plusSeconds(1), 4, true, NOW));
        assertEquals(AccountStatus.LOCKED, AccountStatus.of(true, false, NOW, 5, true, NOW));
        assertEquals(AccountStatus.BIRTH_DATE_REQUIRED, AccountStatus.of(true, false, NOW, 0, false, NOW));
    }

    @Test void labelsAreWhatTheAdministratorReads() {
        assertEquals("최초 접속 대기", AccountStatus.PENDING.label());
        assertEquals("잠김(5회 실패)", AccountStatus.LOCKED.label());
    }
}
