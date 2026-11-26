package kr.tna.notification;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import kr.tna.DatabaseFixture;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class NotificationMailerIT {
    @Autowired NotificationMapper mapper;
    @Autowired TenantTransactions transactions;
    @Autowired @Qualifier("dataSource") DataSource dataSource;
    @Autowired @Qualifier("authDataSource") DataSource authDataSource;
    @Autowired NotificationMail defaultMail;
    private final DatabaseFixture f = DatabaseFixture.start();
    private final JdbcTemplate admin = new JdbcTemplate(f.adminDataSource());
    private UUID tenant, employee, approver, account, approverAccount, request;
    private static final URI ORIGIN = URI.create("https://worklog.example");

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    // 시험 전용: 실패 횟수를 정할 수 있는 가짜 SMTP
    static final class FakeSender extends JavaMailSenderImpl {
        final List<SimpleMailMessage> sent = new java.util.ArrayList<>();
        final AtomicInteger failures = new AtomicInteger();
        @Override public void send(SimpleMailMessage message) {
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new MailSendException("synthetic relay failure");
            sent.add(message);
        }
    }

    private NotificationMailer mailer(NotificationMail mail) {
        return new NotificationMailer(dataSource, authDataSource, transactions, mapper, mail);
    }
    private NotificationMail smtp(FakeSender sender) {
        return new NotificationMail(NotificationMail.Mode.SMTP, sender, "worklog@example.test", ORIGIN);
    }

    @BeforeEach void seed() {
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); approver = UUID.randomUUID();
        account = UUID.randomUUID(); approverAccount = UUID.randomUUID(); request = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 알림 고객사')", tenant, "MAILER-" + tenant);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'N1','합성 직원',DATE '2026-01-01')", tenant, employee);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'N2','합성 승인자',DATE '2026-01-01')", tenant, approver);
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-hash')", tenant, account, employee, account + "@example.test");
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'approver@example.test','synthetic-hash')", tenant, approverAccount, approver);
        admin.update("""
            INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
            VALUES (?,?,?,?,DATE '2026-01-01','합성',?,?)""", tenant, assignment, employee, approver, account, account);
        admin.update("""
            INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,current_approver_id,
              approver_assignment_id,approver_assignment_version,reason,from_date,to_date,client_command_id,attendance_type_version_id)
            SELECT ?,?,?,?,'ATTENDANCE_CORRECTION',?,?,0,'합성',DATE '2026-11-26',DATE '2026-11-26',?,v.id
              FROM tna.attendance_type t JOIN tna.attendance_type_version v ON v.tenant_id=t.tenant_id AND v.type_id=t.id AND v.revision=1
             WHERE t.tenant_id=? AND t.system_kind='ATTENDANCE_CORRECTION'""",
            tenant, request, employee, account, approver, assignment, UUID.randomUUID(), tenant);
    }

    private UUID insertNotification(String kind, String eventKind, String reason) { return insertNotificationTo(approver, kind, eventKind, reason); }
    private UUID insertNotificationTo(UUID recipient, String kind, String eventKind, String reason) {
        UUID event = UUID.randomUUID(), id = UUID.randomUUID();
        admin.update("INSERT INTO tna.approval_event(tenant_id,id,request_id,event_kind,actor_account_id,reason) VALUES (?,?,?,?,?,?)",
                tenant, event, request, eventKind, account, reason);
        admin.update("INSERT INTO tna.notification(tenant_id,id,recipient_employee_id,event_id,request_id,kind) VALUES (?,?,?,?,?,?)",
                tenant, id, recipient, event, request, kind);
        return id;
    }
    private String state(UUID id) {
        return admin.queryForObject("SELECT mail_state FROM tna.notification WHERE tenant_id=? AND id=?", String.class, tenant, id);
    }
    private int attempts(UUID id) {
        return admin.queryForObject("SELECT mail_attempts FROM tna.notification WHERE tenant_id=? AND id=?", Integer.class, tenant, id);
    }

    @Test void defaultTestConfigResolvesToOff() { assertFalse(defaultMail.enabled()); }

    @Test void sendsPendingMailOnceAndMarksItSent() {
        UUID id = insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성 신청");
        var sender = new FakeSender();
        var mail = smtp(sender);
        assertTrue(mailer(mail).runOnce(List.of(tenant)));
        assertEquals(1, sender.sent.size());
        assertEquals("approver@example.test", sender.sent.get(0).getTo()[0]);
        assertEquals("worklog@example.test", sender.sent.get(0).getFrom());
        assertTrue(sender.sent.get(0).getSubject().startsWith("[WorkLog] 결재 요청 · 합성 직원"));
        assertTrue(sender.sent.get(0).getText().contains("https://worklog.example/requests/" + request));
        assertFalse(sender.sent.get(0).getText().contains("합성 신청"), "결재 요청 메일에 이력 사유를 넣지 않는다");
        assertEquals("SENT", state(id));
        mailer(mail).runOnce(List.of(tenant));
        assertEquals(1, sender.sent.size(), "보낸 알림은 다시 보내지 않는다");
    }

    @Test void rejectionMailCarriesTheReason() {
        UUID id = insertNotificationTo(employee, "REJECTED", "REJECTED", "일정 중복");
        admin.update("UPDATE tna.tenant_account SET email='worker@example.test' WHERE tenant_id=? AND id=?", tenant, account);
        var sender = new FakeSender();
        mailer(smtp(sender)).runOnce(List.of(tenant));
        assertTrue(sender.sent.get(0).getText().contains("반려 사유: 일정 중복"));
        assertEquals("SENT", state(id));
    }

    @Test void missingEmailInactiveAccountOrDisabledMailIsSkipped() {
        admin.update("UPDATE tna.tenant_account SET active=false WHERE tenant_id=? AND id=?", tenant, approverAccount);
        UUID inactive = insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성");
        var sender = new FakeSender();
        mailer(smtp(sender)).runOnce(List.of(tenant));
        assertEquals("SKIPPED", state(inactive));
        assertTrue(sender.sent.isEmpty());
        admin.update("UPDATE tna.tenant_account SET active=true WHERE tenant_id=? AND id=?", tenant, approverAccount);
        UUID off = insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성");
        mailer(new NotificationMail(NotificationMail.Mode.OFF, null, null, ORIGIN)).runOnce(List.of(tenant));
        assertEquals("SKIPPED", state(off));
    }

    @Test void failuresRetryThenStopAtThree() {
        UUID id = insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성");
        var sender = new FakeSender();
        sender.failures.set(5);
        var mailer = mailer(smtp(sender));
        mailer.runOnce(List.of(tenant));
        assertEquals("PENDING", state(id));
        assertEquals(1, attempts(id));
        assertTrue(admin.queryForObject("SELECT mail_next_at > CURRENT_TIMESTAMP + interval '50 seconds' FROM tna.notification WHERE tenant_id=? AND id=?", Boolean.class, tenant, id));
        mailer.runOnce(List.of(tenant));
        assertEquals(1, attempts(id), "다음 시도 시각 전에는 다시 보내지 않는다");
        for (int i = 0; i < 2; i++) {
            admin.update("UPDATE tna.notification SET mail_next_at=CURRENT_TIMESTAMP - interval '1 second' WHERE tenant_id=? AND id=?", tenant, id);
            mailer.runOnce(List.of(tenant));
        }
        assertEquals("FAILED", state(id));
        assertEquals(3, attempts(id));
        assertTrue(sender.sent.isEmpty());
    }

    @Test void onlyOneRunHoldsTheLock() throws Exception {
        insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성");
        try (var holder = f.appDataSource().getConnection(); var st = holder.createStatement()) {
            st.execute("SELECT pg_advisory_lock(" + NotificationMailer.LOCK_KEY + ")");
            var sender = new FakeSender();
            assertFalse(mailer(smtp(sender)).runOnce(List.of(tenant)));
            assertTrue(sender.sent.isEmpty());
            st.execute("SELECT pg_advisory_unlock(" + NotificationMailer.LOCK_KEY + ")");
        }
    }

    @Test void localModeKeepsMessagesInMemory() {
        insertNotification("APPROVAL_REQUESTED", "SUBMITTED", "합성");
        var mail = new NotificationMail(NotificationMail.Mode.LOCAL, null, null, URI.create("http://127.0.0.1:18080"));
        mailer(mail).runOnce(List.of(tenant));
        assertEquals(1, mail.messages().size());
        assertEquals("approver@example.test", mail.messages().get(0).to());
    }
}
