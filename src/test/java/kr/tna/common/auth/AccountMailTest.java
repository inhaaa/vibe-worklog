package kr.tna.common.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;

class AccountMailTest {
    private static final Instant INVITE_EXPIRES = Instant.parse("2026-12-31T15:00:01.321Z");
    private static final Instant RESET_EXPIRES = Instant.parse("2026-12-31T14:59:59.999Z");
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final String token = "T".repeat(43);
    private final String email = "employee@example.test";
    private final URI origin = URI.create("https://hr.example.test/");

    @Test void sendsOneInvitationOnlyToItsMailboxWithASecretFreeLink() {
        try (var smtp = new SmtpAccountMail(sender, origin, "worklog@example.test")) {
            var env = new MockEnvironment().withProperty("tna.mail.mode", "smtp");
            env.setActiveProfiles("local");
            var delivery = new InvitationDelivery(env, Optional.of(smtp));
            delivery.deliver(new InvitationDelivery.Envelope("회사 A", email, token, INVITE_EXPIRES));
            var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(sent.capture());
            var message = sent.getValue();
            assertArrayEquals(new String[]{email}, message.getTo());
            assertNull(message.getCc());
            assertNull(message.getBcc());
            assertEquals("worklog@example.test", message.getFrom());
            assertTrue(message.getSubject().contains("초대"));
            var body = message.getText();
            assertTrue(body.lines().anyMatch("https://hr.example.test/invite/%ED%9A%8C%EC%82%AC%20A"::equals));
            assertTrue(body.contains("회사 코드: 회사 A"));
            assertTrue(body.contains("일회용 코드: " + token));
            assertTrue(body.contains("24시간"));
            assertTrue(body.contains("만료 시각 (서울): 2027-01-01 00:00:01.321"));
            assertEquals(body.indexOf(token), body.lastIndexOf(token));
            assertTrue(delivery.messages().isEmpty());
        }
    }

    @Test void resetProvidesItsOwnFormAndNeverWaitsForTheMailRelay() throws Exception {
        var enteredRelay = new CountDownLatch(1);
        var finishRelay = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredRelay.countDown();
            assertTrue(finishRelay.await(5, TimeUnit.SECONDS));
            throw new MailSendException("synthetic relay unavailable");
        }).when(sender).send(any(SimpleMailMessage.class));
        try (var smtp = new SmtpAccountMail(sender, URI.create("http://127.0.0.1:18080"), "worklog@example.test")) {
            try {
                smtp.deliver(InvitationDelivery.Envelope.reset("COMPANY", email, token, RESET_EXPIRES));
                assertTrue(enteredRelay.await(2, TimeUnit.SECONDS));
                var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
                verify(sender).send(sent.capture());
                assertTrue(sent.getValue().getSubject().contains("비밀번호"));
                String body = sent.getValue().getText();
                assertTrue(body.lines().anyMatch("http://127.0.0.1:18080/password-reset"::equals));
                assertTrue(body.contains("일회용 코드: " + token));
                assertTrue(body.contains("30분"));
                assertTrue(body.contains("만료 시각 (서울): 2026-12-31 23:59:59.999"));
            } finally {
                finishRelay.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings={".", "..", "../운영", "서울/지사", "회사%20;#&?+지점"})
    void companyCodesWithSpecialPathMeaningRoundTripAsOneQueryValue(String company) {
        try (var smtp = new SmtpAccountMail(sender, origin, "worklog@example.test")) {
            smtp.deliver(new InvitationDelivery.Envelope(company, email, token, INVITE_EXPIRES));
            var sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(sent.capture());
            String link = sent.getValue().getText().lines().filter(s -> s.startsWith("https://")).findFirst().orElseThrow();
            URI uri = URI.create(link);
            assertEquals("/invite", uri.getPath());
            assertEquals("hr.example.test", uri.getHost());
            assertNull(uri.getRawFragment());
            assertFalse(uri.getRawQuery().contains("&"));
            assertEquals("companyCode=" + company, URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8));
            assertFalse(link.contains(token));
        }
    }

    @Test void failedInvitationExplainsThatReissueIsAvailableWithoutExposingTheSmtpResponse() {
        doThrow(new MailSendException("synthetic response " + email + " " + token)).when(sender).send(any(SimpleMailMessage.class));
        try (var smtp = new SmtpAccountMail(sender, origin, "worklog@example.test")) {
            var failure = assertThrows(OrgException.class,
                    () -> smtp.deliver(new InvitationDelivery.Envelope("COMPANY", email, token, INVITE_EXPIRES)));
            assertEquals(503, failure.status());
            assertTrue(failure.getMessage().contains("재발급"));
            assertFalse(failure.getMessage().contains(email));
            assertFalse(failure.getMessage().contains(token));
            assertNull(failure.getCause());
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"http://external.example.test", "https://account@hr.example.test",
            "https://hr.example.test/path", "https://hr.example.test/?next=a", "https://hr.example.test/#fragment"})
    void rejectsUntrustedOrAmbiguousPublicOrigin(String value) {
        assertThrows(IllegalArgumentException.class,
                () -> new SmtpAccountMail(sender, URI.create(value), "worklog@example.test"));
    }

    @Test void publicDemoCannotSendEvenWhenSmtpSettingsArePresent() {
        var env = new MockEnvironment().withProperty("tna.mail.mode", "smtp");
        env.setActiveProfiles("public-demo");
        try (var smtp = new SmtpAccountMail(sender, origin, "worklog@example.test")) {
            var delivery = new InvitationDelivery(env, Optional.of(smtp));
            assertFalse(delivery.enabled());
            assertThrows(IllegalStateException.class, () -> delivery.deliver(new InvitationDelivery.Envelope("COMPANY", email, token, INVITE_EXPIRES)));
            verifyNoInteractions(sender);
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"local-part-only", "person@", "@example.test", "A<other@example.test>", "group:one@example.test;", "a@example.test,b@example.test",
            "recipient@example.test\r\nBcc: other@example.test"})
    void refusesHeaderSyntaxOrMoreThanOneMailbox(String value) {
        assertFalse(InvitationDelivery.isSingleMailbox(value));
        assertThrows(IllegalArgumentException.class, () -> new SmtpAccountMail(sender, origin, value));
        try (var smtp = new SmtpAccountMail(sender, origin, "worklog@example.test")) {
            assertThrows(OrgException.class, () -> smtp.deliver(new InvitationDelivery.Envelope("COMPANY", value, token, INVITE_EXPIRES)));
            verifyNoInteractions(sender);
        }
    }
}
