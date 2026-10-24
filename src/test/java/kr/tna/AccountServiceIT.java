package kr.tna;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import kr.tna.common.auth.AuthStore;
import kr.tna.common.auth.InvitationAcceptance;
import kr.tna.common.auth.InvitationDelivery;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.AccountData.InvitationForm;
import kr.tna.org.AccountData.RolesForm;
import kr.tna.org.AccountService;
import kr.tna.org.AccountAdminMapper;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
class AccountServiceIT {
    private static final String PASSWORD = "Synthetic invited password 43!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    @Autowired AccountService accounts;
    @Autowired InvitationDelivery delivery;
    @Autowired InvitationAcceptance acceptance;
    @Autowired AuthStore auth;
    @Autowired AccountAdminMapper accountMapper;
    @Autowired EmployeeAdminMapper employeeMapper;
    @Autowired OrgCommands commands;
    @Autowired TenantTransactions transactions;
    private JdbcTemplate database;
    private UUID tenant;
    private String company;
    private UUID employee;
    private TnaPrincipal actor;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var fixture = DatabaseFixture.start();
        properties.add("spring.datasource.url", fixture::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", fixture::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void tenantWithAdminAndUninvitedEmployee() {
        database = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        company = ("ACCESS-" + tenant).toUpperCase(Locale.ROOT);
        database.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 계정 테스트')",
                tenant, company);
        UUID adminEmployee = employee("ADMIN");
        UUID account = UUID.randomUUID();
        database.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash)
                VALUES (?,?,?,'admin@example.test',?)
                """, tenant, account, adminEmployee, HASH);
        for (String role : Set.of("EMPLOYEE", "ATTENDANCE_ADMIN")) {
            database.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        }
        actor = new TnaPrincipal(tenant, account, adminEmployee, 1,
                Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.now());
        employee = employee("EMPLOYEE");
    }

    @Test
    void defaultProfileRefusesIssuanceWithoutAnyDatabaseEffect() {
        var unavailable = new InvitationDelivery(new MockEnvironment());
        var guarded = new AccountService(accountMapper, employeeMapper, commands, unavailable, Clock.systemUTC());
        assertFalse(unavailable.enabled());
        assertStatus(503, () -> guarded.invite(actor, employee,
                new InvitationForm("invited@example.test", 0L, "초대", UUID.randomUUID())));
        assertNull(accounts.get(actor, employee));
        assertEquals(0, version());
        assertEquals(0, database.queryForObject("SELECT count(*) FROM tna.account_token WHERE tenant_id=?", Integer.class, tenant));
        assertTrue(unavailable.messages().isEmpty());
    }

    @Test
    void rolledBackInvitationDoesNotDeliverAndCanRetryTheSameCommand() {
        UUID command = UUID.randomUUID();
        var form = new InvitationForm("invited@example.test", 0L, "초대", command);
        assertThrows(IllegalStateException.class, () -> transactions.execute(tenant, () -> {
            accounts.invite(actor, employee, form);
            throw new IllegalStateException("Synthetic outer transaction failure");
        }));
        assertNull(accounts.get(actor, employee));
        assertEquals(0, version());
        assertTrue(messages().isEmpty());
        assertEquals(0, database.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                Integer.class, tenant, command));
        accounts.invite(actor, employee, form);
        assertEquals(1, messages().size());
    }

    @Test
    void invitationCreatesPendingAccountAndCommitsOneRedactedLocalDelivery() {
        assertNull(accounts.get(actor, employee));
        UUID command = UUID.randomUUID();
        var form = new InvitationForm(" Invited@Example.Test ", 0L, "합성 초대", command);
        assertEquals(employee, accounts.invite(actor, employee, form));
        var account = accounts.get(actor, employee);
        assertEquals("invited@example.test", account.email());
        assertFalse(account.active());
        assertFalse(account.credentialSet());
        assertEquals(Set.of("EMPLOYEE"), account.roles());
        var message = message();
        assertTrue(message.token().matches("[A-Za-z0-9_-]{43}"));
        assertFalse(message.toString().contains(message.token()));
        assertEquals(1, version());
        assertEquals(employee, accounts.invite(actor, employee, form));
        assertEquals(1, messages().size());
        assertEquals(1, version());
        assertStatus(409, () -> accounts.invite(actor, employee,
                new InvitationForm("other@example.test", 0L, "합성 초대", command)));
        String audit = database.queryForObject("SELECT before_state::text || after_state::text FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                String.class, tenant, command);
        String tokenHash = database.queryForObject("SELECT token_hash FROM tna.account_token WHERE tenant_id=?", String.class, tenant);
        assertFalse(audit.contains(message.token()));
        assertFalse(audit.contains(tokenHash));
    }

    @Test
    void reissueRevokesOldTokenAndDoesNotChangeEmail() {
        invite();
        var first = message();
        assertStatus(422, () -> accounts.invite(actor, employee,
                new InvitationForm("different@example.test", 1L, "재발급", UUID.randomUUID())));
        accounts.invite(actor, employee, new InvitationForm("invited@example.test", 1L, "재발급", UUID.randomUUID()));
        assertEquals(2, messages().size());
        assertStatus(422, () -> accept(first));
        accept(message());
        assertTrue(accounts.get(actor, employee).active());
        assertStatus(422, () -> accounts.invite(actor, employee,
                new InvitationForm("invited@example.test", 2L, "재발급", UUID.randomUUID())));
        assertEquals(1, database.queryForObject("SELECT count(*) FROM tna.account_token WHERE tenant_id=? AND revoked_at IS NOT NULL", Integer.class, tenant));
    }

    @Test
    void acceptanceConsumesTokenAndEstablishesCredentialsOnlyOnce() {
        invite();
        long before = accounts.get(actor, employee).authVersion();
        accept(message());
        var account = accounts.get(actor, employee);
        assertTrue(account.active());
        assertTrue(account.credentialSet());
        assertEquals(before + 1, account.authVersion());
        String hash = database.queryForObject("SELECT password_hash FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?",
                String.class, tenant, employee);
        assertTrue(PasswordPolicy.passwordEncoder().matches(PASSWORD, hash));
        assertStatus(422, () -> accept(message()));
        assertEquals(1, database.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ACCOUNT_INVITE_ACCEPT'", Integer.class, tenant));
        String audit = database.queryForObject("SELECT before_state::text || after_state::text FROM tna.org_audit_event WHERE tenant_id=? AND action='ACCOUNT_INVITE_ACCEPT'", String.class, tenant);
        assertFalse(audit.contains(PASSWORD));
        assertFalse(audit.contains(message().token()));
        assertFalse(audit.contains(hash));
    }

    @Test
    void acceptanceRejectsTenantEmailPurposeAndVersionMismatchWithoutConsuming() {
        invite();
        var message = message();
        assertStatus(422, () -> acceptance.accept("OTHER-" + company, message.email(), message.token(), PASSWORD, "127.0.0.1"));
        assertStatus(422, () -> acceptance.accept(company, "other@example.test", message.token(), PASSWORD, "127.0.0.1"));
        database.update("UPDATE tna.account_token SET purpose='RESET' WHERE tenant_id=?", tenant);
        assertStatus(422, () -> accept(message));
        database.update("UPDATE tna.account_token SET purpose='INVITE',issued_auth_version=issued_auth_version+1 WHERE tenant_id=?", tenant);
        assertStatus(422, () -> accept(message));
        assertEquals(0, usedTokens());
        assertFalse(accounts.get(actor, employee).active());
    }

    @Test
    void expirationInstantIsRejectedAndEmploymentIsRechecked() {
        invite();
        Instant expires = Instant.parse("2026-10-24T03:00:00Z");
        database.update("UPDATE tna.account_token SET expires_at=? WHERE tenant_id=?", java.sql.Timestamp.from(expires), tenant);
        var fixed = new InvitationAcceptance(DatabaseFixture.start().authDataSource(), PasswordPolicy.passwordEncoder(), Clock.fixed(expires, ZoneOffset.UTC));
        assertStatus(422, () -> fixed.accept(company, message().email(), message().token(), PASSWORD, "127.0.0.1"));
        database.update("UPDATE tna.account_token SET expires_at=CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE tenant_id=?", tenant);
        database.update("UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?", tenant, employee);
        assertStatus(422, () -> accept(message()));
        assertEquals(0, usedTokens());
    }

    @Test
    void concurrentAcceptanceSucceedsOnce() throws Exception {
        invite();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(pool.submit(() -> consume(start)), pool.submit(() -> consume(start)));
            start.countDown();
            int sum = 0;
            for (var result : tasks) sum += result.get(30, TimeUnit.SECONDS);
            assertEquals(1, sum);
        }
        assertEquals(1, usedTokens());
    }

    @Test
    void failedAcceptanceAuditRollsBackCredentialsAndToken() {
        invite();
        var broken = new InvitationAcceptance(failingAuditSource(), PasswordPolicy.passwordEncoder(), Clock.systemUTC());
        assertStatus(503, () -> broken.accept(company, message().email(), message().token(), PASSWORD, "127.0.0.1"));
        assertFalse(accounts.get(actor, employee).credentialSet());
        assertFalse(accounts.get(actor, employee).active());
        assertEquals(0, usedTokens());
        accept(message());
    }

    @Test
    void rolesRequireAcceptedAccountAndRevokeOldSessionsAcrossRemovalAndRestoration() {
        invite();
        assertStatus(422, () -> accounts.roles(actor, employee,
                new RolesForm(Set.of("EMPLOYEE", "APPROVER"), 1L, "역할", UUID.randomUUID())));
        accept(message());
        var initial = accounts.get(actor, employee);
        TnaPrincipal old = new TnaPrincipal(tenant, initial.id(), employee, initial.authVersion(), initial.roles(), Instant.now());
        assertTrue(auth.isCurrent(old));
        accounts.roles(actor, employee, new RolesForm(Set.of("EMPLOYEE", "APPROVER"), 1L, "승인자 부여", UUID.randomUUID()));
        assertFalse(auth.isCurrent(old));
        accounts.roles(actor, employee, new RolesForm(Set.of("EMPLOYEE"), 2L, "승인자 회수", UUID.randomUUID()));
        assertFalse(auth.isCurrent(old));
        assertEquals(Set.of("EMPLOYEE"), accounts.get(actor, employee).roles());
        assertEquals(1, database.queryForObject("SELECT count(*) FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND role='APPROVER' AND NOT active", Integer.class, tenant, initial.id()));
        accounts.roles(actor, employee, new RolesForm(Set.of("EMPLOYEE", "APPROVER"), 3L, "승인자 복구", UUID.randomUUID()));
        assertFalse(auth.isCurrent(old));
    }

    @Test
    void lastAdminRoleCannotBeRemovedUntilAnotherPermanentAcceptedAdminExists() {
        assertStatus(422, () -> accounts.roles(actor, actor.employeeId(),
                new RolesForm(Set.of("EMPLOYEE"), 0L, "담당자 회수", UUID.randomUUID())));
        invite();
        accept(message());
        accounts.roles(actor, employee, new RolesForm(Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), 1L, "다른 담당자", UUID.randomUUID()));
        accounts.roles(actor, actor.employeeId(), new RolesForm(Set.of("EMPLOYEE"), 0L, "담당자 회수", UUID.randomUUID()));
        assertFalse(auth.isCurrent(actor));
        assertStatus(403, () -> accounts.get(actor, employee));
    }

    @Test
    void untrustedInputsStaleVersionAndForeignTargetsAreRejected() {
        assertStatus(422, () -> accounts.invite(actor, employee, new InvitationForm("bad email", 0L, "초대", UUID.randomUUID())));
        assertStatus(409, () -> accounts.invite(actor, employee, new InvitationForm("invited@example.test", 4L, "초대", UUID.randomUUID())));
        assertStatus(404, () -> accounts.invite(actor, UUID.randomUUID(), new InvitationForm("invited@example.test", 0L, "초대", UUID.randomUUID())));
        assertStatus(404, () -> accounts.get(actor, UUID.randomUUID()));
        assertStatus(422, () -> accounts.roles(actor, actor.employeeId(), new RolesForm(Set.of("EMPLOYEE", "PLATFORM_OPERATOR"), 0L, "역할", UUID.randomUUID())));
        assertStatus(422, () -> accounts.roles(actor, actor.employeeId(), new RolesForm(Set.of("APPROVER"), 0L, "역할", UUID.randomUUID())));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void preAuthenticationFailuresLogOnlyFixedEventsWithoutCredentials(CapturedOutput output) {
        invite();
        String privateEmail = "private-capture@example.test";
        String privateToken = "private-invalid-invitation-token";
        for (int attempt = 0; attempt < 20; attempt++) {
            assertStatus(422, () -> acceptance.accept(company, privateEmail, privateToken, PASSWORD, company));
        }
        assertStatus(429, () -> acceptance.accept(company, privateEmail, privateToken, PASSWORD, company));
        var broken = new InvitationAcceptance(failingAuditSource(), PasswordPolicy.passwordEncoder(), Clock.systemUTC());
        assertStatus(503, () -> broken.accept(company, message().email(), message().token(), PASSWORD, company));
        assertTrue(output.getAll().contains("event=invitation_accept outcome=rejected"));
        assertTrue(output.getAll().contains("event=invitation_accept outcome=rate_limited"));
        assertTrue(output.getAll().contains("event=invitation_accept outcome=storage_unavailable"));
        String tokenHash = database.queryForObject("SELECT token_hash FROM tna.account_token WHERE tenant_id=?", String.class, tenant);
        for (String secret : List.of(privateEmail, privateToken, PASSWORD, company, message().email(), message().token(), tokenHash, HASH)) {
            assertFalse(output.getAll().contains(secret), "보안 이벤트 로그에 자격 정보가 포함되어서는 안 됩니다.");
        }
        assertEquals(0, usedTokens());
        assertFalse(accounts.get(actor, employee).credentialSet());
    }

    @Test
    void malformedSecretAndRepeatedPublicAttemptsAreBoundedWithoutEchoingValues() {
        for (int i = 0; i < 20; i++) {
            assertStatus(422, () -> acceptance.accept(company, "limit@example.test", "secret-invalid-token", PASSWORD, company));
        }
        var error = assertThrows(OrgException.class,
                () -> acceptance.accept(company, "limit@example.test", "secret-invalid-token", PASSWORD, company));
        assertEquals(429, error.status());
        assertFalse(error.getMessage().contains("secret-invalid-token"));
    }

    private void invite() {
        accounts.invite(actor, employee, new InvitationForm("invited@example.test", 0L, "초대", UUID.randomUUID()));
    }

    private void accept(InvitationDelivery.Envelope envelope) {
        acceptance.accept(envelope.companyCode(), envelope.email(), envelope.token(), PASSWORD, "127.0.0.1");
    }

    private List<InvitationDelivery.Envelope> messages() {
        return delivery.messages().stream().filter(item -> item.companyCode().equals(company)).toList();
    }

    private InvitationDelivery.Envelope message() { return messages().getLast(); }
    private long version() { return database.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?", Long.class, tenant, employee); }
    private int usedTokens() { return database.queryForObject("SELECT count(*) FROM tna.account_token WHERE tenant_id=? AND used_at IS NOT NULL", Integer.class, tenant); }

    private int consume(CountDownLatch start) throws Exception {
        start.await();
        try { accept(message()); return 1; }
        catch (OrgException error) { assertEquals(422, error.status()); return 0; }
    }

    private UUID employee(String number) {
        UUID id = UUID.randomUUID();
        database.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 직원',?)",
                tenant, id, number, LocalDate.now(ZoneId.of("Asia/Seoul")).minusYears(1));
        return id;
    }

    private DataSource failingAuditSource() {
        DataSource delegate = DatabaseFixture.start().authDataSource();
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
            @Override public Connection getConnection(String username, String password) throws SQLException { return wrap(delegate.getConnection(username, password)); }
            private Connection wrap(Connection connection) {
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                            && sql.contains("INSERT INTO tna.org_audit_event")) throw new SQLException("Synthetic audit failure");
                    try { return method.invoke(connection, args); }
                    catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
            }
        };
    }

    private void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(OrgException.class, action::run).status());
    }
}
