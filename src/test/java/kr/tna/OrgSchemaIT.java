package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class OrgSchemaIT {
    private static DatabaseFixture database;
    private static JdbcTemplate admin;
    private static JdbcTemplate app;
    private static TenantTransactions transactions;

    @BeforeAll
    static void database() {
        database = DatabaseFixture.start();
        admin = new JdbcTemplate(database.adminDataSource());
        app = new JdbcTemplate(database.appDataSource());
        transactions = new TenantTransactions(database.appDataSource());
    }

    @Test
    void organizationSchemaExists() {
        assertNotNull(admin.queryForObject("SELECT to_regclass('tna.organization')::text", String.class));
    }

    @Test
    void newTablesHaveForcedRlsAndTrustedExtensionUsesTheTenantSchema() {
        assertEquals(List.of("employee_assignment", "org_audit_event", "organization"),
                admin.queryForList("""
                        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'tna' AND c.relname IN
                            ('organization', 'employee_assignment', 'org_audit_event')
                            AND c.relrowsecurity AND c.relforcerowsecurity ORDER BY c.relname
                        """, String.class));
        assertEquals("tna", admin.queryForObject("""
                SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                WHERE e.extname = 'btree_gist'
                """, String.class));
    }

    @Test
    void legacyEmployeeMetadataRemainsUnknownRatherThanInventingCreationData() {
        Scope scope = scope();
        Map<String, Object> row = admin.queryForMap("""
                SELECT version, created_at, created_by, updated_at, updated_by FROM tna.employee
                WHERE tenant_id = ? AND id = ?
                """, scope.tenant(), scope.employee());
        assertEquals(0L, row.get("version"));
        for (String column : List.of("created_at", "created_by", "updated_at", "updated_by")) {
            assertNull(row.get(column));
            assertNull(admin.queryForObject("""
                    SELECT column_default FROM information_schema.columns
                    WHERE table_schema = 'tna' AND table_name = 'employee' AND column_name = ?
                    """, String.class, column));
        }
    }

    @Test
    void runtimeReadsAndWritesAreTenantIsolatedOnEveryNewTable() {
        Scope own = scope();
        Scope other = scope();
        for (Scope scope : List.of(own, other)) {
            transactions.execute(scope.tenant(), () -> {
                organization(scope, scope.organization(), "ROOT", null, scope.actor());
                assignment(scope, scope.employee(), scope.organization(), "2026-01-01", null);
                audit(scope, UUID.randomUUID());
                return null;
            });
        }
        for (String table : List.of("organization", "employee_assignment", "org_audit_event")) {
            assertEquals(0, app.queryForObject("SELECT count(*) FROM tna." + table, Integer.class));
            assertEquals(List.of(own.tenant()), transactions.execute(own.tenant(), () ->
                    app.queryForList("SELECT tenant_id FROM tna." + table, UUID.class)));
            assertEquals(0, transactions.execute(own.tenant(), () -> app.queryForObject(
                    "SELECT count(*) FROM tna." + table + " WHERE tenant_id = ?", Integer.class,
                    other.tenant())));
        }
        rejects("42501", own, () -> organization(other, UUID.randomUUID(), "CROSS", null, other.actor()));
        rejects("42501", own, () -> assignment(other, other.employee(), other.organization(),
                "2027-01-01", null));
        rejects("42501", own, () -> audit(other, UUID.randomUUID()));
    }

    @Test
    void organizationParentAndMetadataActorsCannotCrossTenants() {
        Scope own = scope();
        Scope other = scope();
        transactions.execute(other.tenant(), () -> organization(other, other.organization(), "ROOT", null,
                other.actor()));
        rejects("23503", own, () -> organization(own, UUID.randomUUID(), "CHILD", other.organization(),
                own.actor()));
        rejects("23503", own, () -> organization(own, UUID.randomUUID(), "ACTOR", null, other.actor()));
        rejects("23514", own, () -> organization(own, own.organization(), "SELF", own.organization(),
                own.actor()));
    }

    @Test
    void organizationCodesAreUniqueOnlyWithinTheirTenant() {
        Scope own = scope();
        Scope other = scope();
        transactions.execute(own.tenant(), () -> organization(own, own.organization(), "ROOT", null,
                own.actor()));
        transactions.execute(other.tenant(), () -> organization(other, other.organization(), "ROOT", null,
                other.actor()));
        rejects("23505", own, () -> organization(own, UUID.randomUUID(), "ROOT", null, own.actor()));
    }

    @Test
    void assignmentRangesAllowAdjacentHistoryAndRejectOverlapAndEmptyRanges() {
        Scope scope = scope();
        transactions.execute(scope.tenant(), () -> {
            organization(scope, scope.organization(), "ROOT", null, scope.actor());
            assignment(scope, scope.employee(), scope.organization(), "2026-01-01", "2026-02-01");
            assignment(scope, scope.employee(), scope.organization(), "2026-02-01", null);
            return null;
        });
        rejects("23P01", scope, () -> assignment(scope, scope.employee(), scope.organization(),
                "2026-01-31", "2026-02-02"));
        rejects("23514", scope, () -> assignment(scope, scope.employee(), scope.organization(),
                "2025-12-01", "2025-12-01"));
        assertEquals(2, transactions.execute(scope.tenant(), () -> app.queryForObject("""
                SELECT count(*) FROM tna.employee_assignment WHERE tenant_id = ? AND employee_id = ?
                """, Integer.class, scope.tenant(), scope.employee())));
    }

    @Test
    void assignmentAndAuditForeignKeysKeepEmployeesOrganizationsAndActorsInTheirTenant() {
        Scope own = scope();
        Scope other = scope();
        for (Scope scope : List.of(own, other)) {
            transactions.execute(scope.tenant(), () -> organization(scope, scope.organization(), "ROOT", null,
                    scope.actor()));
        }
        rejects("23503", own, () -> assignment(own, other.employee(), own.organization(), "2026-01-01", null));
        rejects("23503", own, () -> assignment(own, own.employee(), other.organization(), "2026-01-01", null));
        rejects("23503", own, () -> app.update("""
                INSERT INTO tna.org_audit_event (tenant_id, id, command_id, actor_account_id, action,
                    target_kind, target_id, input_hash, reason, before_state, after_state, request_trace_id)
                VALUES (?, ?, ?, ?, 'CREATE', 'ORGANIZATION', ?, ?, '테스트', '{}'::jsonb, '{}'::jsonb, ?)
                """, own.tenant(), UUID.randomUUID(), UUID.randomUUID(), other.actor(), own.organization(),
                "a".repeat(64), UUID.randomUUID()));
        rejects("23503", own, () -> app.update("""
                INSERT INTO tna.employee (tenant_id, id, employee_no, display_name, joined_on,
                    created_by, updated_by)
                VALUES (?, ?, 'ACTOR-FK', '합성 직원', DATE '2026-01-01', ?, ?)
                """, own.tenant(), UUID.randomUUID(), other.actor(), other.actor()));
    }

    @Test
    void runtimeCanOnlyUpdateApprovedBusinessColumnsAndAuditIsAppendOnly() {
        Scope scope = scope();
        UUID command = UUID.randomUUID();
        transactions.execute(scope.tenant(), () -> {
            organization(scope, scope.organization(), "ROOT", null, scope.actor());
            assignment(scope, scope.employee(), scope.organization(), "2026-01-01", null);
            audit(scope, command);
            assertEquals(1, app.update("""
                    UPDATE tna.organization SET name = '수정 이름', active = true, version = version + 1,
                        updated_at = CURRENT_TIMESTAMP, updated_by = ? WHERE tenant_id = ? AND id = ?
                    """, scope.actor(), scope.tenant(), scope.organization()));
            assertEquals(1, app.update("""
                    UPDATE tna.employee SET display_name = '수정 직원', joined_on = DATE '2026-01-01',
                        left_on = DATE '2027-12-31', active = true, version = version + 1,
                        updated_at = CURRENT_TIMESTAMP, updated_by = ? WHERE tenant_id = ? AND id = ?
                    """, scope.actor(), scope.tenant(), scope.employee()));
            assertEquals(1, app.update("""
                    UPDATE tna.employee_assignment SET valid_to = DATE '2027-01-01', version = version + 1,
                        updated_at = CURRENT_TIMESTAMP, updated_by = ? WHERE tenant_id = ? AND employee_id = ?
                    """, scope.actor(), scope.tenant(), scope.employee()));
            Map<String, Object> row = app.queryForMap("""
                    SELECT created_at, created_by, updated_at, updated_by, version FROM tna.organization
                    WHERE tenant_id = ? AND id = ?
                    """, scope.tenant(), scope.organization());
            assertNotNull(row.get("created_at"));
            assertNotNull(row.get("updated_at"));
            assertEquals(scope.actor(), row.get("created_by"));
            assertEquals(scope.actor(), row.get("updated_by"));
            assertEquals(1L, row.get("version"));
            return null;
        });
        rejects("42501", scope, () -> app.update("UPDATE tna.organization SET code = 'CHANGED'"));
        rejects("42501", scope, () -> app.update("UPDATE tna.employee SET employee_no = 'CHANGED'"));
        rejects("42501", scope, () -> app.update("UPDATE tna.employee_assignment SET valid_from = DATE '2025-01-01'"));
        rejects("42501", scope, () -> app.update("UPDATE tna.org_audit_event SET reason = 'CHANGED'"));
        for (String table : List.of("organization", "employee", "employee_assignment", "org_audit_event")) {
            rejects("42501", scope, () -> app.update("DELETE FROM tna." + table));
            rejects("42501", scope, () -> app.execute("TRUNCATE tna." + table));
        }
        rejects("23505", scope, () -> audit(scope, command));
    }

    @Test
    void runtimeReadsAccountMetadataWhilePasswordsAndRoleIdentitiesStayProtected() {
        Scope scope = scope();
        transactions.execute(scope.tenant(), () -> {
            assertEquals(1, app.queryForList("""
                    SELECT tenant_id, id, employee_id, email, active, credential_set, auth_version FROM tna.tenant_account
                    WHERE tenant_id = ? AND id = ?
                    """, scope.tenant(), scope.actor()).size());
            assertEquals(List.of("ATTENDANCE_ADMIN"), app.queryForList("""
                    SELECT role FROM tna.role_grant WHERE tenant_id = ? AND account_id = ?
                    """, String.class, scope.tenant(), scope.actor()));
            assertEquals(1, app.update("""
                    UPDATE tna.tenant_account SET auth_version = auth_version + 1
                    WHERE tenant_id = ? AND id = ?
                    """, scope.tenant(), scope.actor()));
            return null;
        });
        rejects("42501", scope, () -> app.queryForList("SELECT password_hash FROM tna.tenant_account"));
        rejects("42501", scope, () -> app.update("UPDATE tna.tenant_account SET active = false"));
        rejects("42501", scope, () -> app.update("UPDATE tna.tenant_account SET password_hash = 'synthetic'"));
        rejects("42501", scope, () -> app.update("""
                INSERT INTO tna.tenant_account (tenant_id, id, employee_id, email, password_hash)
                VALUES (?, ?, ?, 'blocked@example.test', 'synthetic')
                """, scope.tenant(), UUID.randomUUID(), scope.employee()));
        rejects("42501", scope, () -> app.update("UPDATE tna.role_grant SET role = 'EMPLOYEE'"));
        assertFalse(admin.queryForObject("""
                SELECT has_table_privilege('tna_app', 'tna.tenant_account', 'SELECT')
                """, Boolean.class));
        assertTrue(admin.queryForObject("""
                SELECT has_column_privilege('tna_app', 'tna.tenant_account', 'auth_version', 'UPDATE')
                """, Boolean.class));
    }

    @Test
    void authenticationRoleCannotWriteEmployeesOrReadOrganizationBusinessTables() {
        Scope scope = scope();
        JdbcTemplate auth = new JdbcTemplate(database.authDataSource());
        TenantTransactions authTransactions = new TenantTransactions(database.authDataSource());
        assertThrows(DataAccessException.class, () -> authTransactions.execute(scope.tenant(), () ->
                auth.update("UPDATE tna.employee SET active = false")));
        for (String table : List.of("organization", "employee_assignment", "org_audit_event")) {
            assertThrows(DataAccessException.class, () -> authTransactions.execute(scope.tenant(), () ->
                    auth.queryForObject("SELECT count(*) FROM tna." + table, Integer.class)));
        }
    }

    private Scope scope() {
        Scope scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        admin.update("""
                INSERT INTO platform.tenant_registry (id, company_code, display_name)
                VALUES (?, ?, '합성 DB 테스트 고객사')
                """, scope.tenant(), "SCHEMA-" + scope.tenant());
        admin.update("""
                INSERT INTO tna.employee (tenant_id, id, employee_no, display_name, joined_on)
                VALUES (?, ?, 'E001', '합성 DB 테스트 직원', DATE '2026-01-01')
                """, scope.tenant(), scope.employee());
        admin.update("""
                INSERT INTO tna.tenant_account (tenant_id, id, employee_id, email, password_hash)
                VALUES (?, ?, ?, 'schema@example.test', 'synthetic-schema-test-hash')
                """, scope.tenant(), scope.actor(), scope.employee());
        admin.update("INSERT INTO tna.role_grant (tenant_id, account_id, role) VALUES (?, ?, 'ATTENDANCE_ADMIN')",
                scope.tenant(), scope.actor());
        return scope;
    }

    private int organization(Scope scope, UUID id, String code, UUID parent, UUID actor) {
        return app.update("""
                INSERT INTO tna.organization (tenant_id, id, code, name, parent_id, created_by, updated_by)
                VALUES (?, ?, ?, '합성 조직', ?, ?, ?)
                """, scope.tenant(), id, code, parent, actor, actor);
    }

    private int assignment(Scope scope, UUID employee, UUID organization, String from, String to) {
        return app.update("""
                INSERT INTO tna.employee_assignment (tenant_id, id, employee_id, organization_id,
                    valid_from, valid_to, reason, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?::date, ?::date, '합성 소속 이력', ?, ?)
                """, scope.tenant(), UUID.randomUUID(), employee, organization, from, to, scope.actor(), scope.actor());
    }

    private int audit(Scope scope, UUID command) {
        return app.update("""
                INSERT INTO tna.org_audit_event (tenant_id, id, command_id, actor_account_id, action,
                    target_kind, target_id, input_hash, reason, before_state, after_state, request_trace_id)
                VALUES (?, ?, ?, ?, 'CREATE', 'ORGANIZATION', ?, ?, '합성 감사', '{}'::jsonb, '{}'::jsonb, ?)
                """, scope.tenant(), UUID.randomUUID(), command, scope.actor(), scope.organization(),
                "a".repeat(64), UUID.randomUUID());
    }

    private void rejects(String state, Scope scope, Runnable action) {
        DataAccessException error = assertThrows(DataAccessException.class, () ->
                transactions.execute(scope.tenant(), () -> {
                    action.run();
                    return null;
                }));
        assertEquals(state, ((SQLException) error.getMostSpecificCause()).getSQLState());
    }

    private record Scope(UUID tenant, UUID employee, UUID actor, UUID organization) {}
}
