package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseFoundationIT {
    private static DatabaseFixture database;

    @BeforeAll
    static void database() {
        database = DatabaseFixture.start();
    }

    @Test
    void identityTablesAreProtectedByRls() {
        JdbcTemplate admin = new JdbcTemplate(database.adminDataSource());
        assertEquals(18, admin.queryForObject("SHOW server_version_num", Integer.class) / 10000);
        List<String> protectedTables = admin.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'tna' AND c.relkind = 'r' AND c.relrowsecurity AND c.relforcerowsecurity
                ORDER BY c.relname
                """, String.class);
        assertEquals(List.of("account_token", "allowed_network", "approval_event", "approval_request",
                "approver_assignment", "attendance_event", "calculation_run", "calendar_day",
                "calendar_version", "correction_detail", "day_input_revision", "day_result",
                "employee", "employee_assignment", "employment_rule_assessment", "leave_bucket",
                "leave_ledger", "leave_request_day", "org_audit_event", "organization", "organization_head_assignment",
                "report_snapshot", "request_effect", "role_grant", "schedule_assignment",
                "schedule_break", "schedule_day", "schedule_template", "schedule_version",
                "tenant_account", "work_confirmation", "work_policy_version"), protectedTables);
    }

    @Test
    void runtimeRolesCannotBypassRlsOrOwnTheTables() {
        JdbcTemplate admin = new JdbcTemplate(database.adminDataSource());
        for (String role : List.of(DatabaseFixture.APP_USER, DatabaseFixture.AUTH_USER,
                DatabaseFixture.MIGRATION_USER, "tna_operator")) {
            assertFalse(admin.queryForObject("""
                    SELECT rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole OR rolreplication OR rolinherit
                    FROM pg_roles WHERE rolname = ?
                    """,
                    Boolean.class, role));
            assertTrue(admin.queryForObject("SELECT rolcanlogin FROM pg_roles WHERE rolname = ?",
                    Boolean.class, role));
        }
        for (String role : List.of(DatabaseFixture.APP_USER, DatabaseFixture.AUTH_USER)) {
            assertEquals(0, admin.queryForObject("""
                    SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_roles r ON r.oid = c.relowner
                    WHERE n.nspname IN ('platform', 'tna') AND r.rolname = ?
                    """, Integer.class, role));
        }
        assertEquals(0, admin.queryForObject("""
                SELECT count(*) FROM pg_auth_members WHERE member IN
                    (SELECT oid FROM pg_roles WHERE rolname IN ('tna_app', 'tna_auth'))
                """, Integer.class));
    }

    @Test
    void absentTenantSeesNoEmployeeAndRuntimeCannotWrite() {
        database.seedIdentity("synthetic-test-hash");
        JdbcTemplate app = new JdbcTemplate(database.appDataSource());
        assertEquals(0, app.queryForObject("SELECT count(*) FROM tna.employee", Integer.class));
        assertThrows(DataAccessException.class, () -> app.update("""
                INSERT INTO tna.employee (tenant_id, id, employee_no, display_name, joined_on, active)
                VALUES (?, ?, 'BLOCKED', 'Blocked', DATE '2026-01-01', true)
                """, DatabaseFixture.TENANT_A, UUID.randomUUID()));
        assertThrows(DataAccessException.class,
                () -> app.queryForList("SELECT password_hash FROM tna.tenant_account"));
        assertThrows(DataAccessException.class,
                () -> app.queryForObject("SELECT count(*) FROM public.flyway_schema_history", Integer.class));
    }

    @Test
    void authenticationRoleMustResolveTenantBeforeReadingIdentity() {
        database.seedIdentity("synthetic-test-hash");
        JdbcTemplate auth = new JdbcTemplate(database.authDataSource());
        assertEquals(2, auth.queryForObject("SELECT count(*) FROM platform.tenant_registry WHERE id IN (?, ?)",
                Integer.class, DatabaseFixture.TENANT_A, DatabaseFixture.TENANT_B));
        assertEquals(0, auth.queryForObject("SELECT count(*) FROM tna.tenant_account", Integer.class));
        TenantTransactions transactions = new TenantTransactions(database.authDataSource());
        assertEquals(1, transactions.execute(DatabaseFixture.TENANT_A, () -> auth.queryForObject("""
                SELECT count(*) FROM tna.tenant_account WHERE tenant_id = ? AND email = ?
                """, Integer.class, DatabaseFixture.TENANT_A, DatabaseFixture.EMAIL)));
        assertThrows(DataAccessException.class,
                () -> auth.update("UPDATE tna.tenant_account SET email = 'blocked@example.test'"));
    }

    @Test
    void explicitTenantPredicateAndRlsBothIsolateData() {
        database.seedIdentity("synthetic-test-hash");
        JdbcTemplate app = new JdbcTemplate(database.appDataSource());
        TenantTransactions transactions = new TenantTransactions(database.appDataSource());
        for (UUID tenant : List.of(DatabaseFixture.TENANT_A, DatabaseFixture.TENANT_B)) {
            assertEquals(List.of(tenant), transactions.execute(tenant, () -> app.queryForList(
                    "SELECT DISTINCT tenant_id FROM tna.employee WHERE tenant_id = ?", UUID.class, tenant)));
            assertEquals(List.of(tenant), transactions.execute(tenant,
                    () -> app.queryForList("SELECT DISTINCT tenant_id FROM tna.employee", UUID.class)));
            UUID other = tenant.equals(DatabaseFixture.TENANT_A)
                    ? DatabaseFixture.TENANT_B : DatabaseFixture.TENANT_A;
            assertEquals(0, transactions.execute(tenant, () -> app.queryForObject(
                    "SELECT count(*) FROM tna.employee WHERE tenant_id = ?", Integer.class, other)));
            assertEquals(1, transactions.execute(tenant,
                    () -> app.queryForObject("SELECT count(*) FROM platform.tenant_registry", Integer.class)));
        }
    }

    @Test
    void oneConnectionPoolClearsTenantAfterCommitAndExceptionRollback() {
        database.seedIdentity("synthetic-test-hash");
        JdbcTemplate app = new JdbcTemplate(database.appDataSource());
        TenantTransactions transactions = new TenantTransactions(database.appDataSource());
        int backend = transactions.execute(DatabaseFixture.TENANT_A,
                () -> app.queryForObject("SELECT pg_backend_pid()", Integer.class));
        assertTenantCleared(app, backend);
        assertThrows(IllegalStateException.class, () -> transactions.execute(DatabaseFixture.TENANT_A, () -> {
            throw new IllegalStateException("Synthetic rollback");
        }));
        assertTenantCleared(app, backend);
        assertEquals(List.of(DatabaseFixture.TENANT_B), transactions.execute(DatabaseFixture.TENANT_B,
                () -> app.queryForList("SELECT DISTINCT tenant_id FROM tna.employee", UUID.class)));
        assertTenantCleared(app, backend);
    }

    @Test
    void crossTenantNestedTransactionIsRejected() {
        TenantTransactions transactions = new TenantTransactions(database.appDataSource());
        assertThrows(IllegalStateException.class, () -> transactions.execute(DatabaseFixture.TENANT_A,
                () -> transactions.execute(DatabaseFixture.TENANT_B, () -> "blocked")));
    }

    @Test
    void sameTenantNestedTransactionKeepsTheOriginalScope() {
        database.seedIdentity("synthetic-test-hash");
        TenantTransactions transactions = new TenantTransactions(database.appDataSource());
        JdbcTemplate app = new JdbcTemplate(database.appDataSource());
        assertEquals(List.of(DatabaseFixture.TENANT_A), transactions.execute(DatabaseFixture.TENANT_A,
                () -> transactions.execute(DatabaseFixture.TENANT_A,
                        () -> app.queryForList("SELECT DISTINCT tenant_id FROM tna.employee", UUID.class))));
    }

    @Test
    void sqlFailureAlsoRollsBackTheTenantContext() {
        JdbcTemplate app = new JdbcTemplate(database.appDataSource());
        TenantTransactions transactions = new TenantTransactions(database.appDataSource());
        int backend = app.queryForObject("SELECT pg_backend_pid()", Integer.class);
        assertThrows(DataAccessException.class, () -> transactions.execute(DatabaseFixture.TENANT_A,
                () -> app.queryForObject("SELECT 1 / 0", Integer.class)));
        assertTenantCleared(app, backend);
    }

    @Test
    void compositeForeignKeyRejectsEmployeeFromAnotherTenant() throws SQLException {
        database.seedIdentity("synthetic-test-hash");
        try (Connection connection = database.migrationDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var context = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                context.setString(1, DatabaseFixture.TENANT_A.toString());
                context.execute();
            }
            SQLException error = assertThrows(SQLException.class, () -> {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO tna.tenant_account
                            (tenant_id, id, employee_id, email, password_hash, active, auth_version)
                        VALUES (?, ?, ?, 'cross@example.test', 'synthetic', true, 1)
                        """)) {
                    insert.setObject(1, DatabaseFixture.TENANT_A);
                    insert.setObject(2, UUID.randomUUID());
                    insert.setObject(3, DatabaseFixture.EMPLOYEE_B);
                    insert.executeUpdate();
                }
            });
            assertEquals("23503", error.getSQLState());
            connection.rollback();
        }
    }

    @Test
    void fixtureRefusesRemoteOrUnrelatedDatabases() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseFixture.validateLocalTestUrl(
                "jdbc:postgresql://db.example.test:5432/tna_phase4_test"));
        assertThrows(IllegalArgumentException.class, () -> DatabaseFixture.validateLocalTestUrl(
                "jdbc:postgresql://localhost:5432/postgres"));
        assertThrows(IllegalArgumentException.class, () -> DatabaseFixture.validateLocalTestUrl(
                "jdbc:postgresql://localhost:5432/tna_phase4_test?socketFactory=untrusted.Factory"));
    }

    private void assertTenantCleared(JdbcTemplate app, int backend) {
        assertEquals(backend, app.queryForObject("SELECT pg_backend_pid()", Integer.class));
        String context = app.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class);
        assertTrue(context == null || context.isEmpty());
        assertEquals(0, app.queryForObject("SELECT count(*) FROM tna.employee", Integer.class));
    }
}
