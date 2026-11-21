package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LeaveExitSchemaIT {
    @Test void settlementReceiptsAreAppendOnlyAndForceTenantIsolation() {
        var db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        assertEquals("tna.leave_exit_settlement", db.queryForObject(
                "SELECT to_regclass('tna.leave_exit_settlement')::text", String.class));
        assertTrue(db.queryForObject("""
                SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='tna.leave_exit_settlement'::regclass
                """, Boolean.class));
        assertTrue(db.queryForObject("""
                SELECT has_table_privilege('tna_app','tna.leave_exit_settlement','SELECT')
                    AND has_table_privilege('tna_app','tna.leave_exit_settlement','INSERT')
                    AND NOT has_table_privilege('tna_app','tna.leave_exit_settlement','UPDATE')
                    AND NOT has_table_privilege('tna_app','tna.leave_exit_settlement','DELETE')
                    AND NOT has_table_privilege('tna_auth','tna.leave_exit_settlement','SELECT')
                """, Boolean.class));
    }
}
