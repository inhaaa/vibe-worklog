package kr.tna.common.auth;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class InvitationDelivery {
    private final boolean local;
    private final SmtpAccountMail smtp;
    private final ArrayDeque<Envelope> messages = new ArrayDeque<>();

    public InvitationDelivery(Environment environment) {
        this(environment, Optional.empty());
    }

    @Autowired
    InvitationDelivery(Environment environment, Optional<SmtpAccountMail> smtp) {
        boolean publicDemo = environment.matchesProfiles("public-demo");
        local = !publicDemo && environment.matchesProfiles("local") && !environment.containsProperty("tna.mail.mode");
        this.smtp = publicDemo ? null : smtp.orElse(null);
    }

    public boolean enabled() { return local || smtp != null; }

    public void deliver(Envelope envelope) {
        if (smtp != null) {
            smtp.deliver(envelope);
            return;
        }
        if (!local) throw new IllegalStateException("Account mail delivery is unavailable");
        synchronized (messages) {
            if (messages.size() == 256) messages.removeFirst();
            messages.addLast(envelope);
        }
    }

    public List<Envelope> messages() {
        synchronized (messages) { return List.copyOf(messages); }
    }

    public static boolean isSingleMailbox(String value) {
        if (value == null || value.length() > 254 || value.codePoints().anyMatch(
                c -> Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c))) return false;
        try {
            var address = new InternetAddress(value, true);
            return !address.isGroup() && address.getPersonal() == null && value.equals(address.getAddress());
        } catch (AddressException invalid) {
            return false;
        }
    }

    public enum Purpose { INVITE, RESET }

    public record Envelope(String companyCode, String email, String token, Purpose purpose, Instant expiresAt) {
        public Envelope(String companyCode, String email, String token, Instant expiresAt) {
            this(companyCode, email, token, Purpose.INVITE, expiresAt);
        }
        static Envelope reset(String companyCode, String email, String token, Instant expiresAt) {
            return new Envelope(companyCode, email, token, Purpose.RESET, expiresAt);
        }
        @Override public String toString() { return "Envelope[redacted]"; }
    }
}
