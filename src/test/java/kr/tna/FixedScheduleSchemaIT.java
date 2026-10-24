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

class FixedScheduleSchemaIT {
    private static final List<String> TABLES = List.of("schedule_template", "schedule_version", "schedule_day",
            "schedule_break", "schedule_assignment");
    private static JdbcTemplate admin;
    private static JdbcTemplate app;
    private static JdbcTemplate auth;
    private static TenantTransactions transactions;

    @BeforeAll
    static void database() {
        var db = DatabaseFixture.start();
        admin = new JdbcTemplate(db.adminDataSource());
        app = new JdbcTemplate(db.appDataSource());
        auth = new JdbcTemplate(db.authDataSource());
        transactions = new TenantTransactions(db.appDataSource());
    }

    @Test
    void scheduleSchemaExists() {
        for (String table : TABLES) {
            assertNotNull(admin.queryForObject("SELECT to_regclass(?)::text", String.class, "tna." + table));
            assertTrue(admin.queryForObject("""
                    SELECT c.relrowsecurity AND c.relforcerowsecurity
                    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='tna' AND c.relname=?
                    """, Boolean.class, table));
        }
    }

    @Test
    void immutableVersionDaysAndBreaksHaveNoRuntimeUpdateOrDeleteGrants() {
        var scope = scope();
        for (String table : TABLES) {
            rejects("42501", scope, () -> app.update("DELETE FROM tna." + table));
            rejects("42501", scope, () -> app.execute("TRUNCATE tna." + table));
            assertThrows(DataAccessException.class, () -> auth.queryForList("SELECT * FROM tna." + table));
        }
        rejects("42501", scope, () -> app.update("UPDATE tna.schedule_version SET reason='changed'"));
        rejects("42501", scope, () -> app.update("UPDATE tna.schedule_day SET start_time=TIME '10:00'"));
        rejects("42501", scope, () -> app.update("UPDATE tna.schedule_break SET start_time=TIME '12:30'"));
        rejects("42501", scope, () -> app.update("UPDATE tna.schedule_template SET code='changed'"));
        rejects("42501", scope, () -> app.update("UPDATE tna.schedule_assignment SET valid_from=DATE '2020-01-01'"));
        transactions.execute(scope.tenant(), () -> {
            assertEquals(1, app.update("""
                    UPDATE tna.schedule_template SET name='새 표시 이름',active=false,version=version+1,
                        updated_at=now(),updated_by=? WHERE tenant_id=? AND id=?
                    """, scope.actor(),scope.tenant(),scope.template()));
            return null;
        });
    }

    @Test
    void tenantsAreIsolatedWithAndWithoutExplicitPredicatesAcrossReusedConnections() {
        var own = scope();
        var other = scope();
        for (var scope : List.of(own, other)) {
            transactions.execute(scope.tenant(), () -> {
                day(scope, scope.version(), 1, true, "09:00", "18:00");
                rest(scope, scope.version(), 1, 1, "12:00", "13:00");
                assignment(scope, UUID.randomUUID(), scope.employee(), scope.version(), "2026-10-24", null, scope.actor());
                return null;
            });
        }
        for (String table : TABLES) {
            assertEquals(0, app.queryForObject("SELECT count(*) FROM tna." + table,Integer.class));
            for (var scope : List.of(own, other)) {
                assertEquals(List.of(scope.tenant()), transactions.execute(scope.tenant(), () ->
                        app.queryForList("SELECT DISTINCT tenant_id FROM tna." + table,UUID.class)));
                UUID foreign = scope == own ? other.tenant() : own.tenant();
                assertEquals(0, transactions.execute(scope.tenant(), () -> app.queryForObject(
                        "SELECT count(*) FROM tna." + table + " WHERE tenant_id=?",Integer.class,foreign)));
                assertEquals(0, app.queryForObject("SELECT count(*) FROM tna." + table,Integer.class));
            }
        }
        rejects("42501", own, () -> day(other,other.version(),2,true,"09:00","18:00"));
        rejects("42501", own, () -> assignment(other,UUID.randomUUID(),other.employee(),other.version(),
                "2027-01-01",null,other.actor()));
    }

    @Test
    void compositeReferencesNeverAllowCrossTenantTemplateVersionEmployeeOrActor() {
        var own=scope();
        var other=scope();
        rejects("23503",own,()-> version(own,UUID.randomUUID(),other.template(),2,own.actor()));
        rejects("23503",own,()-> version(own,UUID.randomUUID(),own.template(),2,other.actor()));
        rejects("23503",own,()-> day(own,other.version(),1,true,"09:00","18:00"));
        rejects("23503",own,()-> rest(own,other.version(),1,1,"12:00","13:00"));
        rejects("23503",own,()-> assignment(own,UUID.randomUUID(),other.employee(),own.version(),
                "2026-10-24",null,own.actor()));
        rejects("23503",own,()-> assignment(own,UUID.randomUUID(),own.employee(),other.version(),
                "2026-10-24",null,own.actor()));
        rejects("23503",own,()-> assignment(own,UUID.randomUUID(),own.employee(),own.version(),
                "2026-10-24",null,other.actor()));
    }

    @Test
    void dayChecksRequireSupportedWeekdayAndAValidSameDayWorkInterval() {
        var scope=scope();
        rejects("23514",scope,()-> day(scope,scope.version(),0,true,"09:00","18:00"));
        rejects("23514",scope,()-> day(scope,scope.version(),8,true,"09:00","18:00"));
        rejects("23514",scope,()-> day(scope,scope.version(),1,true,"18:00","09:00"));
        rejects("23514",scope,()-> day(scope,scope.version(),1,true,"09:00","09:00"));
        rejects("23514",scope,()-> day(scope,scope.version(),1,true,null,"18:00"));
        rejects("23514",scope,()-> day(scope,scope.version(),1,false,"09:00","18:00"));
        transactions.execute(scope.tenant(),()-> {
            day(scope,scope.version(),1,true,"09:00:00.123","18:00:00.456");
            day(scope,scope.version(),7,false,null,null);
            return null;
        });
        rejects("23505",scope,()-> day(scope,scope.version(),1,true,"10:00","19:00"));
        assertEquals(4,admin.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema='tna'
                  AND table_name IN ('schedule_day','schedule_break') AND data_type='time without time zone'
                  AND datetime_precision=3
                """,Integer.class));
    }

    @Test
    void plannedBreaksRejectInvertedAndOverlappingIntervalsButAcceptAdjacentOnes() {
        var scope=scope();
        transactions.execute(scope.tenant(),()-> day(scope,scope.version(),1,true,"09:00","18:00"));
        rejects("23514",scope,()-> rest(scope,scope.version(),1,1,"13:00","12:00"));
        rejects("23514",scope,()-> rest(scope,scope.version(),1,0,"12:00","13:00"));
        transactions.execute(scope.tenant(),()-> rest(scope,scope.version(),1,1,"12:00","12:30"));
        rejects("23P01",scope,()-> rest(scope,scope.version(),1,2,"12:15","13:00"));
        transactions.execute(scope.tenant(),()-> rest(scope,scope.version(),1,2,"12:30","13:00"));
        assertEquals(2,admin.queryForObject("SELECT count(*) FROM tna.schedule_break WHERE tenant_id=?",
                Integer.class,scope.tenant()));
    }

    @Test
    void assignmentRangesAcceptAdjacencyAndCloseOnlyTheExistingOpenInterval() {
        var scope=scope();
        UUID current=UUID.randomUUID();
        transactions.execute(scope.tenant(),()-> {
            assignment(scope,UUID.randomUUID(),scope.employee(),scope.version(),"2026-10-24","2026-11-01",scope.actor());
            assignment(scope,current,scope.employee(),scope.version(),"2026-11-01",null,scope.actor());
            return null;
        });
        rejects("23P01",scope,()-> assignment(scope,UUID.randomUUID(),scope.employee(),scope.version(),
                "2026-10-31","2026-11-02",scope.actor()));
        rejects("23514",scope,()-> assignment(scope,UUID.randomUUID(),scope.employee(),scope.version(),
                "2026-12-01","2026-12-01",scope.actor()));
        transactions.execute(scope.tenant(),()-> {
            assertEquals(1,app.update("""
                    UPDATE tna.schedule_assignment SET valid_to=DATE '2026-12-01',version=version+1,
                        updated_at=now(),updated_by=? WHERE tenant_id=? AND id=? AND version=0
                    """,scope.actor(),scope.tenant(),current));
            assignment(scope,UUID.randomUUID(),scope.employee(),scope.version(),"2026-12-01",null,scope.actor());
            return null;
        });
        assertEquals(3,admin.queryForObject("SELECT count(*) FROM tna.schedule_assignment WHERE tenant_id=?",
                Integer.class,scope.tenant()));
    }

    @Test
    void templateCodesAndVersionRevisionsStayUniqueWithValidMetadata() {
        var scope=scope();
        rejects("23505",scope,()-> template(scope,UUID.randomUUID(),"FIXED"));
        rejects("23514",scope,()-> template(scope,UUID.randomUUID()," "));
        rejects("23505",scope,()-> version(scope,UUID.randomUUID(),scope.template(),1,scope.actor()));
        rejects("23514",scope,()-> version(scope,UUID.randomUUID(),scope.template(),0,scope.actor()));
        transactions.execute(scope.tenant(),()-> version(scope,UUID.randomUUID(),scope.template(),2,scope.actor()));
        assertEquals(2,admin.queryForObject("SELECT count(*) FROM tna.schedule_version WHERE tenant_id=?",
                Integer.class,scope.tenant()));
    }

    private Scope scope() {
        var scope=new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 일정 고객사')",
                scope.tenant(),"SCHEDULE-SCHEMA-"+scope.tenant());
        admin.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on)
                VALUES (?,?,'ACTOR','합성 사원',DATE '2026-01-01')
                """,scope.tenant(),scope.employee());
        admin.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash)
                VALUES (?,?,?,'actor@example.test','synthetic-schedule-hash')
                """,scope.tenant(),scope.actor(),scope.employee());
        transactions.execute(scope.tenant(),()-> {
            template(scope,scope.template(),"FIXED");
            version(scope,scope.version(),scope.template(),1,scope.actor());
            return null;
        });
        return scope;
    }

    private int template(Scope scope,UUID id,String code) {
        return app.update("""
                INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by)
                VALUES (?,?,?,'고정 근무',?,?)
                """,scope.tenant(),id,code,scope.actor(),scope.actor());
    }
    private int version(Scope scope,UUID id,UUID template,int revision,UUID actor) {
        return app.update("""
                INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by)
                VALUES (?,?,?,?,DATE '2026-10-24','합성 일정 버전',?)
                """,scope.tenant(),id,template,revision,actor);
    }
    private int day(Scope scope,UUID version,int weekday,boolean workday,String start,String end) {
        return app.update("""
                INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time)
                VALUES (?,?,?,?,?::time,?::time)
                """,scope.tenant(),version,weekday,workday,start,end);
    }
    private int rest(Scope scope,UUID version,int weekday,int sequence,String start,String end) {
        return app.update("""
                INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time)
                VALUES (?,?,?,?,?::time,?::time)
                """,scope.tenant(),version,weekday,sequence,start,end);
    }
    private int assignment(Scope scope,UUID id,UUID employee,UUID version,String from,String to,UUID actor) {
        return app.update("""
                INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,
                    valid_from,valid_to,reason,created_by,updated_by)
                VALUES (?,?,?,?,?::date,?::date,'합성 일정 배정',?,?)
                """,scope.tenant(),id,employee,version,from,to,actor,actor);
    }
    private void rejects(String state,Scope scope,Runnable action) {
        DataAccessException error=assertThrows(DataAccessException.class,()->transactions.execute(scope.tenant(),()-> {
            action.run();return null;
        }));
        assertEquals(state,((SQLException)error.getMostSpecificCause()).getSQLState());
    }
    private record Scope(UUID tenant,UUID employee,UUID actor,UUID template,UUID version) { }
}
