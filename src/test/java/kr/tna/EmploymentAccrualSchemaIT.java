package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class EmploymentAccrualSchemaIT {
    @Test void onlyTenantScopedAppendOnlyReviewsCanBackAMonthlyLedgerEntry() {
        var db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        assertEquals("tna.leave_monthly_review", db.queryForObject("SELECT to_regclass('tna.leave_monthly_review')::text", String.class));
        assertEquals(Boolean.TRUE, db.queryForObject("""
                SELECT relrowsecurity AND relforcerowsecurity AND has_table_privilege('tna_app',oid,'INSERT')
                    AND NOT has_table_privilege('tna_app',oid,'UPDATE,DELETE')
                FROM pg_class WHERE oid='tna.leave_monthly_review'::regclass
                """, Boolean.class));
        assertEquals(1, db.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE confrelid='tna.leave_monthly_review'::regclass
                    AND conrelid='tna.leave_ledger'::regclass AND contype='f'
                """, Integer.class));
    }
}
