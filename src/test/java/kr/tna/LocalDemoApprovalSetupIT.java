package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.console.LocalDemoApprovalSetup;
import kr.tna.console.LocalDemoBootstrap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class LocalDemoApprovalSetupIT {
    private static final UUID TENANT = LocalDemoLogin.TENANT_ID;
    private static final UUID ADMIN = LocalDemoLogin.ACCOUNT_ID;
    private static final UUID EMPLOYEE = LocalDemoLogin.EMPLOYEE_ID;
    private static final UUID APPROVER = LocalDemoLogin.APPROVER_EMPLOYEE_ID;
    private static final UUID ACCOUNT = LocalDemoLogin.APPROVER_ACCOUNT_ID;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T01:00:00Z"), ZoneOffset.UTC);
    private static PostgreSQLContainer container;
    private static DataSource migration;
    private static JdbcTemplate admin;

    @BeforeAll
    static void ownFreshWorklogDemoDatabaseWithRestrictedMigrationRole() {
        container = new PostgreSQLContainer(DockerImageName.parse(DatabaseFixture.postgresImage().get()).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("worklog_demo").withUsername("approval_test_admin")
                .withPassword("Synthetic-Approval-Test-Only-43!")
                .withCreateContainerCmdModifier(DatabaseFixture::bindOwnedPostgresToLoopback);
        container.start();
        admin = new JdbcTemplate(new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword()));
        admin.execute("REVOKE ALL ON DATABASE worklog_demo FROM PUBLIC");
        admin.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        for (String role : List.of("tna_migration", "tna_app", "tna_auth", "tna_operator")) {
            admin.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT PASSWORD 'Synthetic-Approval-Role-Only-43!'");
        }
        admin.execute("GRANT CONNECT ON DATABASE worklog_demo TO tna_migration,tna_app,tna_auth,tna_operator");
        admin.execute("GRANT CREATE ON DATABASE worklog_demo TO tna_migration");
        admin.execute("GRANT USAGE,CREATE ON SCHEMA public TO tna_migration");
        migration = new DriverManagerDataSource(container.getJdbcUrl(), "tna_migration", "Synthetic-Approval-Role-Only-43!");
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stopOnlyTheFixtureOwnedContainer() {
        if (container != null) container.stop();
    }

    @BeforeEach
    void seedOnlyThisOwnedDatabase() throws Exception {
        admin.execute("TRUNCATE platform.tenant_registry CASCADE");
        LocalDemoBootstrap.bootstrap(migration);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,version) VALUES (?,?,'DEMO-001','가상 승인자',DATE '2026-01-01',7)", TENANT, APPROVER);
        admin.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,grade_name,photo_content_type,photo_bytes,version,updated_by) VALUES (?,?,'합성 직급','image/png',?,1,?)", TENANT, APPROVER, new byte[] {1, 2, 3}, ADMIN);
        admin.update("INSERT INTO tna.employee_employment_status(tenant_id,id,employee_id,employment_status,effective_from,revision,reason,created_by) VALUES (?,?,?,'WORKING',DATE '2026-01-01',1,'합성 재직',?)", TENANT, UUID.randomUUID(), APPROVER, ADMIN);
        UUID bucket = UUID.randomUUID();
        admin.update("INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,source_reference,created_by) VALUES (?,?,?,DATE '2026-01-01',DATE '2026-01-01','기존 12 DAY',?)", TENANT, bucket, EMPLOYEE, ADMIN);
        admin.update("INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,source_reference,reason,created_by) VALUES (?,?,?,?,'OPENING',12,DATE '2026-01-01','기존 근거','합성 기초',?)", TENANT, UUID.randomUUID(), bucket, EMPLOYEE, ADMIN);
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,'UNRELATED-APPROVAL-TEST','다른 합성 고객사')", UUID.randomUUID());
    }

    @Test
    void addsExactlyFiveRowsAndOnlyTheApplicantEmployeeRevisionChanges() throws Exception {
        var before = snapshot();
        var beforeCounts = counts();
        var applicant = employee(EMPLOYEE);
        var approver = employee(APPROVER);
        assertTrue(LocalDemoApprovalSetup.setup(migration, TODAY, CLOCK));
        var expectedCounts = new LinkedHashMap<>(beforeCounts);
        expectedCounts.compute("tna.tenant_account", (key, value) -> value + 1);
        expectedCounts.compute("tna.role_grant", (key, value) -> value + 2);
        expectedCounts.compute("tna.approver_assignment", (key, value) -> value + 1);
        expectedCounts.compute("tna.org_audit_event", (key, value) -> value + 1);
        assertEquals(expectedCounts, counts());
        var after = snapshot();
        for (String table : before.keySet()) {
            if (!Set.of("tna.tenant_account", "tna.role_grant", "tna.approver_assignment", "tna.org_audit_event", "tna.employee").contains(table)) assertEquals(before.get(table), after.get(table), table);
        }
        assertEquals(approver, employee(APPROVER));
        var updated = employee(EMPLOYEE);
        assertEquals(((Number) applicant.get("version")).longValue() + 1, ((Number) updated.get("version")).longValue());
        assertEquals(ADMIN, updated.get("updated_by"));
        for (String column : applicant.keySet()) if (!Set.of("version", "updated_at", "updated_by").contains(column)) assertEquals(applicant.get(column), updated.get(column), column);
        var account = admin.queryForMap("SELECT * FROM tna.tenant_account WHERE tenant_id=? AND id=?", TENANT, ACCOUNT);
        assertEquals(APPROVER, account.get("employee_id"));
        assertEquals(LocalDemoLogin.APPROVER_EMAIL, account.get("email"));
        assertEquals(true, account.get("active"));
        assertEquals(1L, ((Number) account.get("auth_version")).longValue());
        assertTrue(PasswordPolicy.passwordEncoder().matches("approver", (String) account.get("password_hash")));
        assertEquals(Set.of("EMPLOYEE", "APPROVER"), Set.copyOf(admin.queryForList("SELECT role FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND active", String.class, TENANT, ACCOUNT)));
        var assignment = admin.queryForMap("SELECT * FROM tna.approver_assignment WHERE tenant_id=?", TENANT);
        assertEquals(EMPLOYEE, assignment.get("employee_id"));
        assertEquals(APPROVER, assignment.get("approver_employee_id"));
        assertEquals(TODAY, ((java.sql.Date) assignment.get("valid_from")).toLocalDate());
        assertNull(assignment.get("valid_to"));
        assertNull(assignment.get("superseded_at"));
        var audit = admin.queryForMap("SELECT * FROM tna.org_audit_event WHERE tenant_id=? AND action='LOCAL_DEMO_APPROVAL_SETUP_20261103'", TENANT);
        assertFalse(audit.toString().contains(account.get("password_hash").toString()));
        assertFalse(audit.toString().contains("approver@local.example.test"));
        assertFalse(LocalDemoApprovalSetup.setup(migration, TODAY, CLOCK));
        assertEquals(after, snapshot());
    }

    @Test
    void theBootstrapFingerprintRejectsAReplacementAdminHashThatStillMatchesThePassword() {
        admin.update("UPDATE tna.tenant_account SET password_hash=? WHERE tenant_id=? AND id=?", PasswordPolicy.passwordEncoder().encode("admin"), TENANT, ADMIN);
        refused();
    }

    @Test
    void replayRejectsChangedApproverHashRolesAssignmentAndApplicantRevisionWithoutRepair() throws Exception {
        for (String change : List.of(
                "UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id='dddddddd-dddd-4ddd-8ddd-dddddddd0211' AND role='APPROVER'",
                "INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,'dddddddd-dddd-4ddd-8ddd-dddddddd0211','ATTENDANCE_ADMIN')",
                "UPDATE tna.approver_assignment SET valid_to=DATE '2026-11-04' WHERE tenant_id=?",
                "UPDATE tna.employee SET version=version+1 WHERE tenant_id=? AND id='dddddddd-dddd-4ddd-8ddd-ddddddddddd1'",
                "DELETE FROM tna.org_audit_event WHERE tenant_id=? AND action='LOCAL_DEMO_APPROVAL_SETUP_20261103'")) {
            seedOnlyThisOwnedDatabase();
            LocalDemoApprovalSetup.setup(migration, TODAY, CLOCK);
            admin.update(change, TENANT);
            refused();
        }
        seedOnlyThisOwnedDatabase();
        LocalDemoApprovalSetup.setup(migration, TODAY, CLOCK);
        admin.update("UPDATE tna.tenant_account SET password_hash=? WHERE tenant_id=? AND id=?", PasswordPolicy.passwordEncoder().encode("approver"), TENANT, ACCOUNT);
        refused();
    }

    @Test
    void missingBootstrapEvidenceWrongEmploymentAndExistingAccountsAreNotAdopted() throws Exception {
        for (String change : List.of(
                "DELETE FROM tna.org_audit_event WHERE tenant_id=? AND action='LOCAL_DEMO_BOOTSTRAP'",
                "UPDATE tna.employee SET employee_no='OTHER' WHERE tenant_id=? AND id='dddddddd-dddd-4ddd-8ddd-dddddddd0201'",
                "UPDATE tna.employee_employment_status SET employment_status='ON_LEAVE' WHERE tenant_id=?",
                "UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND role='ATTENDANCE_ADMIN'",
                "UPDATE platform.tenant_registry SET active=false WHERE id=?")) {
            seedOnlyThisOwnedDatabase();
            admin.update(change, TENANT);
            refused();
        }
        seedOnlyThisOwnedDatabase();
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'existing@example.test',?)", TENANT, UUID.randomUUID(), APPROVER, PasswordPolicy.passwordEncoder().encode("existing-only"));
        refused();
    }

    @Test
    void existingDirectAndRouteAssignmentsAreNeverReplaced() throws Exception {
        admin.update("INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,DATE '2026-01-01','기존 배정',?,?)", TENANT, UUID.randomUUID(), EMPLOYEE, APPROVER, ADMIN, ADMIN);
        refused();
        seedOnlyThisOwnedDatabase();
        UUID route = UUID.randomUUID(), version = UUID.randomUUID();
        admin.update("INSERT INTO tna.approval_route(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'EXISTING','기존 결재선',?,?)", TENANT, route, ADMIN, ADMIN);
        admin.update("INSERT INTO tna.approval_route_version(tenant_id,id,route_id,revision,name,approver_kind,employee_id,reason,created_by) VALUES (?,?,?,1,'기존 결재선','DIRECT_EMPLOYEE',?,'기존 근거',?)", TENANT, version, route, APPROVER, ADMIN);
        admin.update("INSERT INTO tna.approval_route_assignment(tenant_id,id,route_version_id,scope_kind,employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,'EMPLOYEE',?,DATE '2026-01-01','기존 배정',?,?)", TENANT, UUID.randomUUID(), version, EMPLOYEE, ADMIN, ADMIN);
        refused();
    }

    @Test
    void anAuditFailureRollsBackTheAccountRolesAssignmentAndEmployeeUpdate() {
        var before = snapshot();
        DataSource failing = new DelegatingDataSource(migration) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.stripLeading().startsWith("INSERT INTO tna.org_audit_event")) throw new SQLException("synthetic audit failure", "P0001");
                    try { return method.invoke(connection, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
        };
        assertThrows(SQLException.class, () -> LocalDemoApprovalSetup.setup(failing, TODAY, CLOCK));
        assertEquals(before, snapshot());
    }

    @Test
    void parallelSetupCreatesOneAccountAndOneAuditThenReplaysWithoutChanges() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> LocalDemoApprovalSetup.setup(migration, TODAY, CLOCK));
            var second = workers.submit(() -> LocalDemoApprovalSetup.setup(migration, TODAY, CLOCK));
            assertEquals(Set.of(true, false), Set.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)));
        }
        assertEquals(2, admin.queryForObject("SELECT count(*) FROM tna.tenant_account", Integer.class));
        assertEquals(2, admin.queryForObject("SELECT count(*) FROM tna.org_audit_event", Integer.class));
        var before = snapshot();
        assertFalse(LocalDemoApprovalSetup.setup(migration, TODAY, CLOCK));
        assertEquals(before, snapshot());
    }

    @Test
    void unsafeDatabaseRolesAndDisabledForcedRlsAreRejectedBeforeMutation() {
        var before = snapshot();
        var superuser = new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        assertThrows(IllegalStateException.class, () -> LocalDemoApprovalSetup.setup(superuser, TODAY, CLOCK));
        var otherDatabase = new DriverManagerDataSource(container.getJdbcUrl().replace("/worklog_demo", "/postgres"), "tna_migration", "Synthetic-Approval-Role-Only-43!");
        assertThrows(IllegalStateException.class, () -> LocalDemoApprovalSetup.setup(otherDatabase, TODAY, CLOCK));
        admin.execute("ALTER TABLE tna.employee NO FORCE ROW LEVEL SECURITY");
        try { assertThrows(IllegalStateException.class, () -> LocalDemoApprovalSetup.setup(migration, TODAY, CLOCK)); }
        finally { admin.execute("ALTER TABLE tna.employee FORCE ROW LEVEL SECURITY"); }
        assertEquals(before, snapshot());
    }

    private void refused() {
        var before = snapshot();
        assertThrows(IllegalStateException.class, () -> LocalDemoApprovalSetup.setup(migration, TODAY, CLOCK));
        assertEquals(before, snapshot());
    }

    private Map<String, Object> employee(UUID id) {
        return admin.queryForMap("SELECT * FROM tna.employee WHERE tenant_id=? AND id=?", TENANT, id);
    }

    private Map<String, String> snapshot() {
        var result = new LinkedHashMap<String, String>();
        for (String table : tables()) result.put(table, admin.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM " + table + " t", String.class));
        return result;
    }

    private Map<String, Integer> counts() {
        var result = new LinkedHashMap<String, Integer>();
        for (String table : tables()) result.put(table, admin.queryForObject("SELECT count(*) FROM " + table, Integer.class));
        return result;
    }

    private List<String> tables() {
        return admin.queryForList("SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN ('tna','platform') ORDER BY 1", String.class);
    }
}
