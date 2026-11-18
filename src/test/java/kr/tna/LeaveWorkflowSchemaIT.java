package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class LeaveWorkflowSchemaIT {
    private static final List<String> TABLES=List.of("leave_bucket","leave_ledger","approval_request",
            "leave_request_day","correction_detail","request_effect","approval_event");
    private static JdbcTemplate admin,app,auth;
    private static TenantTransactions tx;
    @BeforeAll static void database() {
        var f=DatabaseFixture.start();admin=new JdbcTemplate(f.adminDataSource());app=new JdbcTemplate(f.appDataSource());
        auth=new JdbcTemplate(f.authDataSource());tx=new TenantTransactions(f.appDataSource());
    }
    @Test void leaveWorkflowSchemaExists() {
        assertNotNull(admin.queryForObject("SELECT to_regclass('tna.approval_request')::text",String.class));
        for(String table:TABLES) assertTrue(admin.queryForObject("""
                SELECT c.relrowsecurity AND c.relforcerowsecurity FROM pg_class c
                JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='tna' AND c.relname=?
                """,Boolean.class,table));
    }
    @Test void newDataIsIsolatedAndContextNeverLeaksAcrossTransactions() {
        var a=scope();var b=scope();
        tx.execute(a.tenant(),()->{seed(a,"AM");return null;});
        tx.execute(b.tenant(),()->{seed(b,"PM");return null;});
        for(var s:List.of(a,b)) {
            assertEquals(List.of(s.equals(a)?"AM":"PM"),tx.execute(s.tenant(),()->app.queryForList(
                    "SELECT leave_part FROM tna.leave_request_day WHERE quantity_days=0.5",String.class)));
        }
        for(String table:TABLES) {
            assertEquals(0,app.queryForObject("SELECT count(*) FROM tna."+table,Integer.class));
            for(var s:List.of(a,b)) assertEquals(List.of(s.tenant()),tx.execute(s.tenant(),()->
                    app.queryForList("SELECT DISTINCT tenant_id FROM tna."+table,UUID.class)));
            assertEquals(0,app.queryForObject("SELECT count(*) FROM tna."+table,Integer.class));
        }
        denied("42501",a,()->bucket(b));
    }
    @Test void runtimeCannotRewriteImmutableFactsOrDeleteWorkflowHistory() {
        var s=scope();
        for(String table:TABLES) {
            denied("42501",s,()->app.update("DELETE FROM tna."+table));
            denied("42501",s,()->app.execute("TRUNCATE tna."+table));
            assertThrows(DataAccessException.class,()->auth.queryForList("SELECT * FROM tna."+table));
        }
        for(String table:List.of("leave_ledger","leave_request_day","correction_detail","approval_event")) {
            String column=switch(table){case "leave_ledger"->"reason";case "leave_request_day"->"zero_reason";
                case "correction_detail"->"source_fingerprint";default->"reason";};
            denied("42501",s,()->app.update("UPDATE tna."+table+" SET "+column+"='changed'"));
        }
        denied("42501",s,()->app.update("UPDATE tna.leave_bucket SET source_reference='changed'"));
        denied("42501",s,()->app.update("UPDATE tna.approval_request SET reason='changed'"));
        denied("42501",s,()->app.update("UPDATE tna.request_effect SET effect_kind='CANCEL'"));
    }
    @Test void compositeReferencesKeepBucketsAuthorsApproversAndEffectsInTheirTenant() {
        var own=scope();var other=scope();
        for(var s:List.of(own,other)) tx.execute(s.tenant(),()->{bucket(s);return null;});
        denied("23503",own,()->request(own,UUID.randomUUID(),"LEAVE_DAY",own.target(),other.actor(),own.approver(),own.bucket(),null,"2026-11-01"));
        denied("23503",own,()->request(own,UUID.randomUUID(),"LEAVE_DAY",own.target(),own.actor(),other.approver(),own.bucket(),null,"2026-11-01"));
        denied("23503",own,()->request(own,UUID.randomUUID(),"LEAVE_DAY",other.target(),own.actor(),own.approver(),own.bucket(),null,"2026-11-01"));
        denied("23503",own,()->request(own,UUID.randomUUID(),"LEAVE_DAY",own.target(),own.actor(),own.approver(),other.bucket(),null,"2026-11-01"));
        denied("23514",own,()->request(own,UUID.randomUUID(),"LEAVE_DAY",own.target(),own.actor(),own.target(),own.bucket(),null,"2026-11-01"));
    }
    @Test void pendingCorrectionAndCancellationRequestsHaveUniqueActiveKeys() {
        var s=scope();UUID first=UUID.randomUUID(),original=UUID.randomUUID();
        tx.execute(s.tenant(),()->{
            bucket(s);request(s,original,"LEAVE_DAY",s.target(),s.actor(),s.approver(),s.bucket(),null,"2026-11-01");
            request(s,first,"ATTENDANCE_CORRECTION",s.target(),s.actor(),s.approver(),null,null,"2026-11-02");return null;
        });
        denied("23505",s,()->request(s,UUID.randomUUID(),"ATTENDANCE_CORRECTION",s.target(),s.actor(),s.approver(),null,null,"2026-11-02"));
        tx.execute(s.tenant(),()->{
            app.update("UPDATE tna.approval_request SET status='REJECTED',version=version+1 WHERE tenant_id=? AND id=?",s.tenant(),first);
            request(s,UUID.randomUUID(),"ATTENDANCE_CORRECTION",s.target(),s.actor(),s.approver(),null,null,"2026-11-02");
            request(s,UUID.randomUUID(),"CANCEL_LEAVE",s.target(),s.actor(),s.approver(),null,original,"2026-11-01");return null;
        });
        denied("23505",s,()->request(s,UUID.randomUUID(),"CANCEL_LEAVE",s.target(),s.actor(),s.approver(),null,original,"2026-11-01"));
    }
    @Test void onlyOneCurrentCorrectionEffectExistsAndPriorEffectIsPreserved() {
        var s=scope();UUID first=UUID.randomUUID(),second=UUID.randomUUID(),effect=UUID.randomUUID();
        tx.execute(s.tenant(),()->{
            request(s,first,"ATTENDANCE_CORRECTION",s.target(),s.actor(),s.approver(),null,null,"2026-11-01");
            app.update("UPDATE tna.approval_request SET status='APPROVED' WHERE tenant_id=? AND id=?",s.tenant(),first);
            request(s,second,"ATTENDANCE_CORRECTION",s.target(),s.actor(),s.approver(),null,null,"2026-11-01");
            effect(s,effect,first,"CORRECTION","2026-11-01",null,null);return null;
        });
        denied("23505",s,()->effect(s,UUID.randomUUID(),second,"CORRECTION","2026-11-01",null,null));
        tx.execute(s.tenant(),()->{
            app.update("UPDATE tna.request_effect SET effect_status='SUPERSEDED' WHERE tenant_id=? AND id=?",s.tenant(),effect);
            effect(s,UUID.randomUUID(),second,"CORRECTION","2026-11-01",null,effect);return null;
        });
        assertEquals(2,admin.queryForObject("SELECT count(*) FROM tna.request_effect WHERE tenant_id=?",Integer.class,s.tenant()));
    }
    @Test void ledgerOpeningUseAndRestorationReferencesPreventDoubleEffects() {
        var s=scope();UUID request=UUID.randomUUID(),effect=UUID.randomUUID(),use=UUID.randomUUID();
        tx.execute(s.tenant(),()->{
            bucket(s);request(s,request,"LEAVE_DAY",s.target(),s.actor(),s.approver(),s.bucket(),null,"2026-11-01");
            effect(s,effect,request,"LEAVE",null,null,null);
            ledger(s,UUID.randomUUID(),"OPENING",10,null,null,null);
            ledger(s,use,"USE",-1,effect,"2026-11-01",null);return null;
        });
        denied("23505",s,()->ledger(s,UUID.randomUUID(),"OPENING",10,null,null,null));
        denied("23505",s,()->ledger(s,UUID.randomUUID(),"USE",-1,effect,"2026-11-01",null));
        denied("23514",s,()->ledger(s,UUID.randomUUID(),"USE",1,effect,"2026-11-02",null));
        tx.execute(s.tenant(),()->ledger(s,UUID.randomUUID(),"RESTORE",1,effect,"2026-11-01",use));
        denied("23505",s,()->ledger(s,UUID.randomUUID(),"RESTORE",1,effect,"2026-11-01",use));
        assertEquals(10,admin.queryForObject("SELECT sum(signed_quantity_days) FROM tna.leave_ledger WHERE tenant_id=?",Integer.class,s.tenant()));
    }
    private Scope scope() {
        var s=new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 신청 고객사')",s.tenant(),"WORKFLOW-SCHEMA-"+s.tenant());
        for(UUID employee:List.of(s.actorEmployee(),s.target(),s.approver())) admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 사원',DATE '2020-01-01')",s.tenant(),employee,employee.toString());
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'actor@example.test','synthetic-only')",s.tenant(),s.actor(),s.actorEmployee());
        admin.update("""
                INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,DATE '2026-01-01','합성 배정',?,?)
                """,s.tenant(),s.assignment(),s.target(),s.approver(),s.actor(),s.actor());return s;
    }
    private void seed(Scope s,String part) {
        bucket(s);UUID request=UUID.randomUUID(),correction=UUID.randomUUID(),effect=UUID.randomUUID();
        request(s,request,"LEAVE_DAY",s.target(),s.actor(),s.approver(),s.bucket(),null,"2026-11-01");
        request(s,correction,"ATTENDANCE_CORRECTION",s.target(),s.actor(),s.approver(),null,null,"2026-11-02");
        UUID template=UUID.randomUUID(),schedule=UUID.randomUUID(),calendar=UUID.randomUUID();
        app.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'FIXED','합성 일정',?,?)",s.tenant(),template,s.actor(),s.actor());
        app.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,DATE '2026-01-01','합성 버전',?)",s.tenant(),schedule,template,s.actor());
        app.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,DATE '2026-01-01',DATE '2027-01-01','합성 검토',?)",s.tenant(),calendar,s.actor());
        app.update("INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,schedule_version_id,calendar_version_id,leave_part) VALUES (?,?,?,DATE '2026-11-01',0.5,?,?,?)",s.tenant(),UUID.randomUUID(),request,schedule,calendar,part);
        app.update("""
                INSERT INTO tna.correction_detail(tenant_id,id,request_id,work_date,base_input_revision,source_fingerprint,
                    in_action,out_action,requested_in_at,requested_out_at,actual_breaks,presence_as_work,breaks_confirmed,before_snapshot)
                VALUES (?,?,?,DATE '2026-11-02',0,?,'SET','SET',TIMESTAMPTZ '2026-11-02 09:00+09',
                    TIMESTAMPTZ '2026-11-02 18:00+09','[]'::jsonb,true,true,'{}'::jsonb)
                """,s.tenant(),UUID.randomUUID(),correction,"a".repeat(64));
        effect(s,effect,request,"LEAVE",null,null,null);ledger(s,UUID.randomUUID(),"OPENING",10,null,null,null);
        app.update("INSERT INTO tna.approval_event(tenant_id,id,request_id,event_kind,actor_account_id,to_status,reason) VALUES (?,?,?,'SUBMITTED',?,'PENDING','합성 신청')",s.tenant(),UUID.randomUUID(),request,s.actor());
    }
    private int bucket(Scope s) {return app.update("""
            INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,usable_to,source_reference,created_by)
            VALUES (?,?,?,DATE '2026-10-24',DATE '2026-10-24',DATE '2026-12-31','합성 기초 자료',?)
            """,s.tenant(),s.bucket(),s.target(),s.actor());}
    private int request(Scope s,UUID id,String kind,UUID target,UUID actor,UUID approver,UUID bucket,UUID original,String date) {
        return app.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,
                    approver_assignment_id,approver_assignment_version,reason,original_request_id,leave_bucket_id,from_date,to_date,client_command_id,attendance_type_version_id)
                VALUES (?,?,?,?,?,'PENDING',?,?,0,'합성 신청',?,?,?::date,?::date,?,
                  (SELECT v.id FROM tna.attendance_type_version v JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
                   WHERE t.tenant_id=? AND t.system_kind=? AND v.revision=1))
                """,s.tenant(),id,target,actor,kind,approver,s.assignment(),original,bucket,date,date,UUID.randomUUID(),s.tenant(),
                    kind.equals("ATTENDANCE_CORRECTION")?"ATTENDANCE_CORRECTION":"ANNUAL_LEAVE");
    }
    private int effect(Scope s,UUID id,UUID request,String kind,String date,UUID reverses,UUID supersedes) {return app.update("""
            INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,work_date,effect_kind,effect_status,
                validated_input_refs,reverses_effect_id,supersedes_effect_id,created_by)
            VALUES (?,?,?,?,?::date,?,'EFFECTIVE','[]'::jsonb,?,?,?)
            """,s.tenant(),id,request,s.target(),date,kind,reverses,supersedes,s.actor());}
    private int ledger(Scope s,UUID id,String kind,int quantity,UUID effect,String date,UUID reverses) {return app.update("""
            INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,
                request_effect_id,leave_date,reverses_entry_id,source_reference,reason,created_by)
            VALUES (?,?,?,?,?,?,DATE '2026-10-24',?,?::date,?,'합성 원장 근거','합성 원장',?)
            """,s.tenant(),id,s.bucket(),s.target(),kind,quantity,effect,date,reverses,s.actor());}
    private void denied(String state,Scope s,Runnable action) {
        var error=assertThrows(DataAccessException.class,()->tx.execute(s.tenant(),()->{action.run();return null;}));
        assertEquals(state,((SQLException)error.getMostSpecificCause()).getSQLState());
    }
    private record Scope(UUID tenant,UUID actorEmployee,UUID actor,UUID target,UUID approver,UUID assignment,UUID bucket) { }
}
