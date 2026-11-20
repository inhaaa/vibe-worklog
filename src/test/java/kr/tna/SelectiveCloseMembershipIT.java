package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.worktime.AttendanceMonthCloseService;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class SelectiveCloseMembershipIT {
    private static final LocalDate FIRST=LocalDate.of(2024,10,27), SECOND=FIRST.plusDays(1);
    private static final String MONTH="2024-10";
    private final UUID tenant=UUID.randomUUID(), version=UUID.randomUUID(), network=UUID.randomUUID();
    private JdbcTemplate db;
    private TnaPrincipal admin;
    @Autowired ClockService clocks;
    @Autowired WorktimeService reports;
    @Autowired AttendanceMonthCloseService closures;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();
        p.add("spring.datasource.url",f::jdbcUrl); p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);
        p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD); p.add("tna.auth.url",f::jdbcUrl);
        p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER); p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }

    @Test void closePinsOnlyEmployedDatesAndReclosingCapturesTheNewMembership() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'선택 마감 합성')",tenant,tenant.toString());
        admin=person("담당",SECOND.plusMonths(1),true);
        var partial=person("일부 재직",FIRST,false);
        var absent=person("월 전체 제외",FIRST.minusMonths(1),false);
        schedule();
        for (var person : List.of(partial,absent)) {
            db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,valid_to,reason,created_by,updated_by) VALUES (?,?,?,?,?,?,'선택 배정',?,?)",
                    tenant,UUID.randomUUID(),person.employeeId(),version,FIRST,SECOND.plusDays(1),admin.accountId(),admin.accountId());
            for (var day : List.of(FIRST,SECOND)) confirm(person,day);
            reports.recalculate(admin,person.employeeId(),FIRST,SECOND,UUID.randomUUID(),"퇴사일 정정 전 집계");
        }
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?",FIRST,tenant,partial.employeeId());
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?",FIRST.withDayOfMonth(1).minusDays(1),tenant,absent.employeeId());
        reports.recalculate(admin,partial.employeeId(),FIRST,FIRST,UUID.randomUUID(),"마감할 재직일만 다시 계산");
        var before=reports.adminSettlements(admin,partial.employeeId(),MONTH).periods();
        assertEquals(1,before.getFirst().appliedDays());
        assertEquals(420,before.getFirst().targetMinutes());
        assertTrue(reports.adminSettlements(admin,absent.employeeId(),MONTH).periods().isEmpty());
        close("CLOSE",0);
        assertEquals(before,reports.adminSettlements(admin,partial.employeeId(),MONTH).periods());
        assertTrue(reports.adminSettlements(admin,absent.employeeId(),MONTH).periods().isEmpty());
        db.update("UPDATE tna.employee SET joined_on=?,left_on=NULL WHERE tenant_id=? AND id=?",SECOND.plusMonths(1),tenant,partial.employeeId());
        db.update("UPDATE tna.employee SET left_on=NULL WHERE tenant_id=? AND id=?",tenant,absent.employeeId());
        assertEquals(before,reports.adminSettlements(admin,partial.employeeId(),MONTH).periods());
        assertTrue(reports.adminSettlements(admin,absent.employeeId(),MONTH).periods().isEmpty());
        close("REOPEN",1);
        assertTrue(reports.adminSettlements(admin,partial.employeeId(),MONTH).periods().isEmpty());
        db.update("UPDATE tna.employee SET joined_on=?,left_on=? WHERE tenant_id=? AND id=?",FIRST,SECOND,tenant,absent.employeeId());
        reports.recalculate(admin,absent.employeeId(),FIRST,SECOND,UUID.randomUUID(),"재마감 대상 근무일");
        close("CLOSE",2);
        var recaptured=reports.adminSettlements(admin,absent.employeeId(),MONTH).periods().getFirst();
        assertEquals(2,recaptured.appliedDays());
        assertEquals(840,recaptured.targetMinutes());
        assertTrue(reports.adminSettlements(admin,partial.employeeId(),MONTH).periods().isEmpty());
        assertEquals(List.of(1,2),db.queryForList("""
                SELECT cardinality(m.result_ids) FROM tna.attendance_month_selective_member m
                JOIN tna.attendance_month_closure c ON c.tenant_id=m.tenant_id AND c.id=m.closure_id
                WHERE m.tenant_id=? ORDER BY c.version
                """,Integer.class,tenant));
    }

    @Test void unknownLegacyMembershipOnlyRequiresReopeningForTheAffectedEmployee() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'이관 마감 합성')",tenant,tenant.toString());
        admin=person("담당",SECOND.plusMonths(1),true);
        var person=person("이관 대상",FIRST,false);
        schedule();
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,valid_to,reason,created_by,updated_by) VALUES (?,?,?,?,?,?,'선택 배정',?,?)",
                tenant,UUID.randomUUID(),person.employeeId(),version,FIRST,SECOND,admin.accountId(),admin.accountId());
        confirm(person,FIRST);
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?",FIRST,tenant,person.employeeId());
        reports.recalculate(admin,person.employeeId(),FIRST,FIRST,UUID.randomUUID(),"이관 대상의 원래 계산 근거");
        close("CLOSE",0);
        // The upgrade test exercises V32 -> V33. Reproduce its unprovable marker with full service evidence here.
        assertEquals(1,db.update("UPDATE tna.attendance_month_selective_member SET result_ids=NULL WHERE tenant_id=? AND employee_id=?",tenant,person.employeeId()));
        var unavailable=assertThrows(OrgException.class,()->reports.adminSettlements(admin,person.employeeId(),MONTH));
        assertEquals(503,unavailable.status());
        assertTrue(unavailable.getMessage().contains(MONTH));
        assertTrue(unavailable.getMessage().contains("재개"));
        assertTrue(reports.adminSettlements(admin,admin.employeeId(),MONTH).periods().isEmpty());
        close("REOPEN",1);
        close("CLOSE",2);
        assertEquals(1,reports.adminSettlements(admin,person.employeeId(),MONTH).periods().getFirst().appliedDays());
    }

    private void close(String action,long version) {
        closures.change(admin,new AttendanceMonthCloseService.Command(MONTH,action,version,"재직 경계 확인",UUID.randomUUID()));
    }

    private TnaPrincipal person(String name,LocalDate joined,boolean manager) {
        UUID employee=UUID.randomUUID(),account=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",tenant,employee,name,name,joined);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?, 'synthetic')",tenant,account,employee,account+"@example.test");
        var roles=manager?Set.of("EMPLOYEE","ATTENDANCE_ADMIN"):Set.of("EMPLOYEE");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);
        return new TnaPrincipal(tenant,account,employee,1,roles,Instant.now());
    }

    private void schedule() {
        UUID template=UUID.randomUUID(),code=UUID.randomUUID(),codeVersion=UUID.randomUUID(),calendar=UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'SEL','선택조',?,?)",tenant,template,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성',?)",tenant,version,template,FIRST,admin.accountId());
        db.update("INSERT INTO tna.work_time_code(tenant_id,id,code) VALUES (?,?,'S1')",tenant,code);
        db.update("""
                INSERT INTO tna.work_time_code_version(tenant_id,id,code_id,revision,effective_from,enabled,name,start_time,end_time,
                    work_mode,standard_minutes,settlement_period,reason,created_by)
                VALUES (?,?,?,1,?,true,'선택','07:00','20:00','SELECTIVE',420,'CALENDAR_MONTH','합성',?)
                """,tenant,codeVersion,code,FIRST,admin.accountId());
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,?,?,'합성',?)",tenant,calendar,FIRST,SECOND.plusDays(1),admin.accountId());
        for(var day : List.of(FIRST,SECOND)) {
            int weekday=day.getDayOfWeek().getValue();
            db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,'07:00','20:00')",tenant,version,weekday);
            db.update("INSERT INTO tna.work_group_day(tenant_id,schedule_version_id,weekday,code_version_id) VALUES (?,?,?,?)",tenant,version,weekday,codeVersion);
            db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,'WORKDAY','근로일','합성')",tenant,calendar,day);
        }
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,'127.0.0.1/32','합성',?,'로컬',?)",
                tenant,network,Timestamp.from(FIRST.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()),admin.accountId());
    }

    private void confirm(TnaPrincipal employee,LocalDate date) {
        for (String kind : List.of("IN","OUT")) {
            var at=Timestamp.from(date.atTime(kind.equals("IN")?9:16,0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
            db.update("""
                    INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,
                        source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP','127.0.0.1',?,?)
                    """,tenant,UUID.randomUUID(),employee.employeeId(),kind,at,at,UUID.randomUUID(),network,date);
        }
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,2,0)",tenant,employee.employeeId(),date);
        var snapshot=clocks.day(employee,date);
        clocks.confirm(employee,new ConfirmationForm(date,2L,0L,true,true,snapshot.sourceFingerprint(),UUID.randomUUID()));
    }
}
