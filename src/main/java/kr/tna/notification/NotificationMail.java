package kr.tna.notification;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.List;
import kr.tna.common.auth.InvitationDelivery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 결재 알림 메일 전달 방식: 계정 메일과 같은 설정(ADR-0007)을 따른다. public-demo는 보내지 않는다. */
@Component
public class NotificationMail {
    public enum Mode { SMTP, LOCAL, OFF }
    public record Message(String to, String subject, String body) {
        @Override public String toString() { return "NotificationMail.Message[redacted]"; }
    }

    private final Mode mode;
    private final JavaMailSender sender;
    private final String from;
    private final URI origin;
    private final ArrayDeque<Message> messages = new ArrayDeque<>();

    @Autowired
    NotificationMail(Environment env, ObjectProvider<JavaMailSender> sender, @Value("${tna.mail.mode:}") String mode,
                     @Value("${tna.mail.from:}") String from, @Value("${tna.mail.public-origin:http://127.0.0.1:18080}") URI origin) {
        this(env.matchesProfiles("public-demo") ? Mode.OFF : "smtp".equals(mode) ? Mode.SMTP
                : mode.isEmpty() && env.matchesProfiles("local") ? Mode.LOCAL : Mode.OFF,
                sender.getIfAvailable(), from, origin);
    }

    public NotificationMail(Mode mode, JavaMailSender sender, String from, URI origin) {
        if (mode == Mode.SMTP && (sender == null || !InvitationDelivery.isSingleMailbox(from)))
            throw new IllegalArgumentException("SMTP notification mail needs a mail sender and a single sender mailbox");
        this.mode = mode; this.sender = sender; this.from = from; this.origin = origin;
    }

    public boolean enabled() { return mode != Mode.OFF; }
    public URI origin() { return origin; }

    /** @throws org.springframework.mail.MailException 전달을 확인하지 못했을 때 */
    public void send(Message message) {
        switch (mode) {
            case SMTP -> {
                var mail = new SimpleMailMessage();
                mail.setTo(message.to()); mail.setFrom(from);
                mail.setSubject(message.subject()); mail.setText(message.body());
                sender.send(mail);
            }
            case LOCAL -> { synchronized (messages) { if (messages.size() == 256) messages.removeFirst(); messages.addLast(message); } }
            case OFF -> throw new IllegalStateException("Notification mail is disabled");
        }
    }

    public List<Message> messages() { synchronized (messages) { return List.copyOf(messages); } }
}
