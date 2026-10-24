package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class OrgCommandsIT {
    @Autowired OrgCommands commands;
    @Autowired @Qualifier("dataSource") DataSource applicationDataSource;
    private JdbcTemplate admin;
    private JdbcTemplate application;
    private TnaPrincipal actor;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var database = DatabaseFixture.start();
        properties.add("spring.datasource.url", database::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", database::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void isolatedAdministrator() {
        admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        application = new JdbcTemplate(applicationDataSource);
        actor = tenantAdministrator();
    }

    @Test
    void failedAuditInsertRollsBackTheSuccessfulBusinessMutation() {
        UUID organization = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        var error = assertThrows(OrgException.class, () -> commands.execute(actor, command, "X".repeat(81),
                "ORGANIZATION", OrgCommands.fingerprint(organization), "감사 실패 롤백 검증", () -> {
                    insertOrganization(actor, organization);
                    return new OrgCommands.Change(organization, null, "합성 조직 생성");
                }));
        assertEquals(409, error.status());
        assertSafeError(error);
        assertEquals(0, organizationCount(actor, organization));
        assertEquals(0, auditCount(actor, command));
    }

    @Test
    void cachedIdentityCannotReadWriteOrReplayAfterCurrentAuthorizationIsRevoked() {
        List<Consumer<TnaPrincipal>> revocations = List.of(
                principal -> admin.update("""
                        UPDATE tna.role_grant SET role = 'APPROVER'
                        WHERE tenant_id = ? AND account_id = ? AND role = 'ATTENDANCE_ADMIN'
                        """, principal.tenantId(), principal.accountId()),
                principal -> admin.update("""
                        UPDATE tna.tenant_account SET auth_version = auth_version + 1
                        WHERE tenant_id = ? AND id = ?
                        """, principal.tenantId(), principal.accountId()),
                principal -> admin.update("""
                        UPDATE tna.tenant_account SET active = false WHERE tenant_id = ? AND id = ?
                        """, principal.tenantId(), principal.accountId()),
                principal -> admin.update("""
                        UPDATE tna.employee SET active = false WHERE tenant_id = ? AND id = ?
                        """, principal.tenantId(), principal.employeeId()),
                principal -> admin.update("""
                        UPDATE tna.employee SET left_on = ? WHERE tenant_id = ? AND id = ?
                        """, commands.today().minusDays(1), principal.tenantId(), principal.employeeId()),
                principal -> admin.update("""
                        UPDATE platform.tenant_registry SET active = false WHERE id = ?
                        """, principal.tenantId()));
        for (var revoke : revocations) {
            TnaPrincipal cached = tenantAdministrator();
            UUID existingCommand = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            String hash = OrgCommands.fingerprint(target);
            commands.execute(cached, existingCommand, "COMMAND_TEST", "ORGANIZATION", hash,
                    "권한 회수 전 정상 처리", () -> new OrgCommands.Change(target, null, "기존 결과"));
            revoke.accept(cached);
            assertStatus(403, () -> commands.read(cached, () -> {
                throw new AssertionError("권한 회수 후 조회가 실행되었습니다.");
            }));
            UUID newCommand = UUID.randomUUID();
            for (UUID command : List.of(existingCommand, newCommand)) {
                assertStatus(403, () -> commands.execute(cached, command, "COMMAND_TEST", "ORGANIZATION", hash,
                        "권한 회수 전 정상 처리", () -> {
                            throw new AssertionError("권한 회수 후 변경이 실행되었습니다.");
                        }));
            }
            assertEquals(1, auditCount(cached, existingCommand));
            assertEquals(0, auditCount(cached, newCommand));
        }
    }

    @Test
    void anotherAdministratorCannotReuseTheSameTenantCommand() {
        TnaPrincipal second = administrator(actor.tenantId());
        UUID command = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        String hash = OrgCommands.fingerprint(organization);
        assertEquals(organization, commands.execute(actor, command, "COMMAND_TEST", "ORGANIZATION", hash,
                "주체 별 재시도 검증", () -> {
                    insertOrganization(actor, organization);
                    return new OrgCommands.Change(organization, null, "합성 조직 생성");
                }));
        var error = assertThrows(OrgException.class, () -> commands.execute(second, command, "COMMAND_TEST",
                "ORGANIZATION", hash, "주체 별 재시도 검증", () -> {
                    throw new AssertionError("다른 주체의 명령이 재실행되었습니다.");
                }));
        assertEquals(409, error.status());
        assertSafeError(error);
        assertEquals(1, organizationCount(actor, organization));
        assertEquals(1, auditCount(actor, command));
        assertEquals(actor.accountId(), admin.queryForObject("""
                SELECT actor_account_id FROM tna.org_audit_event WHERE tenant_id = ? AND command_id = ?
                """, UUID.class, actor.tenantId(), command));
    }

    @Test
    void beforeAndAfterSnapshotsPreserveIsoDatesUnicodeAndNull() {
        UUID command = UUID.randomUUID();
        LocalDate joined = commands.today().minusYears(1);
        LocalDate lastDay = commands.today().plusDays(10);
        var before = new Employee(actor.employeeId(), "E-" + actor.employeeId(), "변경 전", joined, null, true, 0);
        var after = new Employee(actor.employeeId(), before.employeeNo(), "합성 직원 😀", joined, lastDay, true, 1);
        commands.execute(actor, command, "EMPLOYEE_UPDATE", "EMPLOYEE", OrgCommands.fingerprint(after),
                "날짜 JSON 검증", () -> {
                    application.update("""
                            UPDATE tna.employee SET display_name = ?, left_on = ?, version = version + 1
                            WHERE tenant_id = ? AND id = ?
                            """, after.displayName(), lastDay, actor.tenantId(), actor.employeeId());
                    return new OrgCommands.Change(actor.employeeId(), before, after);
                });
        var snapshot = admin.queryForMap("""
                SELECT before_state ->> 'joinedOn' AS joined_before,
                       jsonb_typeof(before_state -> 'leftOn') AS left_before_type,
                       after_state ->> 'joinedOn' AS joined_after,
                       after_state ->> 'leftOn' AS left_after,
                       after_state ->> 'displayName' AS display_name_after,
                       after_state ->> 'version' AS version_after
                FROM tna.org_audit_event WHERE tenant_id = ? AND command_id = ?
                """, actor.tenantId(), command);
        assertEquals(joined.toString(), snapshot.get("joined_before"));
        assertEquals("null", snapshot.get("left_before_type"));
        assertEquals(joined.toString(), snapshot.get("joined_after"));
        assertEquals(lastDay.toString(), snapshot.get("left_after"));
        assertEquals("합성 직원 😀", snapshot.get("display_name_after"));
        assertEquals("1", snapshot.get("version_after"));
        assertEquals(lastDay, admin.queryForObject("""
                SELECT left_on FROM tna.employee WHERE tenant_id = ? AND id = ?
                """, LocalDate.class, actor.tenantId(), actor.employeeId()));
    }

    @Test
    void failedSnapshotSerializationRollsBackAndReturnsASafeError() {
        UUID command = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        var error = assertThrows(OrgException.class, () -> commands.execute(actor, command, "COMMAND_TEST",
                "ORGANIZATION", OrgCommands.fingerprint(organization), "감사 JSON 실패 검증", () -> {
                    insertOrganization(actor, organization);
                    return new OrgCommands.Change(organization, null, new UnserializableSnapshot());
                }));
        assertEquals(503, error.status());
        assertSafeError(error);
        assertEquals(0, organizationCount(actor, organization));
        assertEquals(0, auditCount(actor, command));
    }

    @Test
    void requiredTextRejectsUnpairedSurrogatesBeforeFingerprintOrDatabaseEncoding() {
        for (String malformed : List.of("\uD800", "\uDC00", "합성\uD800문자", "\uD800\uD800")) {
            assertStatus(422, () -> OrgCommands.requiredText(malformed, 40, "조직 코드"));
        }
        assertEquals("😀".repeat(40), OrgCommands.requiredText(" " + "😀".repeat(40) + " ", 40, "조직 코드"));
    }

    public static final class UnserializableSnapshot {
        public String getValue() {
            throw new IllegalStateException("RAW_SQL_SENTINEL INSERT INTO tna.organization private-value");
        }
    }

    private TnaPrincipal tenantAdministrator() {
        UUID tenant = UUID.randomUUID();
        admin.update("""
                INSERT INTO platform.tenant_registry (id, company_code, display_name)
                VALUES (?, ?, '합성 명령 검증 고객사')
                """, tenant, "COMMAND-" + tenant);
        return administrator(tenant);
    }

    private TnaPrincipal administrator(UUID tenant) {
        UUID employee = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        admin.update("""
                INSERT INTO tna.employee (tenant_id, id, employee_no, display_name, joined_on)
                VALUES (?, ?, ?, '변경 전', ?)
                """, tenant, employee, "E-" + employee, commands.today().minusYears(1));
        admin.update("""
                INSERT INTO tna.tenant_account (tenant_id, id, employee_id, email, password_hash)
                VALUES (?, ?, ?, ?, 'synthetic-unused-command-password-hash')
                """, tenant, account, employee, account + "@example.test");
        for (String role : List.of("EMPLOYEE", "ATTENDANCE_ADMIN")) {
            admin.update("INSERT INTO tna.role_grant (tenant_id, account_id, role) VALUES (?, ?, ?)",
                    tenant, account, role);
        }
        return new TnaPrincipal(tenant, account, employee, 1, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.now());
    }

    private void insertOrganization(TnaPrincipal principal, UUID id) {
        application.update("""
                INSERT INTO tna.organization (tenant_id, id, code, name, created_by, updated_by)
                VALUES (?, ?, ?, '합성 명령 검증 조직', ?, ?)
                """, principal.tenantId(), id, id.toString(), principal.accountId(), principal.accountId());
    }

    private int organizationCount(TnaPrincipal principal, UUID organization) {
        return admin.queryForObject("SELECT count(*) FROM tna.organization WHERE tenant_id = ? AND id = ?",
                Integer.class, principal.tenantId(), organization);
    }

    private int auditCount(TnaPrincipal principal, UUID command) {
        return admin.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id = ? AND command_id = ?",
                Integer.class, principal.tenantId(), command);
    }

    private static void assertStatus(int status, Runnable action) {
        assertEquals(status, assertThrows(OrgException.class, action::run).status());
    }

    private static void assertSafeError(OrgException error) {
        String message = error.getMessage();
        for (String sensitive : List.of("RAW_SQL_SENTINEL", "INSERT", "tna.", "private-value", "SQLSTATE")) {
            assertFalse(message.contains(sensitive), message);
        }
    }
}
