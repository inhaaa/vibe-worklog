package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountAccessSchemaIT {
    private static JdbcTemplate admin;
    private static JdbcTemplate app;
    private static JdbcTemplate auth;
    private static TenantTransactions appTransactions;
    private static TenantTransactions authTransactions;

    @BeforeAll
    static void database() {
        DatabaseFixture database = DatabaseFixture.start();
        admin = new JdbcTemplate(database.adminDataSource());
        app = new JdbcTemplate(database.appDataSource());
        auth = new JdbcTemplate(database.authDataSource());
        appTransactions = new TenantTransactions(database.appDataSource());
        authTransactions = new TenantTransactions(database.authDataSource());
    }

    @Test
    void accountTokenSchemaExists() {
        assertNotNull(admin.queryForObject("SELECT to_regclass('tna.account_token')::text", String.class));
    }

    @Test
    void pendingAccountsExposeCredentialStateWithoutExposingOrWritingPasswords() {
        Scope scope = scope();
        UUID pending = pending(scope);
        appTransactions.execute(scope.tenant(), () -> {
            assertFalse(app.queryForObject("""
                    SELECT credential_set FROM tna.tenant_account WHERE tenant_id = ? AND id = ?
                    """, Boolean.class, scope.tenant(), pending));
            assertTrue(app.queryForObject("""
                    SELECT credential_set FROM tna.tenant_account WHERE tenant_id = ? AND id = ?
                    """, Boolean.class, scope.tenant(), scope.actor()));
            assertEquals("pending@example.test", app.queryForObject("""
                    SELECT email FROM tna.tenant_account WHERE tenant_id = ? AND id = ?
                    """, String.class, scope.tenant(), pending));
            return null;
        });
        rejects("42501", appTransactions, scope, () -> app.queryForList("SELECT password_hash FROM tna.tenant_account"));
        rejects("42501", appTransactions, scope, () -> app.update("""
                UPDATE tna.tenant_account SET password_hash = 'blocked' WHERE tenant_id = ? AND id = ?
                """, scope.tenant(), pending));
        rejects("23514", authTransactions, scope, () -> auth.update("""
                UPDATE tna.tenant_account SET active = true WHERE tenant_id = ? AND id = ?
                """, scope.tenant(), pending));
        authTransactions.execute(scope.tenant(), () -> auth.update("""
                UPDATE tna.tenant_account SET password_hash = 'synthetic-accepted-hash', active = true,
                    auth_version = auth_version + 1 WHERE tenant_id = ? AND id = ?
                """, scope.tenant(), pending));
        assertTrue(admin.queryForObject("SELECT credential_set FROM tna.tenant_account WHERE tenant_id = ? AND id = ?",
                Boolean.class, scope.tenant(), pending));
        assertTrue(admin.queryForObject("SELECT active FROM tna.tenant_account WHERE tenant_id = ? AND id = ?",
                Boolean.class, scope.tenant(), scope.actor()));
    }

    @Test
    void runtimeCanGrantAndRevokeSupportedRolesButCannotRewriteRoleIdentity() {
        Scope scope = scope();
        appTransactions.execute(scope.tenant(), () -> {
            assertEquals(1, app.update("""
                    INSERT INTO tna.role_grant (tenant_id, account_id, role) VALUES (?, ?, 'APPROVER')
                    """, scope.tenant(), scope.actor()));
            assertTrue(app.queryForObject("""
                    SELECT active FROM tna.role_grant WHERE tenant_id = ? AND account_id = ? AND role = 'APPROVER'
                    """, Boolean.class, scope.tenant(), scope.actor()));
            assertEquals(1, app.update("""
                    UPDATE tna.role_grant SET active = false WHERE tenant_id = ? AND account_id = ? AND role = 'APPROVER'
                    """, scope.tenant(), scope.actor()));
            return null;
        });
        rejects("42501", appTransactions, scope, () -> app.update("UPDATE tna.role_grant SET role = 'EMPLOYEE'"));
        rejects("23514", appTransactions, scope, () -> app.update("""
                INSERT INTO tna.role_grant (tenant_id, account_id, role) VALUES (?, ?, 'PLATFORM_OPERATOR')
                """, scope.tenant(), scope.actor()));
    }

    @Test
    void tokenMetadataAndOneTimeConsumptionHaveSeparateRuntimePermissions() {
        Scope scope = scope();
        UUID token = UUID.randomUUID();
        appTransactions.execute(scope.tenant(), () -> token(scope, token, scope.actor(), scope.actor(), "INVITE"));
        appTransactions.execute(scope.tenant(), () -> {
            assertEquals(1, app.queryForList("""
                    SELECT tenant_id, id, account_id, purpose, email, issued_auth_version,
                        expires_at, used_at, revoked_at, created_at, created_by FROM tna.account_token
                    WHERE tenant_id = ? AND id = ?
                    """, scope.tenant(), token).size());
            return null;
        });
        rejects("42501", appTransactions, scope, () -> app.queryForList("SELECT token_hash FROM tna.account_token"));
        rejects("42501", appTransactions, scope, () -> app.update("UPDATE tna.account_token SET used_at = now()"));
        authTransactions.execute(scope.tenant(), () -> {
            assertNotNull(auth.queryForObject("""
                    SELECT token_hash FROM tna.account_token WHERE tenant_id = ? AND id = ?
                    """, String.class, scope.tenant(), token));
            assertEquals(1, auth.update("""
                    UPDATE tna.account_token SET used_at = now() WHERE tenant_id = ? AND id = ? AND used_at IS NULL
                    """, scope.tenant(), token));
            assertEquals(0, auth.update("""
                    UPDATE tna.account_token SET used_at = now() WHERE tenant_id = ? AND id = ? AND used_at IS NULL
                    """, scope.tenant(), token));
            return null;
        });
        rejects("42501", authTransactions, scope, () -> auth.update("UPDATE tna.account_token SET revoked_at = now()"));
        appTransactions.execute(scope.tenant(), () -> app.update("""
                UPDATE tna.account_token SET revoked_at = now() WHERE tenant_id = ? AND id = ?
                """, scope.tenant(), token));
    }

    @Test
    void tokenPurposeHashAndCompositeReferencesAreChecked() {
        Scope own = scope();
        Scope other = scope();
        rejects("23503", appTransactions, own, () -> token(own, UUID.randomUUID(), other.actor(), own.actor(), "INVITE"));
        rejects("23503", appTransactions, own, () -> token(own, UUID.randomUUID(), own.actor(), other.actor(), "INVITE"));
        rejects("23514", appTransactions, own, () -> token(own, UUID.randomUUID(), own.actor(), own.actor(), "UNKNOWN"));
        rejects("23514", appTransactions, own, () -> app.update("""
                INSERT INTO tna.account_token (tenant_id, id, account_id, purpose, token_hash, email,
                    issued_auth_version, expires_at, created_by)
                VALUES (?, ?, ?, 'INVITE', 'not-a-hash', 'actor@example.test', 1, now() + interval '24 hours', ?)
                """, own.tenant(), UUID.randomUUID(), own.actor(), own.actor()));
        appTransactions.execute(own.tenant(), () -> token(own, UUID.randomUUID(), own.actor(), own.actor(), "RESET"));
    }

    @Test
    void newTablesIsolateTenantReadsWritesAndReusedConnections() {
        Scope own = scope();
        Scope other = scope();
        for (Scope scope : List.of(own, other)) {
            appTransactions.execute(scope.tenant(), () -> {
                token(scope, UUID.randomUUID(), scope.actor(), scope.actor(), "INVITE");
                assignment(scope, UUID.randomUUID(), scope.target(), scope.approver(), "2026-01-01", null, scope.actor());
                return null;
            });
        }
        for (String table : List.of("account_token", "approver_assignment")) {
            assertEquals(0, app.queryForObject("SELECT count(*) FROM tna." + table, Integer.class));
            assertEquals(List.of(own.tenant()), appTransactions.execute(own.tenant(), () ->
                    app.queryForList("SELECT tenant_id FROM tna." + table, UUID.class)));
            assertEquals(List.of(other.tenant()), appTransactions.execute(other.tenant(), () ->
                    app.queryForList("SELECT tenant_id FROM tna." + table, UUID.class)));
            assertEquals(0, app.queryForObject("SELECT count(*) FROM tna." + table, Integer.class));
        }
        assertEquals(0, auth.queryForObject("SELECT count(*) FROM tna.account_token", Integer.class));
        assertEquals(List.of(own.tenant()), authTransactions.execute(own.tenant(), () ->
                auth.queryForList("SELECT tenant_id FROM tna.account_token", UUID.class)));
        rejects("42501", appTransactions, own, () -> token(other, UUID.randomUUID(), other.actor(), other.actor(), "INVITE"));
        rejects("42501", appTransactions, own, () -> assignment(other, UUID.randomUUID(), other.target(),
                other.approver(), "2027-01-01", null, other.actor()));
    }

    @Test
    void approverRangesRejectSelfCrossTenantAndOverlapWhileKeepingSupersededHistory() {
        Scope scope = scope();
        Scope other = scope();
        UUID current = UUID.randomUUID();
        appTransactions.execute(scope.tenant(), () -> {
            assignment(scope, UUID.randomUUID(), scope.target(), scope.approver(), "2026-01-01", "2026-02-01", scope.actor());
            assignment(scope, current, scope.target(), scope.approver(), "2026-02-01", null, scope.actor());
            return null;
        });
        rejects("23514", appTransactions, scope, () -> assignment(scope, UUID.randomUUID(), scope.target(),
                scope.target(), "2027-01-01", null, scope.actor()));
        rejects("23514", appTransactions, scope, () -> assignment(scope, UUID.randomUUID(), scope.approver(),
                scope.target(), "2026-03-01", "2026-03-01", scope.actor()));
        rejects("23P01", appTransactions, scope, () -> assignment(scope, UUID.randomUUID(), scope.target(),
                scope.actorEmployee(), "2026-02-01", null, scope.actor()));
        rejects("23503", appTransactions, scope, () -> assignment(scope, UUID.randomUUID(), other.target(),
                scope.approver(), "2026-01-01", null, scope.actor()));
        rejects("23503", appTransactions, scope, () -> assignment(scope, UUID.randomUUID(), scope.approver(),
                other.target(), "2026-01-01", null, scope.actor()));
        rejects("23503", appTransactions, scope, () -> assignment(scope, UUID.randomUUID(), scope.approver(),
                scope.target(), "2026-01-01", null, other.actor()));
        appTransactions.execute(scope.tenant(), () -> {
            assertEquals(1, app.update("""
                    UPDATE tna.approver_assignment SET superseded_at = now(), version = version + 1,
                        updated_at = now(), updated_by = ? WHERE tenant_id = ? AND id = ?
                    """, scope.actor(), scope.tenant(), current));
            assignment(scope, UUID.randomUUID(), scope.target(), scope.actorEmployee(), "2026-02-01", null, scope.actor());
            assertEquals(3, app.queryForObject("SELECT count(*) FROM tna.approver_assignment WHERE tenant_id = ?",
                    Integer.class, scope.tenant()));
            return null;
        });
        rejects("42501", appTransactions, scope, () -> app.update("UPDATE tna.approver_assignment SET reason = 'changed'"));
        rejects("42501", appTransactions, scope, () -> app.update("UPDATE tna.approver_assignment SET valid_from = DATE '2025-01-01'"));
    }

    @Test
    void authenticationMayOnlyAppendItsOwnAcceptanceAuditWithoutReadingBusinessAudit() {
        Scope scope = scope();
        Scope other = scope();
        authTransactions.execute(scope.tenant(), () -> audit(scope, scope.actor(), scope.actor(), "ACCOUNT_INVITE_ACCEPT", "ACCOUNT"));
        rejects("42501", authTransactions, scope, () -> auth.queryForList("SELECT * FROM tna.org_audit_event"));
        rejects("42501", authTransactions, scope, () -> audit(scope, scope.actor(), scope.actor(), "ROLE_CHANGE", "ACCOUNT"));
        rejects("42501", authTransactions, scope, () -> audit(scope, scope.actor(), scope.actor(), "ACCOUNT_INVITE_ACCEPT", "EMPLOYEE"));
        rejects("42501", authTransactions, scope, () -> audit(scope, scope.actor(), scope.target(), "ACCOUNT_INVITE_ACCEPT", "ACCOUNT"));
        rejects("42501", authTransactions, scope, () -> audit(other, other.actor(), other.actor(), "ACCOUNT_INVITE_ACCEPT", "ACCOUNT"));
    }

    @Test
    void newRuntimeGrantsNeverAllowDeletesOrUnrelatedAuthenticationWrites() {
        Scope scope = scope();
        for (String table : List.of("tenant_account", "role_grant", "account_token", "approver_assignment", "org_audit_event")) {
            for (TenantTransactions transactions : List.of(appTransactions, authTransactions)) {
                JdbcTemplate jdbc = transactions == appTransactions ? app : auth;
                rejects("42501", transactions, scope, () -> jdbc.update("DELETE FROM tna." + table));
                rejects("42501", transactions, scope, () -> jdbc.execute("TRUNCATE tna." + table));
            }
        }
        rejects("42501", authTransactions, scope, () -> auth.update("UPDATE tna.tenant_account SET email = 'blocked@example.test'"));
        rejects("42501", authTransactions, scope, () -> auth.update("UPDATE tna.role_grant SET active = false"));
        rejects("42501", authTransactions, scope, () -> auth.queryForList("SELECT * FROM tna.approver_assignment"));
    }

    private Scope scope() {
        Scope scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        admin.update("INSERT INTO platform.tenant_registry (id, company_code, display_name) VALUES (?, ?, '합성 고객사')",
                scope.tenant(), "ACCESS-SCHEMA-" + scope.tenant());
        int number = 0;
        for (UUID employee : List.of(scope.actorEmployee(), scope.target(), scope.approver())) {
            admin.update("""
                    INSERT INTO tna.employee (tenant_id, id, employee_no, display_name, joined_on)
                    VALUES (?, ?, ?, '합성 사원', DATE '2026-01-01')
                    """, scope.tenant(), employee, "E" + ++number);
        }
        admin.update("""
                INSERT INTO tna.tenant_account (tenant_id, id, employee_id, email, password_hash)
                VALUES (?, ?, ?, 'actor@example.test', 'synthetic-schema-hash')
                """, scope.tenant(), scope.actor(), scope.actorEmployee());
        return scope;
    }

    private UUID pending(Scope scope) {
        UUID account = UUID.randomUUID();
        appTransactions.execute(scope.tenant(), () -> app.update("""
                INSERT INTO tna.tenant_account (tenant_id, id, employee_id, email, active)
                VALUES (?, ?, ?, 'pending@example.test', false)
                """, scope.tenant(), account, scope.target()));
        return account;
    }

    private int token(Scope scope, UUID id, UUID account, UUID actor, String purpose) {
        return app.update("""
                INSERT INTO tna.account_token (tenant_id, id, account_id, purpose, token_hash, email,
                    issued_auth_version, expires_at, created_by)
                VALUES (?, ?, ?, ?, ?, 'actor@example.test', 1, now() + interval '24 hours', ?)
                """, scope.tenant(), id, account, purpose, id.toString().replace("-", "").repeat(2), actor);
    }

    private int assignment(Scope scope, UUID id, UUID employee, UUID approver, String from, String to, UUID actor) {
        return app.update("""
                INSERT INTO tna.approver_assignment (tenant_id, id, employee_id, approver_employee_id,
                    valid_from, valid_to, reason, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?::date, ?::date, '합성 승인 관계', ?, ?)
                """, scope.tenant(), id, employee, approver, from, to, actor, actor);
    }

    private int audit(Scope scope, UUID actor, UUID target, String action, String kind) {
        return auth.update("""
                INSERT INTO tna.org_audit_event (tenant_id, id, command_id, actor_account_id, action,
                    target_kind, target_id, input_hash, reason, before_state, after_state, request_trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '합성 수락 감사', '{}'::jsonb, '{}'::jsonb, ?)
                """, scope.tenant(), UUID.randomUUID(), UUID.randomUUID(), actor, action, kind, target,
                "a".repeat(64), UUID.randomUUID());
    }

    private void rejects(String state, TenantTransactions transactions, Scope scope, Runnable action) {
        DataAccessException error = assertThrows(DataAccessException.class, () -> transactions.execute(scope.tenant(), () -> {
            action.run();
            return null;
        }));
        assertEquals(state, ((SQLException) error.getMostSpecificCause()).getSQLState());
    }

    private record Scope(UUID tenant, UUID actorEmployee, UUID actor, UUID target, UUID approver) { }
}
