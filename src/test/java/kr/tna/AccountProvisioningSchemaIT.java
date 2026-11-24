package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountProvisioningSchemaIT {
    private final DatabaseFixture f = DatabaseFixture.start();
    private final JdbcTemplate admin = new JdbcTemplate(f.adminDataSource());
    private UUID tenant, employee;

    @BeforeEach void seed() {
        tenant = UUID.randomUUID(); employee = UUID.randomUUID();
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 계정 고객사')",
                tenant, "PROV-" + tenant);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'26001','합성 사원',DATE '2020-01-01')",
                tenant, employee);
        UUID updaterEmployee = UUID.randomUUID(), updater = UUID.randomUUID();
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'26000','합성 담당자',DATE '2020-01-01')",
                tenant, updaterEmployee);
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,active) VALUES (?,?,?,'updater@example.test',false)",
                tenant, updater, updaterEmployee);
        admin.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,version,updated_by) VALUES (?,?,DATE '1990-05-12',1,?)",
                tenant, employee, updater);
    }

    @Test void appCreatesPendingAccountWithoutEmailOrPassword() {
        UUID account = UUID.randomUUID();
        as(f.appDataSource(), db -> db.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,activation_expires_at)
                VALUES (?,?,?,false,?)""", tenant, account, employee, Timestamp.from(Instant.now().plusSeconds(3600))));
        assertNull(admin.queryForObject("SELECT email FROM tna.tenant_account WHERE id=?", String.class, account));
        assertEquals(0, admin.queryForObject("SELECT activation_failures FROM tna.tenant_account WHERE id=?", Integer.class, account));
        as(f.appDataSource(), db -> assertEquals(1, db.update(
                "UPDATE tna.tenant_account SET activation_expires_at=now(),activation_failures=0 WHERE tenant_id=? AND id=?", tenant, account)));
        assertEquals("42501", sqlState(() -> as(f.appDataSource(), db -> db.update(
                "UPDATE tna.tenant_account SET password_hash='x' WHERE tenant_id=? AND id=?", tenant, account))));
        assertEquals("42501", sqlState(() -> as(f.appDataSource(), db -> db.update(
                "UPDATE tna.tenant_account SET active=true WHERE tenant_id=? AND id=?", tenant, account))));
    }

    @Test void authReadsBirthDateOnlyInsideTenantAndCountsFailures() {
        UUID account = UUID.randomUUID();
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,activation_expires_at) VALUES (?,?,?,false,now()+interval '1 day')",
                tenant, account, employee);
        as(f.authDataSource(), db -> {
            assertEquals("1990-05-12", db.queryForObject(
                    "SELECT birth_date::text FROM tna.employee_personnel WHERE tenant_id=? AND employee_id=?", String.class, tenant, employee));
            assertEquals(1, db.update("UPDATE tna.tenant_account SET activation_failures=activation_failures+1 WHERE tenant_id=? AND id=?", tenant, account));
        });
        assertEquals(0, asTenant(f.authDataSource(), UUID.randomUUID(), db -> db.queryForObject(
                "SELECT count(*) FROM tna.employee_personnel", Integer.class)).intValue());
        assertEquals("42501", sqlState(() -> as(f.authDataSource(), db -> db.queryForList(
                "SELECT grade_name, photo_bytes FROM tna.employee_personnel"))));
        assertEquals("42501", sqlState(() -> as(f.authDataSource(), db -> db.update(
                "UPDATE tna.employee_personnel SET birth_date=DATE '1990-01-01' WHERE tenant_id=?", tenant))));
        assertEquals("23514", sqlState(() -> admin.update(
                "UPDATE tna.tenant_account SET activation_failures=-1 WHERE id=?", account)));
        admin.update("UPDATE tna.tenant_account SET activation_expires_at=NULL WHERE id=?", account);
        assertEquals(0, asTenant(f.authDataSource(), tenant, db -> db.queryForObject(
                "SELECT count(*) FROM tna.employee_personnel", Integer.class)).intValue());
        assertThrows(DataAccessException.class, () -> admin.update(
                "UPDATE tna.tenant_account SET activation_failures=6 WHERE id=?", account));
    }

    @Test void onboardingStoresOptionalBirthDate() {
        assertEquals(1, admin.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='tna' AND table_name='employee_onboarding' AND column_name='birth_date'""", Integer.class));
        assertTrue(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.employee_onboarding','birth_date','INSERT')", Boolean.class));
        assertTrue(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.employee_onboarding','birth_date','UPDATE')", Boolean.class));
    }

    @Test void authAuditsActivationOnlyForTheAccountItself() {
        UUID account = UUID.randomUUID();
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,activation_expires_at) VALUES (?,?,?,false,now()+interval '1 day')",
                tenant, account, employee);
        for (String action : new String[] {"ACCOUNT_ACTIVATED", "ACCOUNT_ACTIVATION_FAILED", "ACCOUNT_LOCKED"}) {
            as(f.authDataSource(), db -> assertEquals(1, audit(db, action, account, account)));
        }
        assertEquals("42501", sqlState(() -> as(f.authDataSource(), db -> audit(db, "ACCOUNT_ROLES_CHANGE", account, account))));
        assertEquals("42501", sqlState(() -> as(f.authDataSource(), db -> audit(db, "ACCOUNT_ACTIVATED", account, UUID.randomUUID()))));
    }

    private static int audit(JdbcTemplate db, String action, UUID actor, UUID target) {
        return db.update("""
                INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,
                    input_hash,reason,before_state,after_state,request_trace_id)
                VALUES (current_setting('app.tenant_id')::uuid,?,?,?,?,'ACCOUNT',?,?,'최초 접속','{}'::jsonb,'{}'::jsonb,?)""",
                UUID.randomUUID(), UUID.randomUUID(), actor, action, target, "a".repeat(64), UUID.randomUUID());
    }

    private static String sqlState(Runnable work) {
        DataAccessException error = assertThrows(DataAccessException.class, work::run);
        return ((java.sql.SQLException) error.getMostSpecificCause()).getSQLState();
    }

    private void as(DataSource source, Consumer<JdbcTemplate> work) { asTenant(source, tenant, db -> { work.accept(db); return 0; }); }

    private <T> T asTenant(DataSource source, UUID scope, java.util.function.Function<JdbcTemplate, T> work) {
        var db = new JdbcTemplate(source);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> {
            db.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, scope.toString());
            return work.apply(db);
        });
    }
}
