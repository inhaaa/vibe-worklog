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
            var after=Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").load().migrate();
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
        } finally {cluster.execute("DROP DATABASE "+database+" WITH (FORCE)");}
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
    private static List<Map<String,Object>> access(JdbcTemplate db) {return db.queryForList("""
        SELECT n.nspname,c.relname,c.relrowsecurity,c.relforcerowsecurity,c.relacl::text,
         (SELECT jsonb_agg(jsonb_build_array(p.policyname,p.permissive,p.roles,p.cmd,p.qual,p.with_check) ORDER BY p.policyname)::text FROM pg_policies p WHERE p.schemaname=n.nspname AND p.tablename=c.relname) AS policies,
         (SELECT jsonb_agg(jsonb_build_array(a.attname,a.attacl::text) ORDER BY a.attnum)::text FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped AND a.attname<>'attendance_type_version_id') AS columns
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname IN ('tna','platform') AND c.relkind='r' AND c.relname NOT IN ('common_code_group','common_code','attendance_type','attendance_type_version')
        ORDER BY n.nspname,c.relname
        """);}
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
