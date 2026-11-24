package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class HolidaySchemaIT {
    private final DatabaseFixture f = DatabaseFixture.start();
    private final JdbcTemplate admin = new JdbcTemplate(f.adminDataSource());
    private final JdbcTemplate app = new JdbcTemplate(f.appDataSource());
    private final TenantTransactions tx = new TenantTransactions(f.appDataSource());
    private UUID tenant, account;

    @BeforeEach void seed() {
        tenant = UUID.randomUUID(); account = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 휴일 고객사')", tenant, "HOLIDAY-" + tenant);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'H1','합성 담당자',DATE '2020-01-01')", tenant, employee);
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'holiday@example.test','synthetic-only')",
                tenant, account, employee);
    }

    @Test void holidayTablesAreTenantScopedWithNarrowGrants() {
        for (String table : List.of("holiday", "holiday_year")) {
            assertTrue(admin.queryForObject("""
                    SELECT c.relrowsecurity AND c.relforcerowsecurity FROM pg_class c
                    JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='tna' AND c.relname=?
                    """, Boolean.class, table), table);
        }
        assertTrue(admin.queryForObject("SELECT has_table_privilege('tna_app','tna.holiday','DELETE')", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_table_privilege('tna_app','tna.holiday_year','DELETE')", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.holiday_year','year','UPDATE')", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_table_privilege('tna_auth','tna.holiday','SELECT')", Boolean.class));
        assertFalse(admin.queryForObject("SELECT has_column_privilege('tna_app','tna.calendar_version','scope','UPDATE')", Boolean.class));
        assertTrue(admin.queryForObject("SELECT has_table_privilege('tna_app','tna.calendar_effective_day','SELECT')", Boolean.class));

        tx.execute(tenant, () -> holiday("2027-01-01", "PUBLIC"));
        assertEquals(1, tx.execute(tenant, () -> app.queryForObject("SELECT count(*) FROM tna.holiday", Integer.class)));
        assertEquals(0, tx.execute(UUID.randomUUID(), () -> app.queryForObject("SELECT count(*) FROM tna.holiday", Integer.class)));
        assertEquals("23505", sqlState(() -> tx.execute(tenant, () -> holiday("2027-01-01", "COMPANY"))));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> holiday("2027-01-02", "HOLIDAY"))));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> app.update(
                "INSERT INTO tna.holiday_year(tenant_id,year,version) VALUES (?,1999,1)", tenant))));
    }

    @Test void dayRevisionsStackOnPeriodsAndTheViewTakesTheHighestRevision() {
        UUID period = UUID.randomUUID(), day = UUID.randomUUID();
        tx.execute(tenant, () -> {
            version(period, 1, "2026-05-01", "2026-06-01", "PERIOD");
            day(period, "2026-05-05", "WORKDAY", "근로일");
            day(period, "2026-05-06", "WORKDAY", "근로일");
            version(day, 2, "2026-05-05", "2026-05-06", "DAY");
            day(day, "2026-05-05", "HOLIDAY", "어린이날");
            return null;
        });
        assertEquals(List.of(day + ":HOLIDAY:어린이날", period + ":WORKDAY:근로일"), tx.execute(tenant, () -> app.queryForList("""
                SELECT version_id::text||':'||kind||':'||name FROM tna.calendar_effective_day ORDER BY work_date
                """, String.class)));
        assertEquals(0, tx.execute(UUID.randomUUID(), () -> app.queryForObject("SELECT count(*) FROM tna.calendar_effective_day", Integer.class)));
        assertEquals("PERIOD", admin.queryForObject("SELECT scope FROM tna.calendar_version WHERE id=?", String.class, period));
        assertEquals("23P01", sqlState(() -> tx.execute(tenant, () -> version(UUID.randomUUID(), 3, "2026-05-20", "2026-06-10", "PERIOD"))));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> version(UUID.randomUUID(), 3, "2026-05-07", "2026-05-09", "DAY"))));
        assertEquals("23514", sqlState(() -> tx.execute(tenant, () -> version(UUID.randomUUID(), 3, "2026-05-07", "2026-05-08", "WEEK"))));
        tx.execute(tenant, () -> version(UUID.randomUUID(), 3, "2026-05-06", "2026-05-07", "DAY"));
    }

    private int holiday(String date, String category) {
        return app.update("""
                INSERT INTO tna.holiday(tenant_id,id,holiday_date,name,category,created_by,updated_by)
                VALUES (?,?,CAST(? AS date),'합성 휴일',?,?,?)
                """, tenant, UUID.randomUUID(), date, category, account, account);
    }

    private int version(UUID id, int revision, String from, String to, String scope) {
        return app.update("""
                INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,scope,reason,created_by)
                VALUES (?,?,?,CAST(? AS date),CAST(? AS date),?,'합성 반영',?)
                """, tenant, id, revision, from, to, scope, account);
    }

    private int day(UUID version, String date, String kind, String name) {
        return app.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,CAST(? AS date),?,?,'합성 근거')",
                tenant, version, date, kind, name);
    }

    private static String sqlState(Runnable action) {
        var error = assertThrows(DataAccessException.class, action::run);
        return ((SQLException) error.getMostSpecificCause()).getSQLState();
    }
}
