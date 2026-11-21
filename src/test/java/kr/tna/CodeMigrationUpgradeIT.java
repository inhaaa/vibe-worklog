package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class CodeMigrationUpgradeIT {
    @Test void v13RequestsDecisionsEffectsLedgerAndOriginalColumnsSurviveTheV14Backfill() throws Exception {
        var fixture=DatabaseFixture.start();var cluster=new JdbcTemplate(fixture.adminDataSource());
        String database="tna_codes_upgrade_"+UUID.randomUUID().toString().replace("-","");
        assertTrue(database.matches("tna_codes_upgrade_[0-9a-f]{32}"));
        String url=fixture.jdbcUrl().replace("/"+DatabaseFixture.DATABASE_NAME,"/"+database);
        assertNotEquals(url,fixture.jdbcUrl());
        var original=(DriverManagerDataSource)fixture.adminDataSource();
        var adminSource=new DriverManagerDataSource(url,original.getUsername(),original.getPassword());
        var migrationSource=new DriverManagerDataSource(url,DatabaseFixture.MIGRATION_USER,DatabaseFixture.MIGRATION_PASSWORD);
        cluster.execute("CREATE DATABASE "+database);
        try {
            var db=new JdbcTemplate(adminSource);
            cluster.execute("REVOKE ALL ON DATABASE "+database+" FROM PUBLIC");
            cluster.execute("GRANT CONNECT ON DATABASE "+database+" TO tna_migration,tna_app,tna_auth,tna_operator");
            cluster.execute("GRANT CREATE ON DATABASE "+database+" TO tna_migration");
            db.execute("GRANT USAGE,CREATE ON SCHEMA public TO tna_migration");
            var before=Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").target("13").load().migrate();
            assertTrue(before.success);assertEquals(13,before.migrationsExecuted);
            seed(db,new TransactionTemplate(new DataSourceTransactionManager(adminSource)));
            seed(db,new TransactionTemplate(new DataSourceTransactionManager(adminSource)));
            assertEquals(8,db.queryForObject("SELECT count(*) FROM tna.approval_request",Integer.class));
            assertEquals(2,db.queryForObject("SELECT count(*) FROM tna.approval_route_snapshot",Integer.class));
            Map<String,List<String>> columns=columns(db);var rows=rows(db,columns);var access=access(db);
            var after=Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").target("14").load().migrate();
            assertTrue(after.success);assertEquals(1,after.migrationsExecuted);
            assertEquals(rows,rows(db,columns));assertEquals(access,access(db));
            assertEquals(8,db.queryForObject("""
                SELECT count(*) FROM tna.approval_request r JOIN tna.attendance_type_version v ON v.tenant_id=r.tenant_id AND v.id=r.attendance_type_version_id
                JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
                WHERE v.revision=1 AND v.system_seed AND v.enabled AND v.effective_from=DATE '0001-01-01'
                 AND t.system_kind=CASE WHEN r.kind='ATTENDANCE_CORRECTION' THEN 'ATTENDANCE_CORRECTION' ELSE 'ANNUAL_LEAVE' END
                """,Integer.class));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.approval_request r JOIN tna.approval_request o ON o.tenant_id=r.tenant_id AND o.id=r.original_request_id WHERE r.kind='CANCEL_LEAVE' AND r.attendance_type_version_id<>o.attendance_type_version_id",Integer.class));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM pg_policies WHERE policyname='code_migration_registry_scan'",Integer.class));
            assertEquals("NO",db.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema='tna' AND table_name='approval_request' AND column_name='attendance_type_version_id'",String.class));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.approval_request WHERE attendance_type_version_id IS NULL",Integer.class));
            assertEquals(2,db.queryForObject("SELECT count(*) FROM tna.leave_ledger WHERE entry_kind='RESTORE'",Integer.class));
            var appSource=new DriverManagerDataSource(url,DatabaseFixture.APP_USER,DatabaseFixture.APP_PASSWORD);
            try(Connection connection=appSource.getConnection();var statement=connection.createStatement()) {
                try(var result=statement.executeQuery("SELECT count(*) FROM tna.attendance_type_version")){assertTrue(result.next());assertEquals(0,result.getInt(1));}
                String tenant=db.queryForObject("SELECT tenant_id::text FROM tna.approval_request ORDER BY tenant_id LIMIT 1",String.class);
                connection.setAutoCommit(false);statement.execute("SELECT set_config('app.tenant_id','"+tenant+"',true)");
                try(var result=statement.executeQuery("SELECT count(*) FROM tna.approval_request")){assertTrue(result.next());assertEquals(4,result.getInt(1));}
                connection.commit();
                try(var result=statement.executeQuery("SELECT count(*) FROM tna.approval_request")){assertTrue(result.next());assertEquals(0,result.getInt(1));}
                connection.rollback();
            }
            var v14Columns=columns(db);var v14Rows=rows(db,v14Columns);var v14Access=access(db);
            var v15=Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").target("15").load().migrate();
            assertTrue(v15.success);assertEquals(1,v15.migrationsExecuted);
            assertEquals(v14Rows,rows(db,v14Columns));
            assertEquals(v14Access,access(db).stream().filter(row->!"leave_bucket_attribution".equals(row.get("relname"))).toList());
            assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.leave_bucket_attribution",Integer.class));
            var v15Columns=columns(db);var v15Rows=rows(db,v15Columns);var v15Access=access(db);
            var v16=Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").target("16").load().migrate();
            assertTrue(v16.success);assertEquals(1,v16.migrationsExecuted);
            assertEquals(v15Rows,rows(db,v15Columns));
            assertEquals(v15Access,access(db).stream().filter(row->!List.of("employee_personnel","employee_employment_status").contains(row.get("relname"))).toList());
            assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.employee_personnel",Integer.class));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.employee_employment_status",Integer.class));
            var v16Columns=columns(db);var v16Rows=rows(db,v16Columns);var v16Access=access(db);
            var v17=Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").target("17").load().migrate();
            assertTrue(v17.success);assertEquals(1,v17.migrationsExecuted);
            assertEquals(v16Rows,rows(db,v16Columns));
            assertEquals(v16Access,access(db).stream().filter(row->!List.of("employee_personal_details","employee_personal_entry").contains(row.get("relname"))).toList());
            assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.employee_personal_details",Integer.class));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.employee_personal_entry",Integer.class));
            assertTrue(Flyway.configure().dataSource(migrationSource).target("18").load().migrate().success);
            var v18Columns=columns(db);var v18Rows=rows(db,v18Columns);
            var v22=Flyway.configure().dataSource(migrationSource).target("22").load().migrate();
            assertTrue(v22.success);assertEquals(4,v22.migrationsExecuted);
            assertEquals(v18Rows,rows(db,v18Columns));
            assertEquals(6,db.queryForObject("SELECT count(*) FROM tna.request_form",Integer.class));
            assertEquals(8,db.queryForObject("SELECT count(*) FROM tna.approval_request WHERE form_version_id IS NULL AND current_step=1 AND total_steps=1",Integer.class));
            assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.request_approval_step",Integer.class));
            assertTrue(Flyway.configure().dataSource(migrationSource).target("24").load().migrate().success);
            UUID customer=db.queryForList("SELECT id FROM platform.tenant_registry ORDER BY id",UUID.class).getFirst();
            UUID customForm=UUID.randomUUID();
            db.update("INSERT INTO tna.request_form(tenant_id,id,code,kind) VALUES(?,?,'O01','LEAVE_DAY')",customer,customForm);
            var upgraded=Flyway.configure().dataSource(migrationSource).target("25").load().migrate();
            assertTrue(upgraded.success);assertEquals(1,upgraded.migrationsExecuted);
            assertEquals(customForm,db.queryForObject("SELECT id FROM tna.request_form WHERE tenant_id=? AND code='O01'",UUID.class,customer));
            assertEquals(4,db.queryForObject("""
                    SELECT count(*) FROM tna.request_form f JOIN tna.request_form_version v
                    ON v.tenant_id=f.tenant_id AND v.form_id=f.id
                    WHERE f.kind IN ('OVERTIME','CANCEL_OVERTIME') AND v.enabled AND v.revision=1
                    """,Integer.class));
            assertEquals(v18Rows,rows(db,v18Columns));

            verifyCompactAttendanceCodeUpgrade(db, migrationSource, customer);
            var v26Columns = columns(db);
            var v26Rows = rows(db,v26Columns);
            assertTrue(Flyway.configure().dataSource(migrationSource).target("27").load().migrate().success);
            assertEquals(v26Rows,rows(db,v26Columns));
            assertEquals(2, db.queryForObject("SELECT count(*) FROM tna.leave_request_day WHERE leave_part='FULL' AND quantity_days=1", Integer.class));
            // V27 already allowed fractional USE/RESTORE rows. Carry them through the V28 constraint change.
            db.update("UPDATE tna.leave_ledger SET signed_quantity_days=signed_quantity_days/2 WHERE entry_kind IN ('USE','RESTORE')");
            db.update("""
                    INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,source_reference,reason,created_by)
                    SELECT tenant_id,gen_random_uuid(),bucket_id,employee_id,'ADJUSTMENT',0,effective_on,source_reference,reason,created_by
                    FROM tna.leave_ledger WHERE entry_kind='OPENING'
                    """);
            var v27Columns = columns(db);
            var v27Rows = rows(db,v27Columns);
            var v27Access = access(db);
            var v28 = Flyway.configure().dataSource(migrationSource).target("28").load().migrate();
            assertEquals(1, v28.migrationsExecuted);
            assertEquals(v27Rows,rows(db,v27Columns));
            assertEquals(v27Access,access(db));
            var v28Columns = columns(db);
            var v28Rows = rows(db, v28Columns);
            var v28Access = access(db);
            var toV29 = Flyway.configure().dataSource(migrationSource).target("29").load();
            assertEquals(1, toV29.migrate().migrationsExecuted);
            assertEquals(0, toV29.migrate().migrationsExecuted);
            assertEquals(v28Rows, rows(db, v28Columns));
            assertEquals(v28Access, access(db).stream().filter(row -> !"checkout_follow_up".equals(row.get("relname"))).toList());
            assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.checkout_follow_up", Integer.class));
            assertEquals(2, db.update("""
                    INSERT INTO tna.checkout_follow_up(tenant_id,id,employee_id,work_date,reason,requested_by)
                    SELECT tenant_id,gen_random_uuid(),target_employee_id,from_date,'V29 미해소 보완',author_account_id
                    FROM tna.approval_request WHERE kind='ATTENDANCE_CORRECTION' AND status='PENDING'
                    """));
            var v29Columns = columns(db);
            var v29Rows = rows(db, v29Columns);
            var v29Access = access(db);
            var toV30 = Flyway.configure().dataSource(migrationSource).target("30").load();
            assertEquals(1, toV30.migrate().migrationsExecuted);
            assertEquals(0, toV30.migrate().migrationsExecuted);
            assertEquals(v29Rows, rows(db, v29Columns));
            assertEquals(v29Access, access(db).stream().filter(row -> !"attendance_month_closure".equals(row.get("relname"))).toList());
            assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.attendance_month_closure", Integer.class));
            verifyMonthCloseExportUpgrade(db, migrationSource);
            verifySelectiveWorkUpgrade(db, migrationSource);
            verifySelectiveCloseUpgrade(db, migrationSource, url);
            var oldColumns = columns(db);
            var oldRows = rows(db, oldColumns);
            var oldPermissions = access(db);
            var companyLeave = Flyway.configure().dataSource(migrationSource).target("35").load();
            assertEquals(2, companyLeave.migrate().migrationsExecuted);
            assertEquals(0, companyLeave.migrate().migrationsExecuted);
            assertEquals(oldRows, rows(db, oldColumns));
            assertEquals(oldPermissions, access(db));
            var v35Columns = columns(db);
            var v35Rows = rows(db, v35Columns);
            var creditExport = Flyway.configure().dataSource(migrationSource).target("36").load();
            assertEquals(1, creditExport.migrate().migrationsExecuted);
            assertEquals(0, creditExport.migrate().migrationsExecuted);
            assertEquals(v35Rows, rows(db, v35Columns));
            assertEquals(oldPermissions, access(db));
            assertTrue(db.queryForObject("SELECT count(*)>0 AND count(paid_leave_snapshot)=0 FROM tna.attendance_month_closure", Boolean.class));
            assertTrue(db.queryForObject("""
                    SELECT has_column_privilege('tna_app','tna.attendance_month_closure','paid_leave_snapshot','SELECT')
                       AND has_column_privilege('tna_app','tna.attendance_month_closure','paid_leave_snapshot','INSERT')
                       AND NOT has_column_privilege('tna_app','tna.attendance_month_closure','paid_leave_snapshot','UPDATE')
                       AND NOT has_column_privilege('tna_auth','tna.attendance_month_closure','paid_leave_snapshot','SELECT')
                    """, Boolean.class));
        } finally {cluster.execute("DROP DATABASE "+database+" WITH (FORCE)");}
    }

    private static void verifySelectiveWorkUpgrade(JdbcTemplate db, DriverManagerDataSource migrations) {
        assertEquals(2,db.update("""
                INSERT INTO tna.work_time_code(tenant_id,id,code)
                SELECT id,gen_random_uuid(),'OLD32' FROM platform.tenant_registry
                """));
        assertEquals(4,db.update("""
                INSERT INTO tna.work_time_code_version(tenant_id,id,code_id,revision,effective_from,enabled,name,
                    start_time,end_time,after_start,after_end,reason,created_by)
                SELECT c.tenant_id,gen_random_uuid(),c.id,n,DATE '2026-01-01'+n,true,'이전 고정 코드',
                       TIME '09:00',TIME '18:00',TIME '18:30',TIME '20:00','기존 기준',
                       (SELECT id FROM tna.tenant_account WHERE tenant_id=c.tenant_id ORDER BY id LIMIT 1)
                FROM tna.work_time_code c CROSS JOIN generate_series(1,2) n WHERE c.code='OLD32'
                """));
        assertEquals(4,db.update("""
                INSERT INTO tna.work_time_code_break(tenant_id,version_id,kind,start_time,end_time)
                SELECT v.tenant_id,v.id,'REGULAR',TIME '12:00',TIME '13:00'
                FROM tna.work_time_code_version v JOIN tna.work_time_code c ON c.tenant_id=v.tenant_id AND c.id=v.code_id
                WHERE c.code='OLD32'
                """));
        var oldColumns=columns(db);
        var oldRows=rows(db,oldColumns);
        var oldAccess=access(db);
        var upgrade=Flyway.configure().dataSource(migrations).target("32").load();
        assertEquals(1,upgrade.migrate().migrationsExecuted);
        assertEquals(0,upgrade.migrate().migrationsExecuted);
        assertEquals(oldRows,rows(db,oldColumns));
        assertEquals(oldAccess,access(db,true));
        assertEquals(4,db.queryForObject("""
                SELECT count(*) FROM tna.work_time_code_version v
                JOIN tna.work_time_code c ON c.tenant_id=v.tenant_id AND c.id=v.code_id
                WHERE c.code='OLD32' AND v.work_mode='FIXED' AND v.core_start IS NULL AND v.core_end IS NULL
                    AND v.standard_minutes IS NULL AND v.settlement_period IS NULL
                """,Integer.class));
        assertTrue(db.queryForObject("""
                SELECT has_column_privilege('tna_app','tna.work_time_code_version','work_mode','INSERT')
                    AND NOT has_any_column_privilege('tna_app','tna.work_time_code_version','UPDATE')
                """,Boolean.class));
    }

    private static void verifySelectiveCloseUpgrade(JdbcTemplate db, DriverManagerDataSource migrations, String url) throws Exception {
        var owners=db.queryForList("SELECT DISTINCT ON (tenant_id) tenant_id,id FROM tna.tenant_account ORDER BY tenant_id,id");
        var tenant=owners.getFirst().get("tenant_id");
        var account=owners.getFirst().get("id");
        var closure=UUID.randomUUID();
        assertEquals(4,db.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on,created_at)
                SELECT ?,gen_random_uuid(),code,code,joined,left_on,DATE '2024-01-01' FROM (VALUES
                    ('V33-FULL',DATE '2024-11-01',NULL::date),('V33-PART',DATE '2024-11-05',DATE '2024-11-05'),
                    ('V33-UNKNOWN',DATE '2024-11-05',DATE '2024-11-05'),('V33-OUT',DATE '2024-12-01',NULL::date)
                ) people(code,joined,left_on)
                """,tenant));
        assertEquals(4,db.update("""
                INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,
                        engine_version,status,finished_at)
                SELECT tenant_id,gen_random_uuid(),id,?,DATE '2024-11-05',DATE '2024-11-06',DATE '2024-11-04',DATE '2024-11-10',
                       'CALC-1','COMPLETED',CURRENT_TIMESTAMP FROM tna.employee WHERE tenant_id=? AND employee_no LIKE 'V33-%'
                """,account,tenant));
        assertEquals(8,db.update("""
                INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
                        source_fingerprint,rule_version,status,actual_work_millis,leave_days,reason_codes,input_refs,calculated_at)
                SELECT r.tenant_id,gen_random_uuid(),r.id,r.employee_id,DATE '2024-11-05'+n,1,0,0,repeat('a',64),
                       'CALC-1','CALCULATED',25200000,0,'[]','[{"clock":{"schedule":{"workMode":"SELECTIVE"}}}]',DATE '2024-11-10'
                FROM tna.employee e JOIN tna.calculation_run r ON r.tenant_id=e.tenant_id AND r.employee_id=e.id
                CROSS JOIN generate_series(0,1) n WHERE e.tenant_id=? AND e.employee_no LIKE 'V33-%'
                """,tenant));
        db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name,changed_at)
                VALUES (?,?,DATE '2024-11-01',1,'CLOSED','이전 선택 마감',?,'합성 담당자',DATE '2024-12-01')
                """,tenant,closure,account);
        db.update("UPDATE tna.employee SET joined_on=DATE '2025-01-05',left_on=NULL,updated_at=DATE '2024-12-07' WHERE tenant_id=? AND employee_no IN ('V33-FULL','V33-UNKNOWN')",tenant);
        assertEquals(1,db.update("""
                INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
                        source_fingerprint,rule_version,status,actual_work_millis,leave_days,reason_codes,input_refs,calculated_at)
                SELECT r.tenant_id,gen_random_uuid(),r.run_id,r.employee_id,r.work_date,2,0,0,r.source_fingerprint,
                       r.rule_version,r.status,3600000,0,'[]',r.input_refs,DATE '2024-12-08'
                FROM tna.day_result r JOIN tna.employee e ON e.tenant_id=r.tenant_id AND e.id=r.employee_id
                WHERE r.tenant_id=? AND e.employee_no='V33-FULL' AND r.work_date=DATE '2024-11-05'
                """,tenant));
        var original=columns(db);
        var data=rows(db,original);
        var permissions=access(db);
        var upgrade=Flyway.configure().dataSource(migrations).target("33").load();
        assertEquals(1,upgrade.migrate().migrationsExecuted);
        assertEquals(0,upgrade.migrate().migrationsExecuted);
        assertEquals(data,rows(db,original));
        assertEquals(permissions,access(db).stream().filter(row->!"attendance_month_selective_member".equals(row.get("relname"))).toList());
        assertEquals(List.of("V33-FULL/05.1,06.1","V33-PART/05.1","V33-UNKNOWN/UNKNOWN"),db.queryForList("""
                SELECT e.employee_no||'/'||coalesce((SELECT string_agg(to_char(r.work_date,'DD')||'.'||r.result_revision,',' ORDER BY r.work_date)
                        FROM tna.day_result r WHERE r.tenant_id=m.tenant_id AND r.id=ANY(m.result_ids)),'UNKNOWN')
                FROM tna.attendance_month_selective_member m JOIN tna.employee e ON e.tenant_id=m.tenant_id AND e.id=m.employee_id
                WHERE m.tenant_id=? AND m.closure_id=? ORDER BY e.employee_no
                """,String.class,tenant,closure));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM pg_policies WHERE policyname='selective_close_registry_scan'",Integer.class));
        assertTrue(db.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='tna.attendance_month_selective_member'::regclass",Boolean.class));
        assertFalse(db.queryForObject("""
                SELECT has_any_column_privilege('tna_app','tna.attendance_month_selective_member','UPDATE')
                    OR has_table_privilege('tna_app','tna.attendance_month_selective_member','DELETE')
                    OR has_table_privilege('tna_auth','tna.attendance_month_selective_member','SELECT')
                    OR has_table_privilege('tna_operator','tna.attendance_month_selective_member','SELECT')
                """,Boolean.class));
        var app=new DriverManagerDataSource(url,DatabaseFixture.APP_USER,DatabaseFixture.APP_PASSWORD);
        try(var connection=app.getConnection();var query=connection.createStatement()) {
            connection.setAutoCommit(false);
            for (var owner : owners) {
                query.execute("SELECT set_config('app.tenant_id','"+owner.get("tenant_id")+"',true)");
                try(var result=query.executeQuery("SELECT count(*) FROM tna.attendance_month_selective_member")) {
                    assertTrue(result.next());
                    assertEquals(owner.get("tenant_id").equals(tenant)?3:0,result.getInt(1));
                }
            }
            var denied=assertThrows(java.sql.SQLException.class,()->query.executeUpdate("INSERT INTO tna.attendance_month_selective_member(tenant_id,closure_id,employee_id) VALUES ('"+tenant+"','"+closure+"',gen_random_uuid())"));
            assertEquals("42501",denied.getSQLState());
            connection.rollback();
        }
    }

    private static void verifyMonthCloseExportUpgrade(JdbcTemplate db, DriverManagerDataSource migrations) {
        seedMonthCloseEvidence(db);
        assertEquals(12, db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                SELECT a.tenant_id,gen_random_uuid(),d.month_start,d.version,
                     CASE WHEN d.version%2=1 THEN 'CLOSED' ELSE 'OPEN' END,'이관 전 마감 이력',a.id,'기존 마감자'
                FROM (SELECT DISTINCT ON (tenant_id) tenant_id,id FROM tna.tenant_account ORDER BY tenant_id,id) a
                CROSS JOIN (VALUES (DATE '2026-08-01',1),(DATE '2026-08-01',2),(DATE '2026-09-01',1),
                         (DATE '2026-09-01',2),(DATE '2026-09-01',3),(DATE '2026-10-01',1)) d(month_start,version)
                """));
        UUID backdatedClosure = seedBackdatedEmploymentAfterClose(db);
        var originalColumns = columns(db);
        var originalRows = rows(db, originalColumns);
        var toV31 = Flyway.configure().dataSource(migrations).target("31").load();
        assertEquals(1, toV31.migrate().migrationsExecuted);
        assertEquals(0, toV31.migrate().migrationsExecuted);
        assertEquals(originalRows, rows(db, originalColumns));
        assertEquals(List.of("BACKDATED-HIRE", "REVISED-START"), db.queryForList("""
                SELECT employee_no FROM tna.attendance_month_export_row
                WHERE closure_id=? AND employed_days=1 AND worked_days IS NULL AND actual_millis IS NULL ORDER BY employee_no
                """, String.class, backdatedClosure));
        assertEquals(List.of("UNCHANGED"), db.queryForList("""
                SELECT employee_no FROM tna.attendance_month_export_row
                WHERE closure_id=? AND employed_days=1 AND worked_days=0 AND actual_millis=0
                """, String.class, backdatedClosure));
        assertEquals(13, db.queryForObject("SELECT count(*) FROM tna.attendance_month_closure WHERE NOT export_at_close", Integer.class));
        assertEquals(List.of("2025-03-01/1/3", "2026-09-01/3/4", "2026-10-01/1/4"), db.queryForList("""
                SELECT c.month_start::text||'/'||c.version||'/'||count(*)
                FROM tna.attendance_month_export_row r JOIN tna.attendance_month_closure c
                    ON c.tenant_id=r.tenant_id AND c.id=r.closure_id
                GROUP BY c.month_start,c.version ORDER BY c.month_start
                """, String.class));
        assertEquals(2, db.queryForObject("SELECT count(DISTINCT tenant_id) FROM tna.attendance_month_export_row", Integer.class));
        assertEquals(1, db.queryForObject("""
                SELECT count(*) FROM tna.attendance_month_export_row WHERE leave_days=0.5
                    AND actual_millis=14400000 AND worked_days=1 AND employed_days=31
                """, Integer.class));
        assertEquals(1, db.queryForObject("""
                SELECT count(*) FROM tna.attendance_month_export_row WHERE closure_id<>? AND actual_millis IS NULL AND worked_days IS NULL
                """, Integer.class, backdatedClosure));
        assertEquals(0.5, db.queryForObject("SELECT sum(leave_days) FROM tna.attendance_month_export_row", Double.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM pg_policies WHERE policyname='month_close_export_registry_scan'", Integer.class));
        assertTrue(db.queryForObject("""
                SELECT relrowsecurity AND relforcerowsecurity FROM pg_class
                WHERE oid='tna.attendance_month_export_row'::regclass
                """, Boolean.class));
        for (String role : List.of("tna_auth", "tna_operator")) {
            assertFalse(db.queryForObject("SELECT has_table_privilege(?, 'tna.attendance_month_export_row', 'SELECT')", Boolean.class, role));
        }
        for (String privilege : List.of("UPDATE", "DELETE")) {
            assertFalse(db.queryForObject("SELECT has_table_privilege('tna_app','tna.attendance_month_export_row',?)", Boolean.class, privilege));
        }
    }

    private static UUID seedBackdatedEmploymentAfterClose(JdbcTemplate db) {
        var owner = db.queryForMap("SELECT tenant_id,id FROM tna.tenant_account ORDER BY tenant_id,id LIMIT 1");
        var tenant = owner.get("tenant_id");
        db.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on,created_at)
                VALUES (?,gen_random_uuid(),'UNCHANGED','기존 재직자',DATE '2025-03-31',DATE '2025-03-31',TIMESTAMPTZ '2025-03-01 09:00:00+09'),
                       (?,gen_random_uuid(),'REVISED-START','입사일 소급 수정',DATE '2025-04-01',DATE '2025-04-01',TIMESTAMPTZ '2025-03-01 09:00:00+09')
                """, tenant, tenant);
        var closure = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name,changed_at)
                VALUES (?,?,DATE '2025-03-01',1,'CLOSED','기존 월 마감',?,'이전 마감자',TIMESTAMPTZ '2025-04-02 09:00:00+09')
                """, tenant, closure, owner.get("id"));
        db.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on,created_at)
                VALUES (?,gen_random_uuid(),'BACKDATED-HIRE','마감 뒤 소급 등록',DATE '2025-03-31',DATE '2025-03-31',TIMESTAMPTZ '2025-04-03 09:00:00+09')
                """, tenant);
        db.update("""
                UPDATE tna.employee SET joined_on=DATE '2025-03-31',updated_at=TIMESTAMPTZ '2025-04-03 09:00:00+09'
                WHERE tenant_id=? AND employee_no='REVISED-START'
                """, tenant);
        return closure;
    }

    /** One effective half-day, a revised calculation, and a separate employee whose result was unconfirmed. */
    private static void seedMonthCloseEvidence(JdbcTemplate db) {
        var old = db.queryForMap("""
                SELECT tenant_id,id,target_employee_id AS employee,author_account_id AS actor,current_approver_id AS approver
                FROM tna.approval_request WHERE kind='LEAVE_DAY' AND status='APPROVED' ORDER BY tenant_id LIMIT 1
                """);
        UUID half = UUID.randomUUID();
        assertEquals(1, db.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,
                        approver_assignment_id,approver_assignment_version,reason,leave_bucket_id,from_date,to_date,
                        client_command_id,attendance_type_version_id)
                SELECT tenant_id,?,target_employee_id,author_account_id,kind,status,current_approver_id,approver_assignment_id,
                       approver_assignment_version,'이관된 반차',leave_bucket_id,from_date-1,to_date-1,gen_random_uuid(),attendance_type_version_id
                FROM tna.approval_request WHERE tenant_id=? AND id=?
                """, half, old.get("tenant_id"), old.get("id")));
        db.update("""
                INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,schedule_version_id,calendar_version_id,leave_part)
                SELECT tenant_id,gen_random_uuid(),?,leave_date-1,0.5,schedule_version_id,calendar_version_id,'AM'
                FROM tna.leave_request_day WHERE tenant_id=? AND request_id=?
                """, half, old.get("tenant_id"), old.get("id"));
        db.update("""
                INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,created_by)
                VALUES (?,gen_random_uuid(),?,?,'LEAVE',?)
                """, old.get("tenant_id"), half, old.get("employee"), old.get("actor"));
        for (int i = 0; i < 3; i++) {
            boolean unknown = i == 2;
            Object employee = old.get(unknown ? "approver" : "employee");
            var run = UUID.randomUUID();
            db.update("""
                    INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,
                            engine_version,status,finished_at)
                    VALUES (?,?,?,?,DATE '2026-10-28',DATE '2026-10-28',DATE '2026-10-26',DATE '2026-11-01',
                            'CALC-1','COMPLETED',CURRENT_TIMESTAMP)
                    """, old.get("tenant_id"), run, employee, old.get("actor"));
            db.update("""
                    INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,
                            review_revision,source_fingerprint,rule_version,status,actual_work_millis,leave_days,reason_codes,input_refs)
                    VALUES (?,gen_random_uuid(),?,?,DATE '2026-10-28',?,0,0,repeat('a',64),'CALC-1',?,?,0,'[]','[]')
                    """, old.get("tenant_id"), run, employee, unknown ? 1 : i + 1, unknown ? "REVIEW_REQUIRED" : "CALCULATED",
                    unknown ? null : Long.valueOf(i == 0 ? 28800000L : 14400000L));
        }
    }

    private static void verifyCompactAttendanceCodeUpgrade(JdbcTemplate db, DriverManagerDataSource migrations, UUID customer) {
        var account = db.queryForList("SELECT id FROM tna.tenant_account WHERE tenant_id=? ORDER BY id", UUID.class, customer).getFirst();
        var customCodes = List.of("A01", "a03", "LONG_CUSTOM_CODE");
        for (String code : customCodes) {
            db.update("INSERT INTO tna.attendance_type(tenant_id,id,code,created_by,updated_by) VALUES(?,?,?,?,?)",
                    customer, UUID.randomUUID(), code, account, account);
        }
        var originalColumns = columns(db);
        originalColumns.get("tna.attendance_type").remove("code");
        var before = rows(db, originalColumns);
        var migrated = Flyway.configure().dataSource(migrations).target("26").load().migrate();
        assertTrue(migrated.success);
        assertEquals(1, migrated.migrationsExecuted);
        assertEquals(before, rows(db, originalColumns));
        assertEquals(customCodes.stream().sorted().toList(), db.queryForList("""
                SELECT code FROM tna.attendance_type WHERE tenant_id=? AND system_kind IS NULL ORDER BY code COLLATE "C"
                """, String.class, customer));
        assertEquals("A04", db.queryForObject("SELECT code FROM tna.attendance_type WHERE tenant_id=? AND system_kind='ANNUAL_LEAVE'",
                String.class, customer));
        assertEquals("A02", db.queryForObject("SELECT code FROM tna.attendance_type WHERE tenant_id=? AND system_kind='ATTENDANCE_CORRECTION'",
                String.class, customer));
        assertEquals("A01", db.queryForObject("SELECT code FROM tna.attendance_type WHERE tenant_id<>? AND system_kind='ANNUAL_LEAVE'",
                String.class, customer));
    }
    private static Map<String,List<String>> columns(JdbcTemplate db) {
        var result=new LinkedHashMap<String,List<String>>();
        for(String table:db.queryForList("SELECT table_schema||'.'||table_name FROM information_schema.tables WHERE table_schema IN ('tna','platform') AND table_type='BASE TABLE' ORDER BY table_schema,table_name",String.class)) {
            String[] parts=table.split("\\.");
            result.put(table,db.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position",String.class,parts[0],parts[1]));
        }return result;
    }
    private static Map<String,String> rows(JdbcTemplate db,Map<String,List<String>> columns) {
        var result=new LinkedHashMap<String,String>();
        columns.forEach((table,fields)->{
            assertTrue(table.matches("(tna|platform)\\.[a-z_]+"));for(String field:fields)assertTrue(field.matches("[a-z_][a-z0-9_]*"));
            String projection=String.join(",",fields.stream().map(name->"\""+name+"\"").toList());
            result.put(table,db.queryForObject("SELECT md5(coalesce(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text) FROM (SELECT "+projection+" FROM "+table+")r",String.class));
        });return result;
    }
    private static List<Map<String,Object>> access(JdbcTemplate db) {return access(db,false);}
    private static List<Map<String,Object>> access(JdbcTemplate db,boolean beforeSelectiveColumns) {return db.queryForList("""
        SELECT n.nspname,c.relname,c.relrowsecurity,c.relforcerowsecurity,c.relacl::text,
         (SELECT jsonb_agg(jsonb_build_array(p.policyname,p.permissive,p.roles,p.cmd,p.qual,p.with_check) ORDER BY p.policyname)::text FROM pg_policies p WHERE p.schemaname=n.nspname AND p.tablename=c.relname) AS policies,
         (SELECT jsonb_agg(jsonb_build_array(a.attname,a.attacl::text) ORDER BY a.attnum)::text FROM pg_attribute a
          WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped AND a.attname<>'attendance_type_version_id'
            AND NOT (c.relname='attendance_month_closure' AND a.attname='paid_leave_snapshot')
            AND NOT (? AND c.relname='work_time_code_version'
                     AND a.attname IN ('work_mode','standard_minutes','core_start','core_end','settlement_period'))) AS columns
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname IN ('tna','platform') AND c.relkind='r' AND c.relname NOT IN ('common_code_group','common_code','attendance_type','attendance_type_version')
        ORDER BY n.nspname,c.relname
        """,beforeSelectiveColumns);}
    private static void seed(JdbcTemplate db,TransactionTemplate tx) {
        UUID tenant=UUID.randomUUID(),target=UUID.randomUUID(),approver=UUID.randomUUID(),account=UUID.randomUUID(),approverAccount=UUID.randomUUID(),assignment=UUID.randomUUID(),bucket=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'V13 합성 회사')",tenant,"UPGRADE-"+tenant);
        for(var employee:List.of(target,approver))db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'V13 합성 사원',DATE '2026-01-01')",tenant,employee,employee.toString());
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-upgrade-password-hash')",tenant,account,target,account+"@example.test");
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-upgrade-password-hash')",tenant,approverAccount,approver,approverAccount+"@example.test");
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'APPROVER')",tenant,approverAccount);
        db.update("INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,DATE '2026-01-01','V13 지정',?,?)",tenant,assignment,target,approver,account,account);
        db.update("INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,usable_to,source_reference,created_by) VALUES (?,?,?,DATE '2026-01-01',DATE '2026-01-01',DATE '2026-12-31','V13 기초',?)",tenant,bucket,target,account);
        UUID leave=UUID.randomUUID(),correction=UUID.randomUUID(),cancel=UUID.randomUUID();
        for(String kind:List.of("LEAVE_DAY","ATTENDANCE_CORRECTION","CANCEL_LEAVE")) {
            UUID request=kind.equals("LEAVE_DAY")?leave:kind.equals("ATTENDANCE_CORRECTION")?correction:cancel;
            db.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,approver_assignment_id,approver_assignment_version,reason,original_request_id,leave_bucket_id,from_date,to_date,client_command_id,version)
                VALUES (?,?,?,?,?,?,?, ?,0,'V13 신청',?,?,DATE '2026-10-29',DATE '2026-10-29',?,?)
                """,tenant,request,target,account,kind,kind.equals("ATTENDANCE_CORRECTION")?"PENDING":"APPROVED",approver,assignment,kind.equals("CANCEL_LEAVE")?leave:null,kind.equals("ATTENDANCE_CORRECTION")?null:bucket,UUID.randomUUID(),kind.equals("ATTENDANCE_CORRECTION")?0:1);
            db.update("INSERT INTO tna.approval_event(tenant_id,id,request_id,event_kind,actor_account_id,to_status,reason) VALUES (?,?,?,'SUBMITTED',?,'PENDING','V13 신청 이력')",tenant,UUID.randomUUID(),request,account);
            if(!kind.equals("ATTENDANCE_CORRECTION"))db.update("INSERT INTO tna.approval_event(tenant_id,id,request_id,event_kind,actor_account_id,from_status,to_status,reason) VALUES (?,?,?,'APPROVED',?,'PENDING','APPROVED','V13 승인 결정')",tenant,UUID.randomUUID(),request,approverAccount);
        }
        UUID template=UUID.randomUUID(),schedule=UUID.randomUUID(),calendar=UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'V13 고정 일정',?,?)",tenant,template,"SCHEDULE",account,account);
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,DATE '2026-01-01','V13 일정',?)",tenant,schedule,template,account);
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,DATE '2026-01-01',DATE '2027-01-01','V13 달력',?)",tenant,calendar,account);
        db.update("INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,schedule_version_id,calendar_version_id) VALUES (?,?,?,DATE '2026-10-29',1,?,?)",tenant,UUID.randomUUID(),leave,schedule,calendar);
        db.update("INSERT INTO tna.correction_detail(tenant_id,id,request_id,work_date,base_input_revision,source_fingerprint,in_action,out_action,actual_breaks,presence_as_work,breaks_confirmed,before_snapshot) VALUES (?,?,?,DATE '2026-10-29',0,?,'KEEP','KEEP','[]'::jsonb,false,false,'{}'::jsonb)",tenant,UUID.randomUUID(),correction,"a".repeat(64));
        UUID effect=UUID.randomUUID(),restoration=UUID.randomUUID(),use=UUID.randomUUID();
        db.update("INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,effect_status,created_by) VALUES (?,?,?,?,'LEAVE','CANCELED',?)",tenant,effect,leave,target,account);
        db.update("INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,reverses_effect_id,created_by) VALUES (?,?,?,?,'CANCEL',?,?)",tenant,restoration,cancel,target,effect,account);
        db.update("INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,source_reference,reason,created_by) VALUES (?,?,?,?,'OPENING',10,DATE '2026-01-01','V13 기초','V13 원장',?)",tenant,UUID.randomUUID(),bucket,target,account);
        db.update("INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,request_effect_id,leave_date,reason,created_by) VALUES (?,?,?,?,'USE',-1,DATE '2026-10-28',?,DATE '2026-10-29','V13 차감',?)",tenant,use,bucket,target,effect,account);
        db.update("INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,request_effect_id,leave_date,reverses_entry_id,reason,created_by) VALUES (?,?,?,?,'RESTORE',1,DATE '2026-10-28',?,DATE '2026-10-29',?,'V13 복원',?)",tenant,UUID.randomUUID(),bucket,target,restoration,use,account);
        UUID route=UUID.randomUUID(),routeVersion=UUID.randomUUID(),routeAssignment=UUID.randomUUID(),request=UUID.randomUUID(),snapshot=UUID.randomUUID();
        db.update("INSERT INTO tna.approval_route(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'V13 결재선',?,?)",tenant,route,"ROUTE",account,account);
        db.update("INSERT INTO tna.approval_route_version(tenant_id,id,route_id,revision,name,approver_kind,employee_id,reason,created_by) VALUES (?,?,?,1,'V13 지정','DIRECT_EMPLOYEE',?,'V13 결재선',?)",tenant,routeVersion,route,approver,account);
        db.update("INSERT INTO tna.approval_route_assignment(tenant_id,id,route_version_id,scope_kind,employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,'EMPLOYEE',?,DATE '2026-01-01','V13 배정',?,?)",tenant,routeAssignment,routeVersion,target,account,account);
        tx.executeWithoutResult(status->{
            db.update("INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,current_approver_id,route_snapshot_id,reason,leave_bucket_id,from_date,to_date,client_command_id) VALUES (?,?,?,?,'LEAVE_DAY',?,?,'V13 경로 신청',?,DATE '2026-10-30',DATE '2026-10-30',?)",tenant,request,target,account,approver,snapshot,bucket,UUID.randomUUID());
            db.update("INSERT INTO tna.approval_route_snapshot(tenant_id,id,request_id,route_version_id,assignment_id,assignment_version,resolved_approver_employee_id) VALUES (?,?,?,?,?,0,?)",tenant,snapshot,request,routeVersion,routeAssignment,approver);
        });
    }
}
