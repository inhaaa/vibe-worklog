package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.console.LocalDemoBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class LocalDemoBootstrapIT {
    private static final UUID TENANT = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID EMPLOYEE = UUID.fromString("dddddddd-dddd-4ddd-8ddd-ddddddddddd1");
    private static final UUID ACCOUNT = UUID.fromString("dddddddd-dddd-4ddd-8ddd-ddddddddddd2");
    private DatabaseFixture fixture;
    private JdbcTemplate admin;
    private final List<UUID> otherTenants = new ArrayList<>();

    @BeforeEach
    void reservedNamespaceStartsEmptyInTheOwnedTestDatabase() {
        fixture = DatabaseFixture.start();
        fixture.ownedContainer();
        admin = new JdbcTemplate(fixture.adminDataSource());
        clear(TENANT);
    }

    @AfterEach
    void cleanOnlyNamespacesOwnedByThisTest() {
        clear(TENANT);
        for (UUID tenant : otherTenants) clear(tenant);
    }

    @Test
    void seedsOnlyTheReservedIdentityWithHashedPasswordTwoRolesAndOneCustomerAudit() throws Exception {
        UUID other = addOtherTenant("OTHER-" + UUID.randomUUID());
        String otherBefore = snapshot(other);
        int operatorCount = count("platform.operator_account");
        int operatorAuditCount = count("platform.operator_audit_event");

        assertEquals(TENANT, LocalDemoBootstrap.bootstrap(fixture.migrationDataSource()));

        assertEquals("TNA-LOCAL-DEMO", admin.queryForObject(
                "SELECT company_code FROM platform.tenant_registry WHERE id=?", String.class, TENANT));
        var identity = admin.queryForMap("""
                SELECT a.employee_id,a.email,a.password_hash,a.active,a.credential_set,
                       e.active AS employee_active,
                       e.joined_on<=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date AS employed
                FROM tna.tenant_account a JOIN tna.employee e
                  ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                WHERE a.tenant_id=? AND a.id=?
                """, TENANT, ACCOUNT);
        assertEquals(EMPLOYEE, identity.get("employee_id"));
        assertEquals("admin@local.example.test", identity.get("email"));
        assertEquals(true, identity.get("active"));
        assertEquals(true, identity.get("credential_set"));
        assertEquals(true, identity.get("employee_active"));
        assertEquals(true, identity.get("employed"));
        String hash = identity.get("password_hash").toString();
        assertFalse("admin".equals(hash));
        assertTrue(PasswordPolicy.passwordEncoder().matches("admin", hash));
        assertEquals(Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Set.copyOf(admin.queryForList(
                "SELECT role FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND active",
                String.class, TENANT, ACCOUNT)));
        assertEquals(2, tenantCount("tna.role_grant"));
        assertEquals(1, tenantCount("tna.employee"));
        assertEquals(1, tenantCount("tna.tenant_account"));
        assertEquals(0, tenantCount("tna.account_token"));
        var audit = admin.queryForMap("""
                SELECT actor_account_id,action,before_state::text,after_state::text
                FROM tna.org_audit_event WHERE tenant_id=?
                """, TENANT);
        assertEquals(ACCOUNT, audit.get("actor_account_id"));
        assertEquals("LOCAL_DEMO_BOOTSTRAP", audit.get("action"));
        assertFalse(audit.toString().contains(hash));
        assertTrue(otherBefore.equals(snapshot(other)), "다른 고객사 자료를 변경하면 안 됩니다.");
        assertEquals(operatorCount, count("platform.operator_account"));
        assertEquals(operatorAuditCount, count("platform.operator_audit_event"));
    }

    @Test
    void replayVerifiesExistingRowsWithoutChangingHashesVersionsOrAudit() throws Exception {
        LocalDemoBootstrap.bootstrap(fixture.migrationDataSource());
        String before = snapshot(TENANT);
        assertEquals(TENANT, LocalDemoBootstrap.bootstrap(fixture.migrationDataSource()));
        assertTrue(before.equals(snapshot(TENANT)), "재실행은 기존 자료를 덮어쓰지 않습니다.");
        assertEquals(1, tenantCount("tna.org_audit_event"));
    }

    @Test
    void changedHashIsRejectedEvenWhenAReplacementStillMatchesAdmin() throws Exception {
        LocalDemoBootstrap.bootstrap(fixture.migrationDataSource());
        admin.update("UPDATE tna.tenant_account SET password_hash=? WHERE tenant_id=? AND id=?",
                PasswordPolicy.passwordEncoder().encode("admin"), TENANT, ACCOUNT);
        assertRefusedWithoutRepair();
    }

    @Test
    void changedIdentityInactiveEmploymentAndRevokedOrAddedRolesAreNotRepaired() throws Exception {
        for (String change : List.of(
                "UPDATE platform.tenant_registry SET active=false WHERE id=?",
                "UPDATE tna.employee SET active=false WHERE tenant_id=?",
                "UPDATE tna.employee SET joined_on=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date+1 WHERE tenant_id=?",
                "UPDATE tna.employee SET joined_on=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date-2,left_on=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date-1 WHERE tenant_id=?",
                "UPDATE tna.tenant_account SET active=false WHERE tenant_id=?",
                "UPDATE tna.tenant_account SET email='changed@example.test' WHERE tenant_id=?",
                "UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND role='ATTENDANCE_ADMIN'",
                "DELETE FROM tna.role_grant WHERE tenant_id=? AND role='EMPLOYEE'")) {
            clear(TENANT);
            LocalDemoBootstrap.bootstrap(fixture.migrationDataSource());
            admin.update(change, TENANT);
            assertRefusedWithoutRepair();
        }
        clear(TENANT);
        LocalDemoBootstrap.bootstrap(fixture.migrationDataSource());
        admin.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'APPROVER')",
                TENANT, ACCOUNT);
        assertRefusedWithoutRepair();
    }

    @Test
    void existingReservedIdOrCompanyCodeCannotBeAdoptedAsAnExistingDemo() throws Exception {
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 충돌')",
                TENANT, "OTHER-RESERVED-ID");
        assertRefusedWithoutRepair();
        clear(TENANT);
        UUID other = addOtherTenant("TNA-LOCAL-DEMO");
        String before = snapshot(other);
        assertThrows(SQLException.class, () -> LocalDemoBootstrap.bootstrap(fixture.migrationDataSource()));
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM platform.tenant_registry WHERE id=?",
                Integer.class, TENANT));
        assertEquals(0, tenantCount("tna.employee"));
        assertTrue(before.equals(snapshot(other)), "회사코드 충돌 자료를 변경하면 안 됩니다.");
    }

    @Test
    void absentBootstrapAuditCannotBeSilentlyRecreated() throws Exception {
        LocalDemoBootstrap.bootstrap(fixture.migrationDataSource());
        admin.update("DELETE FROM tna.org_audit_event WHERE tenant_id=?", TENANT);
        assertRefusedWithoutRepair();
    }

    @Test
    void auditFailureRollsBackAllEarlierSeedWrites() {
        DataSource failing = new DelegatingDataSource(fixture.migrationDataSource()) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                    && sql.stripLeading().startsWith("INSERT INTO tna.org_audit_event")) {
                                throw new SQLException("synthetic audit failure", "P0001");
                            }
                            try { return method.invoke(connection, args); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
            }
        };
        assertThrows(SQLException.class, () -> LocalDemoBootstrap.bootstrap(failing));
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM platform.tenant_registry WHERE id=?",
                Integer.class, TENANT));
        for (String table : List.of("tna.employee", "tna.tenant_account", "tna.role_grant", "tna.org_audit_event")) {
            assertEquals(0, tenantCount(table));
        }
    }

    @Test
    void concurrentCommandsSerializeToOneSeedAndOneAudit() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> LocalDemoBootstrap.bootstrap(fixture.migrationDataSource()));
            var second = workers.submit(() -> LocalDemoBootstrap.bootstrap(fixture.migrationDataSource()));
            assertEquals(TENANT, first.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(TENANT, second.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, tenantCount("tna.tenant_account"));
        assertEquals(1, tenantCount("tna.org_audit_event"));
    }

    private void assertRefusedWithoutRepair() {
        String before = snapshot(TENANT);
        assertThrows(IllegalStateException.class,
                () -> LocalDemoBootstrap.bootstrap(fixture.migrationDataSource()));
        assertTrue(before.equals(snapshot(TENANT)), "충돌·변경·회수 상태를 복구하면 안 됩니다.");
    }

    private int count(String table) {
        return admin.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int tenantCount(String table) {
        return admin.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=?", Integer.class, TENANT);
    }

    private String snapshot(UUID tenant) {
        return List.of(admin.queryForList("SELECT * FROM platform.tenant_registry WHERE id=?", tenant),
                admin.queryForList("SELECT * FROM tna.employee WHERE tenant_id=? ORDER BY id", tenant),
                admin.queryForList("SELECT * FROM tna.tenant_account WHERE tenant_id=? ORDER BY id", tenant),
                admin.queryForList("SELECT * FROM tna.role_grant WHERE tenant_id=? ORDER BY account_id,role", tenant),
                admin.queryForList("SELECT * FROM tna.org_audit_event WHERE tenant_id=? ORDER BY id", tenant)).toString();
    }

    private UUID addOtherTenant(String companyCode) {
        UUID tenant = UUID.randomUUID(), employee = UUID.randomUUID(), account = UUID.randomUUID();
        otherTenants.add(tenant);
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'다른 합성 고객사')",
                tenant, companyCode);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'OTHER','합성 직원',DATE '2026-01-01')",
                tenant, employee);
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'other@example.test',?)",
                tenant, account, employee, PasswordPolicy.passwordEncoder().encode("synthetic-other-password"));
        admin.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, account);
        return tenant;
    }

    private void clear(UUID tenant) {
        admin.update("UPDATE tna.employee SET created_by=NULL,updated_by=NULL WHERE tenant_id=?", tenant);
        for (String table : List.of("tna.org_audit_event", "tna.account_token", "tna.role_grant", "tna.tenant_account", "tna.employee")) {
            admin.update("DELETE FROM " + table + " WHERE tenant_id=?", tenant);
        }
        admin.update("DELETE FROM platform.tenant_registry WHERE id=?", tenant);
    }
}
