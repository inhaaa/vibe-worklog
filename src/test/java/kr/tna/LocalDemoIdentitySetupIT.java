package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.console.LocalDemoBootstrap;
import kr.tna.console.LocalDemoIdentitySetup;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class LocalDemoIdentitySetupIT {
    private static final UUID TENANT = LocalDemoLogin.TENANT_ID;
    private static final UUID ADMIN = LocalDemoLogin.ACCOUNT_ID;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T01:00:00Z"), ZoneOffset.UTC);
    private static PostgreSQLContainer container;
    private static DataSource migration;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void ownIsolatedDatabase() {
        container = new PostgreSQLContainer(DockerImageName.parse(DatabaseFixture.postgresImage().get()).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("worklog_demo").withUsername("identity_test_admin")
                .withPassword("Synthetic-Identity-Admin-Only-43!")
                .withCreateContainerCmdModifier(DatabaseFixture::bindOwnedPostgresToLoopback);
        container.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword()));
        jdbc.execute("REVOKE ALL ON DATABASE worklog_demo FROM PUBLIC");
        jdbc.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        for (String role : List.of("tna_migration", "tna_app", "tna_auth", "tna_operator")) {
            jdbc.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION NOINHERIT PASSWORD 'Synthetic-Identity-Role-Only-43!'");
        }
        jdbc.execute("GRANT CONNECT ON DATABASE worklog_demo TO tna_migration,tna_app,tna_auth,tna_operator");
        jdbc.execute("GRANT CREATE ON DATABASE worklog_demo TO tna_migration");
        jdbc.execute("GRANT USAGE,CREATE ON SCHEMA public TO tna_migration");
        migration = new DriverManagerDataSource(container.getJdbcUrl(), "tna_migration", "Synthetic-Identity-Role-Only-43!");
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stopOnlyOwnedContainer() { if (container != null) container.stop(); }

    @BeforeEach
    void legacySyntheticIdentities() throws Exception {
        jdbc.execute("TRUNCATE platform.tenant_registry CASCADE");
        LocalDemoBootstrap.bootstrap(migration);
        jdbc.update("UPDATE tna.employee SET employee_no='LOCAL-DEMO',display_name='로컬 데모 담당자' WHERE tenant_id=? AND id=?", TENANT, LocalDemoLogin.EMPLOYEE_ID);
        for (int index = 1; index <= 3; index++) {
            jdbc.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,DATE '2026-01-01')",
                    TENANT, employee(index), "DEMO-00" + index, "기존 가상 사원 " + index);
        }
        jdbc.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash,auth_version) VALUES (?,?,?,?,?,1)",
                TENANT, LocalDemoLogin.APPROVER_ACCOUNT_ID, employee(1), LocalDemoLogin.APPROVER_EMAIL,
                PasswordPolicy.passwordEncoder().encode(LocalDemoLogin.APPROVER_PASSWORD));
        for (String role : List.of("EMPLOYEE", "APPROVER")) jdbc.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",
                TENANT, LocalDemoLogin.APPROVER_ACCOUNT_ID, role);
        jdbc.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,grade_name,photo_content_type,photo_bytes,version,updated_by) VALUES (?,?,'기존 직급','image/png',?,1,?)",
                TENANT, employee(1), new byte[] {1,2,3}, ADMIN);
    }

    @Test
    void renumbersOnlySyntheticEmployeesAndAddsMissingAccountsWithoutReplacingHistory() throws Exception {
        var before = jdbc.queryForList("SELECT id,joined_on,active FROM tna.employee WHERE tenant_id=? ORDER BY id", TENANT);
        String personnel = jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(p))::text FROM tna.employee_personnel p WHERE tenant_id=?", String.class, TENANT);
        assertTrue(LocalDemoIdentitySetup.setup(migration, TODAY, CLOCK));
        assertEquals("99999", number(LocalDemoLogin.EMPLOYEE_ID));
        assertEquals("담당자", jdbc.queryForObject("SELECT display_name FROM tna.employee WHERE tenant_id=? AND id=?", String.class, TENANT, LocalDemoLogin.EMPLOYEE_ID));
        for (int index = 1; index <= 3; index++) assertEquals("2600" + index, number(employee(index)));
        assertEquals("25001", number(employee(4)));
        assertEquals(before, jdbc.queryForList("SELECT id,joined_on,active FROM tna.employee WHERE tenant_id=? AND id<>? ORDER BY id", TENANT, employee(4)));
        assertEquals(personnel, jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(p))::text FROM tna.employee_personnel p WHERE tenant_id=?", String.class, TENANT));
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=?", Integer.class, TENANT));
    }

    @Test
    void rerunDoesNotChangeAnySyntheticData() throws Exception {
        assertTrue(LocalDemoIdentitySetup.setup(migration, TODAY, CLOCK));
        String before = snapshot();
        assertFalse(LocalDemoIdentitySetup.setup(migration, TODAY, CLOCK));
        assertEquals(before, snapshot());
    }

    @Test
    void duplicateReservedNumberRollsBackAllChanges() {
        jdbc.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'99999','충돌 가상 사원',DATE '2026-01-01')", TENANT, UUID.randomUUID());
        String before = snapshot();
        assertThrows(IllegalStateException.class, () -> LocalDemoIdentitySetup.setup(migration, TODAY, CLOCK));
        assertEquals(before, snapshot());
    }

    @Test
    void unexpectedExistingAccountOrRolesAreNeverOverwritten() {
        jdbc.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'COMPANY_REPRESENTATIVE')", TENANT, ADMIN);
        String before = snapshot();
        assertThrows(IllegalStateException.class, () -> LocalDemoIdentitySetup.setup(migration, TODAY, CLOCK));
        assertEquals(before, snapshot());
    }

    @Test
    void requiresExplicitCurrentDateAndRestrictedMigrationIdentity() {
        assertThrows(IllegalArgumentException.class, () -> LocalDemoIdentitySetup.setup(migration, TODAY.minusDays(1), CLOCK));
        var admin = new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        assertThrows(IllegalStateException.class, () -> LocalDemoIdentitySetup.setup(admin, TODAY, CLOCK));
    }

    private static UUID employee(int index) { return UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd020" + index); }
    private String number(UUID id) { return jdbc.queryForObject("SELECT employee_no FROM tna.employee WHERE tenant_id=? AND id=?", String.class, TENANT, id); }
    private String snapshot() {
        var values = new StringBuilder();
        for (String table : List.of("employee", "tenant_account", "role_grant", "employee_personnel", "org_audit_event")) {
            values.append(jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(v) ORDER BY to_jsonb(v)::text),'[]'::jsonb)::text FROM tna." + table + " v WHERE tenant_id=?", String.class, TENANT));
        }
        return values.toString();
    }
}
