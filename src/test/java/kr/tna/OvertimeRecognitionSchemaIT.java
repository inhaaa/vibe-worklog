package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class OvertimeRecognitionSchemaIT {
    JdbcTemplate db;
    UUID tenant, employee, run;

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); run = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 연장인정')", tenant, "OTREC-" + tenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'R1','합성 사원',DATE '2026-01-01')", tenant, employee);
        db.update("INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version) VALUES (?,?,?,NULL,DATE '2026-11-23',DATE '2026-11-23',DATE '2026-11-23',DATE '2026-11-23','CALC-3')",
                tenant, run, employee);
    }

    private int result(int revision, String rule, Long approvedOvertime) {
        return db.update("""
            INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
              source_fingerprint,rule_version,status,leave_days,reason_codes,input_refs,approved_overtime_millis)
            VALUES (?,?,?,?,DATE '2026-11-23',?,1,0,?,?,'CALCULATED',0,'[]'::jsonb,'[]'::jsonb,?)""",
            tenant, UUID.randomUUID(), run, employee, revision, "c".repeat(64), rule, approvedOvertime);
    }

    @Test void calc3ResultsCarryApprovedOvertimeAndOlderRulesStayValid() {
        assertEquals(1, result(1, "CALC-3", 7_200_000L));
        assertEquals(1, result(2, "CALC-2", null));
        assertThrows(DataIntegrityViolationException.class, () -> result(3, "CALC-3", -1L));
        assertThrows(DataIntegrityViolationException.class, () -> result(4, "CALC-9", null));
    }

    @Test void monthCloseRowsHavePayrollColumns() {
        assertEquals(10, db.queryForObject("""
            SELECT count(*) FROM information_schema.columns WHERE table_schema='tna' AND table_name='attendance_month_export_row'
              AND column_name IN ('payroll_captured','approved_overtime_millis','unapproved_extra_millis','night_work_millis',
                'holiday_work_millis','late_count','late_millis','early_leave_count','early_leave_millis','absence_days')""", Integer.class));
        assertEquals("false", db.queryForObject("""
            SELECT column_default FROM information_schema.columns WHERE table_schema='tna' AND table_name='attendance_month_export_row'
              AND column_name='payroll_captured'""", String.class));
    }
}
