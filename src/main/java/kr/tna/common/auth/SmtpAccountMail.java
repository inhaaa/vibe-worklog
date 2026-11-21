package kr.tna.common.auth;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import kr.tna.org.OrgException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Profile("!public-demo")
@ConditionalOnProperty(name = "tna.mail.mode", havingValue = "smtp")
final class SmtpAccountMail implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SmtpAccountMail.class);
    private static final DateTimeFormatter SEOUL_EXPIRY = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.of("Asia/Seoul"));
    private static final Pattern INVITE_PATH_CODE = Pattern.compile("[\\p{L}\\p{N} _.\\-]+");
    private final JavaMailSender sender;
    private final URI origin;
    private final String from;
    // The public recovery response must not depend on mailbox existence or on waiting for a relay.
    private final ThreadPoolExecutor resets = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), Thread.ofPlatform().daemon().name("account-reset-mail-", 1).factory());

    SmtpAccountMail(JavaMailSender sender, @Value("${tna.mail.public-origin}") URI origin,
                    @Value("${tna.mail.from}") String from) {
        if (!trustedOrigin(origin) || !InvitationDelivery.isSingleMailbox(from)) {
            throw new IllegalArgumentException("A valid account mail origin and sender are required");
        }
        this.sender = sender;
        this.origin = origin;
        this.from = from;
    }

    void deliver(InvitationDelivery.Envelope envelope) {
        boolean reset = envelope.purpose() == InvitationDelivery.Purpose.RESET;
        if (reset) {
            try {
                resets.execute(() -> send(envelope));
            } catch (RejectedExecutionException unavailable) {
                LOG.warn("event=account_mail purpose=RESET outcome=queue_unavailable");
            }
        } else if (!send(envelope)) {
            throw new OrgException(503, "초대가 등록되었지만 메일 전달을 확인하지 못했습니다. 최신 목록에서 초대 대기 계정을 확인하고 재발급해 주세요.");
        }
    }

    private boolean send(InvitationDelivery.Envelope envelope) {
        if (!InvitationDelivery.isSingleMailbox(envelope.email())) {
            LOG.warn("event=account_mail purpose={} outcome=invalid_mailbox", envelope.purpose());
            return false;
        }
        var reset = envelope.purpose() == InvitationDelivery.Purpose.RESET;
        String form = reset ? origin.resolve("/password-reset").toString() : invitationForm(envelope.companyCode());
        var message = new SimpleMailMessage();
        message.setTo(envelope.email());
        message.setFrom(from);
        message.setSubject(reset ? "WorkLog 비밀번호 재설정" : "WorkLog 계정 초대");
        message.setText("""
                %s

                회사 코드: %s
                일회용 코드: %s
                유효 시간: 발급 후 %s
                만료 시각 (서울): %s

                %s
                같은 요청의 메일을 여러 번 받았다면 만료 시각이 가장 늦은 메일의 코드를 입력해 주세요.
                """.formatted(reset ? "아래 주소에서 새 비밀번호를 설정하세요." : "아래 주소에서 비밀번호를 설정하고 WorkLog 계정을 활성화하세요.",
                envelope.companyCode(), envelope.token(), reset ? "30분" : "24시간", SEOUL_EXPIRY.format(envelope.expiresAt()), form));
        try {
            sender.send(message);
            return true;
        } catch (MailException failed) {
            // Provider exceptions may include the recipient, SMTP dialog or entire message.
            LOG.warn("event=account_mail purpose={} outcome=relay_unconfirmed", envelope.purpose());
            return false;
        }
    }

    private String invitationForm(String company) {
        var link = UriComponentsBuilder.fromUri(origin).replacePath("/invite");
        if (INVITE_PATH_CODE.matcher(company).matches() && !company.equals(".") && !company.equals("..")) {
            link.pathSegment("{company}");
        } else {
            link.queryParam("companyCode", "{company}");
        }
        return link.encode().buildAndExpand(company).toUriString();
    }

    private static boolean trustedOrigin(URI uri) {
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !Set.of("", "/").contains(uri.getRawPath())) return false;
        if ("https".equalsIgnoreCase(uri.getScheme())) return true;
        return "http".equalsIgnoreCase(uri.getScheme()) && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
    }

    @Override @PreDestroy
    public void close() {
        resets.shutdown();
    }
}
