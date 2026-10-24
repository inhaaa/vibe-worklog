package kr.tna.common.auth;

import java.util.ArrayDeque;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public final class InvitationDelivery {
    private final boolean local;
    private final ArrayDeque<Envelope> messages = new ArrayDeque<>();

    public InvitationDelivery(Environment environment) {
        local = environment.acceptsProfiles(Profiles.of("local"));
    }

    public boolean enabled() { return local; }

    public synchronized void deliver(Envelope envelope) {
        if (!local) throw new IllegalStateException("Invitation delivery is unavailable");
        // Keep this process-local development mailbox bounded; no external transport is configured.
        if (messages.size() == 256) messages.removeFirst();
        messages.addLast(envelope);
    }

    public synchronized List<Envelope> messages() { return List.copyOf(messages); }
    public record Envelope(String companyCode, String email, String token) {
        @Override public String toString() { return "Envelope[redacted]"; }
    }
}
