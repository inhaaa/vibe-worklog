package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class DailyAttendanceSchemaIT {
    JdbcTemplate db;
    UUID tenant, employee, account;

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); account = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 일일근태')", tenant, "DAILY-" + tenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'D1','합성 사원',DATE '2026-01-01')", tenant, employee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-hash')", tenant, account, employee, account + "@example.test");
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required) VALUES (?,?,DATE '2026-11-23',1,0,true)", tenant, employee);
    }

    private int confirm(String method, UUID by) {
        return db.update("""
            INSERT INTO tna.work_confirmation(tenant_id,id,employee_id,work_date,confirmed_input_revision,review_revision,
              source_fingerprint,presence_as_work,breaks_match,confirmed_by,break_snapshot,client_command_id,method)
            VALUES (?,?,?,DATE '2026-11-23',1,1,?,true,true,?,'[]'::jsonb,?,?)
            """, tenant, UUID.randomUUID(), employee, "a".repeat(64), by, UUID.randomUUID(), method);
    }

    @Test void autoConfirmationMayHaveNoAccountButEmployeeConfirmationMust() {
        assertThrows(DataIntegrityViolationException.class, () -> confirm("EMPLOYEE", null));
        assertThrows(DataIntegrityViolationException.class, () -> confirm("GUESS", account));
        assertEquals(1, confirm("AUTO_SCHEDULE", null));
    }

    @Test void existingConfirmationsDefaultToEmployeeAndResultColumnsExist() {
        assertEquals("EMPLOYEE", db.queryForObject(
            "SELECT column_default FROM information_schema.columns WHERE table_schema='tna' AND table_name='work_confirmation' AND column_name='method'",
            String.class).replace("::character varying", "").replace("'", ""));
        assertEquals(3, db.queryForObject("""
            SELECT count(*) FROM information_schema.columns WHERE table_schema='tna' AND table_name='day_result'
              AND column_name IN ('late_millis','early_leave_millis','absence')""", Integer.class));
        assertEquals("YES", db.queryForObject("""
            SELECT is_nullable FROM information_schema.columns WHERE table_schema='tna' AND table_name='calculation_run' AND column_name='requested_by'""", String.class));
    }

    @Test void resultAttendanceValuesAreChecked() {
        UUID run = UUID.randomUUID();
        db.update("INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version) VALUES (?,?,?,NULL,DATE '2026-11-23',DATE '2026-11-23',DATE '2026-11-23',DATE '2026-11-23','CALC-2')",
                tenant, run, employee);
        String insert = """
            INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
              source_fingerprint,rule_version,status,leave_days,reason_codes,input_refs,late_millis,early_leave_millis,absence)
            VALUES (?,?,?,?,DATE '2026-11-23',?,1,0,?,'CALC-2','CALCULATED',0,'[]'::jsonb,'[]'::jsonb,?,?,?)""";
        assertEquals(1, db.update(insert, tenant, UUID.randomUUID(), run, employee, 1, "b".repeat(64), 0L, 0L, null));
        assertThrows(DataIntegrityViolationException.class, () -> db.update(insert, tenant, UUID.randomUUID(), run, employee, 2, "b".repeat(64), -1L, 0L, null));
        assertThrows(DataIntegrityViolationException.class, () -> db.update(insert, tenant, UUID.randomUUID(), run, employee, 3, "b".repeat(64), null, null, "SOME"));
    }
}
