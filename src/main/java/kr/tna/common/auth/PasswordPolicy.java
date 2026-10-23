package kr.tna.common.auth;

import java.util.Map;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

public final class PasswordPolicy {
    private PasswordPolicy() { }

    public static boolean isValid(String password) {
        if (password == null || password.length() > 256) {
            return false;
        }
        int points = 0;
        for (int offset = 0; offset < password.length(); offset++, points++) {
            char character = password.charAt(offset);
            if (Character.isHighSurrogate(character)) {
                if (++offset >= password.length() || !Character.isLowSurrogate(password.charAt(offset))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(character)) {
                return false;
            }
        }
        return points >= 12 && points <= 128;
    }

    public static PasswordEncoder passwordEncoder() {
        // Spring's 5.8 preset: 16-byte salt, 310,000 iterations, PBKDF2-HMAC-SHA256.
        return new DelegatingPasswordEncoder("pbkdf2-v1",
                Map.of("pbkdf2-v1", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}
