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

class AttendanceSchemaIT {
    private static final List<String> TABLES=List.of("calendar_version","calendar_day","allowed_network",
            "attendance_event","day_input_revision","work_confirmation");
    private static JdbcTemplate admin,app,auth;
    private static TenantTransactions tx;
    @BeforeAll static void database() {
        var db=DatabaseFixture.start();admin=new JdbcTemplate(db.adminDataSource());app=new JdbcTemplate(db.appDataSource());
        auth=new JdbcTemplate(db.authDataSource());tx=new TenantTransactions(db.appDataSource());
    }
    @Test void attendanceSchemaExists() {
        assertNotNull(admin.queryForObject("SELECT to_regclass('tna.attendance_event')::text",String.class));
        for(String table:TABLES) assertTrue(admin.queryForObject("""
                SELECT c.relrowsecurity AND c.relforcerowsecurity FROM pg_class c
                JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='tna' AND c.relname=?
                """,Boolean.class,table));
    }
    @Test void appendOnlyDataAndPrivateIpEvidenceHaveNarrowRuntimeGrants() {
        var s=scope();
        for(String table:TABLES) {
            denied("42501",s,()->app.update("DELETE FROM tna."+table));
            denied("42501",s,()->app.execute("TRUNCATE tna."+table));
            assertThrows(DataAccessException.class,()->auth.queryForList("SELECT * FROM tna."+table));
        }
        for(String table:List.of("calendar_version","calendar_day","attendance_event","work_confirmation")) {
            String column=table.equals("calendar_version")?"reason":table.equals("calendar_day")?"basis":table.equals("attendance_event")?"source":"source_fingerprint";
            denied("42501",s,()->app.update("UPDATE tna."+table+" SET "+column+"='changed'"));
        }
        denied("42501",s,()->app.queryForList("SELECT validated_ip FROM tna.attendance_event"));
        denied("42501",s,()->app.update("UPDATE tna.allowed_network SET network='0.0.0.0/0'::cidr"));
        denied("42501",s,()->app.update("UPDATE tna.day_input_revision SET employee_id='00000000-0000-0000-0000-000000000000'"));
    }
    @Test void tenantIsolationProtectsEveryNewTableAndReturnsCleanConnections() {
        var a=scope();var b=scope();
        for(var s:List.of(a,b)) tx.execute(s.tenant(),()->{ seed(s);return null; });
        for(String table:TABLES) {
            assertEquals(0,app.queryForObject("SELECT count(*) FROM tna."+table,Integer.class));
            for(var s:List.of(a,b)) assertEquals(List.of(s.tenant()),tx.execute(s.tenant(),()->
                    app.queryForList("SELECT DISTINCT tenant_id FROM tna."+table,UUID.class)));
            assertEquals(0,app.queryForObject("SELECT count(*) FROM tna."+table,Integer.class));
        }
        denied("42501",a,()->event(b,UUID.randomUUID(),b.employee(),b.network(),UUID.randomUUID()));
    }
    @Test void eventReferencesMustRemainInsideTheAuthenticatedTenant() {
        var own=scope();var other=scope();
        tx.execute(own.tenant(),()->network(own));tx.execute(other.tenant(),()->network(other));
        denied("23503",own,()->event(own,UUID.randomUUID(),other.employee(),own.network(),UUID.randomUUID()));
        denied("23503",own,()->event(own,UUID.randomUUID(),own.employee(),other.network(),UUID.randomUUID()));
        denied("23503",own,()->app.update("""
                INSERT INTO tna.work_confirmation(tenant_id,id,employee_id,work_date,confirmed_input_revision,
                    review_revision,source_fingerprint,presence_as_work,breaks_match,confirmed_by,break_snapshot,client_command_id)
                VALUES (?,?,?,DATE '2026-10-24',2,1,?,true,true,?,'[]'::jsonb,?)
                """,own.tenant(),UUID.randomUUID(),own.employee(),"a".repeat(64),other.actor(),UUID.randomUUID()));
    }
    @Test void eventCommandIsUniqueWithoutSuppressingDistinctRawEvents() {
        var s=scope();UUID command=UUID.randomUUID();
        tx.execute(s.tenant(),()->{network(s);event(s,UUID.randomUUID(),s.employee(),s.network(),command);return null;});
        denied("23505",s,()->event(s,UUID.randomUUID(),s.employee(),s.network(),command));
        tx.execute(s.tenant(),()->event(s,UUID.randomUUID(),s.employee(),s.network(),UUID.randomUUID()));
        assertEquals(2,admin.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=?",Integer.class,s.tenant()));
    }
    @Test void calendarPeriodsRejectOverlapButKeepAdjacentVersions() {
        var s=scope();
        tx.execute(s.tenant(),()->calendar(s,UUID.randomUUID(),1,"2026-10-24","2026-11-01"));
        denied("23P01",s,()->calendar(s,UUID.randomUUID(),2,"2026-10-31","2026-11-02"));
        tx.execute(s.tenant(),()->calendar(s,UUID.randomUUID(),2,"2026-11-01","2026-12-01"));
        denied("23514",s,()->calendar(s,UUID.randomUUID(),3,"2027-01-01","2027-01-01"));
    }
    @Test void sameDateRevisionsAndConfirmationMetadataRejectInvalidValues() {
        var s=scope();tx.execute(s.tenant(),()->{seed(s);return null;});
        denied("23505",s,()->app.update("""
                INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date) VALUES (?,?,DATE '2026-10-24')
                """,s.tenant(),s.employee()));
        denied("23514",s,()->app.update("UPDATE tna.day_input_revision SET input_revision=-1 WHERE tenant_id=?",s.tenant()));
        denied("23514",s,()->app.update("UPDATE tna.day_input_revision SET review_revision=-1 WHERE tenant_id=?",s.tenant()));
    }
    private Scope scope() {
        var s=new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 출퇴근 고객사')",s.tenant(),"CLOCK-SCHEMA-"+s.tenant());
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'A','합성 사원',DATE '2020-01-01')",s.tenant(),s.employee());
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'actor@example.test','synthetic-only')",s.tenant(),s.actor(),s.employee());
        return s;
    }
    private void seed(Scope s) {
        network(s);UUID version=UUID.randomUUID();calendar(s,version,1,"2026-10-24","2026-10-25");
        app.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,DATE '2026-10-24','WORKDAY','근로일','합성 근거')",s.tenant(),version);
        event(s,UUID.randomUUID(),s.employee(),s.network(),UUID.randomUUID());
        app.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,DATE '2026-10-24',2,1)",s.tenant(),s.employee());
        app.update("""
                INSERT INTO tna.work_confirmation(tenant_id,id,employee_id,work_date,confirmed_input_revision,
                    review_revision,source_fingerprint,presence_as_work,breaks_match,confirmed_by,break_snapshot,client_command_id)
                VALUES (?,?,?,DATE '2026-10-24',2,1,?,true,true,?,'[]'::jsonb,?)
                """,s.tenant(),UUID.randomUUID(),s.employee(),"a".repeat(64),s.actor(),UUID.randomUUID());
    }
    private int network(Scope s) {return app.update("""
            INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by)
            VALUES (?,?,'127.0.0.1/32'::cidr,'합성 허용망',now(),'합성 근거',?)
            """,s.tenant(),s.network(),s.actor());}
    private int event(Scope s,UUID id,UUID employee,UUID network,UUID command) {return app.update("""
            INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,
                client_command_id,source,validated_ip,network_id,assigned_work_date)
            VALUES (?,?,?,'IN',now(),now(),?,'IP','127.0.0.1'::inet,?,DATE '2026-10-24')
            """,s.tenant(),id,employee,command,network);}
    private int calendar(Scope s,UUID id,int revision,String from,String to) {return app.update("""
            INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by)
            VALUES (?,?,?,?::date,?::date,'합성 검토',?)
            """,s.tenant(),id,revision,from,to,s.actor());}
    private void denied(String state,Scope s,Runnable action) {
        var error=assertThrows(DataAccessException.class,()->tx.execute(s.tenant(),()->{action.run();return null;}));
        assertEquals(state,((SQLException)error.getMostSpecificCause()).getSQLState());
    }
    private record Scope(UUID tenant,UUID employee,UUID actor,UUID network) { }
}
