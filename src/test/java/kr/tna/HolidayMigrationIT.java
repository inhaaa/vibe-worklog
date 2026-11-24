package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class HolidayMigrationIT {
    @Test void v41MovesCurrentHolidaysAndWeekdayNonWorkdaysIntoTheHolidayTable() {
        var fixture = DatabaseFixture.start();
        var cluster = new JdbcTemplate(fixture.adminDataSource());
        String database = "tna_holiday_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(database.matches("tna_holiday_upgrade_[0-9a-f]{32}"));
        String url = fixture.jdbcUrl().replace("/" + DatabaseFixture.DATABASE_NAME, "/" + database);
        assertNotEquals(url, fixture.jdbcUrl());
        var original = (DriverManagerDataSource) fixture.adminDataSource();
        var adminSource = new DriverManagerDataSource(url, original.getUsername(), original.getPassword());
        var migrationSource = new DriverManagerDataSource(url, DatabaseFixture.MIGRATION_USER, DatabaseFixture.MIGRATION_PASSWORD);
        cluster.execute("CREATE DATABASE " + database);
        try {
            var db = new JdbcTemplate(adminSource);
            cluster.execute("REVOKE ALL ON DATABASE " + database + " FROM PUBLIC");
            cluster.execute("GRANT CONNECT ON DATABASE " + database + " TO tna_migration,tna_app,tna_auth,tna_operator");
            cluster.execute("GRANT CREATE ON DATABASE " + database + " TO tna_migration");
            db.execute("GRANT USAGE,CREATE ON SCHEMA public TO tna_migration");
            assertTrue(Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").target("40").load().migrate().success);

            UUID tenant = UUID.randomUUID(), other = UUID.randomUUID(), employee = UUID.randomUUID(), account = UUID.randomUUID(), calendar = UUID.randomUUID();
            db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'V40 합성 회사')", tenant, "V40-" + tenant);
            db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'V40 달력 없는 회사')", other, "V40-" + other);
            db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'V1','V40 담당자',DATE '2020-01-01')", tenant, employee);
            db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'v40@example.test','synthetic-only')", tenant, account, employee);
            db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,DATE '2026-12-28',DATE '2027-01-04','V40 달력',?)",
                    tenant, calendar, account);
            for (String[] day : List.of(
                    new String[] {"2026-12-28", "WORKDAY", "검토한 기본 근로일", "기간 근거"},
                    new String[] {"2026-12-29", "NON_WORKDAY", "회사 휴무", "노사 합의"},
                    new String[] {"2026-12-30", "WORKDAY", "검토한 기본 근로일", "기간 근거"},
                    new String[] {"2026-12-31", "HOLIDAY", "창립기념일", "취업규칙 제10조"},
                    new String[] {"2027-01-01", "HOLIDAY", "1월 1일", "월력요항"},
                    new String[] {"2027-01-02", "NON_WORKDAY", "주말", "기간 근거"},
                    new String[] {"2027-01-03", "NON_WORKDAY", "주말", "기간 근거"})) {
                db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,CAST(? AS date),?,?,?)",
                        tenant, calendar, day[0], day[1], day[2], day[3]);
            }

            var upgrade = Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").target("41").load();
            assertEquals(1, upgrade.migrate().migrationsExecuted);
            assertEquals(0, upgrade.migrate().migrationsExecuted);

            assertEquals(List.of(
                    "2026-12-29|NON_WORKDAY|회사 휴무|노사 합의",
                    "2026-12-31|COMPANY|창립기념일|취업규칙 제10조",
                    "2027-01-01|COMPANY|1월 1일|월력요항"), db.queryForList("""
                    SELECT holiday_date||'|'||category||'|'||name||'|'||basis FROM tna.holiday
                    WHERE tenant_id=? AND created_by=? AND updated_by=? ORDER BY holiday_date
                    """, String.class, tenant, account, account));
            assertEquals(List.of("2026|1", "2027|1"), db.queryForList("""
                    SELECT year||'|'||version FROM tna.holiday_year
                    WHERE tenant_id=? AND applied_at IS NOT NULL AND applied_by IS NULL ORDER BY year
                    """, String.class, tenant));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.holiday_year WHERE tenant_id=?", Integer.class, other));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM pg_policies WHERE policyname='holiday_migration_registry_scan'", Integer.class));
            assertEquals("PERIOD", db.queryForObject("SELECT scope FROM tna.calendar_version WHERE id=?", String.class, calendar));
        } finally {
            cluster.execute("DROP DATABASE " + database + " WITH (FORCE)");
        }
    }
}
