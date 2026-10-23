package kr.tna;

import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.LoginRateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test
    void acceptsTwelveTo128UnicodeCodePointsWithoutTrimming() {
        assertTrue(PasswordPolicy.isValid("가".repeat(12)));
        assertTrue(PasswordPolicy.isValid("😀".repeat(128)));
        assertTrue(PasswordPolicy.isValid(" ".repeat(12)));
        assertFalse(PasswordPolicy.isValid("a".repeat(11)));
        assertFalse(PasswordPolicy.isValid("😀".repeat(129)));
        assertFalse(PasswordPolicy.isValid(null));
        assertFalse(PasswordPolicy.isValid("a".repeat(12) + '\uD800'));
    }

    @Test
    void hashesTheEntireLongPasswordWithAnAlgorithmIdentifier() {
        var encoder = PasswordPolicy.passwordEncoder();
        String password = "긴 암호 😀 ".repeat(12);
        String encoded = encoder.encode(password);
        assertTrue(encoded.startsWith("{pbkdf2-v1}"));
        assertTrue(encoder.matches(password, encoded));
        assertFalse(encoder.matches(password.substring(0, password.length() - 1) + "x", encoded));
        assertNotEquals(encoded, encoder.encode(password));
    }

    @Test
    void loginRateLimitsExpireAndKeyStorageIsBounded() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-10-24T00:00:00Z"));
        var clock = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() { return now.get(); }
        };
        var limiter = new LoginRateLimiter(clock);
        for (int attempt = 0; attempt < 20; attempt++) {
            assertTrue(limiter.tryAcquire("192.0.2.1", "COMPANY", "person@example.test"));
        }
        assertFalse(limiter.tryAcquire("192.0.2.1", "company", "PERSON@example.test"));
        for (int key = 1; key < 4096; key++) {
            assertTrue(limiter.tryAcquire("192.0.2.1", "COMPANY", "person" + key + "@example.test"));
        }
        assertFalse(limiter.tryAcquire("192.0.2.2", "COMPANY", "overflow@example.test"));
        now.set(now.get().plusSeconds(301));
        assertTrue(limiter.tryAcquire("192.0.2.2", "COMPANY", "overflow@example.test"));
    }
}
