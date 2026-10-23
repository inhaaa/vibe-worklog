package kr.tna.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** Bounded, process-local development guard; a shared limiter is required before scaling. */
public final class LoginRateLimiter {
    private static final int MAX_KEYS = 4096;
    private static final int MAX_ATTEMPTS = 20;
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(String address, String company, String email) {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        String key = digest(address, company, email);
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= MAX_KEYS) {
                return false;
            }
            windows.put(key, new Window(now.plusSeconds(300), 1));
            return true;
        }
        if (window.attempts() >= MAX_ATTEMPTS) {
            return false;
        }
        windows.put(key, new Window(window.expiresAt(), window.attempts() + 1));
        return true;
    }

    private String digest(String address, String company, String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{address, company.toLowerCase(Locale.ROOT), email.toLowerCase(Locale.ROOT)}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 is unavailable", exception);
        }
    }

    private record Window(Instant expiresAt, int attempts) { }
}
