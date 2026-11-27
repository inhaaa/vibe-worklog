package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class StatutoryLeaveSchemaIT {
    JdbcTemplate db;
    UUID tenant, employee, account, bucket;

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID(); employee = UUID.randomUUID(); account = UUID.randomUUID(); bucket = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 법정 연차')", tenant, "STAT-" + tenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'S1','합성 사원',DATE '2024-07-01')", tenant, employee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic')", tenant, account, employee, account + "@example.test");
        db.update("INSERT INTO tna.annual_leave_rule(tenant_id,id,version,fiscal_start_month,grant_days,carry_limit_days,created_by) VALUES (?,?,1,1,15,0,?)",
                tenant, UUID.randomUUID(), account);
        db.update("INSERT INTO tna.annual_leave_cycle(tenant_id,id,entitlement_year,rule_version,created_by) VALUES (?,?,2025,1,?)", tenant, UUID.randomUUID(), account);
        db.update("""
                INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,usable_to,source_reference,created_by)
                VALUES (?,?,?,DATE '2025-01-01',DATE '2025-01-01',DATE '2025-12-31','합성 부여',?)""", tenant, bucket, employee, account);
    }

    private int rule(int version, String basis) {
        return db.update("INSERT INTO tna.annual_leave_rule(tenant_id,id,version,fiscal_start_month,grant_days,carry_limit_days,basis,created_by) VALUES (?,?,?,1,15,0,?,?)",
                tenant, UUID.randomUUID(), version, basis, account);
    }

    private int award(Integer serviceYear, String start, String end, Integer scheduled, String absence, String granted) {
        return award(serviceYear, start, end, scheduled, absence, granted, false);
    }

    private int award(Integer serviceYear, String start, String end, Integer scheduled, String absence, String granted, boolean reviewed) {
        return db.update("""
                INSERT INTO tna.annual_leave_award(tenant_id,entitlement_year,employee_id,bucket_id,created_by,
                    service_year,period_start,period_end,scheduled_days,absence_days,granted_days,reviewed_by_admin)
                VALUES (?,2025,?,?,?,?,CAST(? AS date),CAST(? AS date),?,CAST(? AS numeric),CAST(? AS numeric),?)""",
                tenant, employee, bucket, account, serviceYear, start, end, scheduled, absence, granted, reviewed);
    }

    @Test void existingRulesAreFiscalAndOnlyTheTwoBasesAreAccepted() {
        assertEquals("FISCAL", db.queryForObject("SELECT basis FROM tna.annual_leave_rule WHERE tenant_id=? AND version=1", String.class, tenant));
        assertEquals(1, rule(2, "HIRE_DATE"));
        assertThrows(DataIntegrityViolationException.class, () -> rule(3, "MONTHLY"));
    }

    @Test void awardEvidenceIsAllOrNothingAndWithinStatutoryBounds() {
        assertThrows(DataIntegrityViolationException.class, () -> award(1, "2024-07-01", null, 1, "0", "8"));
        assertThrows(DataIntegrityViolationException.class, () -> award(1, "2024-07-01", "2024-12-31", 0, "0", "8"));
        assertThrows(DataIntegrityViolationException.class, () -> award(1, "2024-07-01", "2024-12-31", 10, "0.25", "8"));
        assertThrows(DataIntegrityViolationException.class, () -> award(1, "2024-07-01", "2024-12-31", 10, "0", "25.5"));
        assertThrows(DataIntegrityViolationException.class, () -> award(0, "2024-07-01", "2024-12-31", 10, "0", "8"));
        assertEquals(1, award(1, "2024-07-01", "2024-12-31", 10, "1.5", "8"));
        assertEquals(Boolean.TRUE, db.queryForObject("""
                SELECT has_column_privilege('tna_app','tna.annual_leave_award','granted_days','SELECT')
                   AND has_column_privilege('tna_app','tna.annual_leave_award','granted_days','INSERT')
                   AND NOT has_column_privilege('tna_app','tna.annual_leave_award','granted_days','UPDATE')
                   AND has_column_privilege('tna_app','tna.annual_leave_rule','basis','INSERT')""", Boolean.class));
    }

    @Test void awardsPostedBeforeV47KeepEmptyEvidenceAndAreNotAdminReviewed() {
        assertThrows(DataIntegrityViolationException.class, () -> award(null, null, null, null, null, null, true));
        assertEquals(1, award(null, null, null, null, null, null));
        assertEquals(Boolean.FALSE, db.queryForObject("SELECT reviewed_by_admin FROM tna.annual_leave_award WHERE tenant_id=?", Boolean.class, tenant));
    }

    @Test void aReviewedAwardKeepsTheComputedEvidenceEvenWithoutScheduledDays() {
        assertEquals(1, award(2, "2024-01-01", "2024-12-31", 0, "0", "15", true));
    }
}
