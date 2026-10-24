package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.schedule.ScheduleData.*;
import kr.tna.schedule.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class FixedScheduleServiceIT {
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    @Autowired ScheduleService schedules;
    private JdbcTemplate db;
    private TnaPrincipal actor;
    private UUID tenant;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var fixture = DatabaseFixture.start();
        properties.add("spring.datasource.url", fixture::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", fixture::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void administrator() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 일정 고객사')",
                tenant, "SCHEDULE-" + tenant);
        UUID employee = employee(true, TODAY.minusYears(1), null);
        UUID account = UUID.randomUUID();
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, employee, account + "@example.test", "synthetic-schedule-hash");
        for (String role : List.of("EMPLOYEE", "ATTENDANCE_ADMIN")) {
            db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        }
        actor = new TnaPrincipal(tenant, account, employee, 1, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.now());
    }

    @Test
    void createsNormalizedImmutableSevenDayVersionWithMultipleBreaks() {
        String code = code();
        UUID id = schedules.create(actor, form(" " + code + " ", "  기본 고정  ", TODAY, week(), null));
        assertNotNull(id);
        var template = schedules.get(actor, id);
        assertEquals(code, template.code()); assertEquals("기본 고정", template.name());
        assertTrue(template.active()); assertEquals(0, template.version());
        var version = schedules.versions(actor, id).getFirst();
        assertEquals(1, version.revision()); assertEquals(TODAY, version.validFrom());
        var days = schedules.days(actor, version.id());
        assertEquals(7, days.size());
        assertEquals(5, days.stream().filter(Day::workday).count());
        assertEquals(2, days.getFirst().breaks().size());
        assertEquals(LocalTime.NOON, days.getFirst().breaks().getFirst().startTime());
        assertTrue(days.get(5).breaks().isEmpty());
        assertNull(days.get(5).startTime());
        assertTrue(schedules.list(actor).stream().anyMatch(t -> t.id().equals(id)));
        assertTrue(schedules.assignableVersions(actor).stream().anyMatch(v -> v.id().equals(version.id())));
    }

    @Test
    void normalizedCommandReplayHasOneTemplateVersionAndAudit() {
        UUID command = UUID.randomUUID(); String code = code();
        var form = new TemplateForm(" " + code + " ", " 기본 ", TODAY, week(), null, " 신규 일정 ", command);
        UUID id = schedules.create(actor, form);
        assertEquals(id, schedules.create(actor, new TemplateForm(code, "기본", TODAY, week(), null, "신규 일정", command)));
        assertEquals(1, schedules.versions(actor, id).size());
        assertEquals(1, audit(command));
        assertStatus(409, () -> schedules.create(actor, new TemplateForm(code, "다른 이름", TODAY, week(), null, "신규 일정", command)));
    }

    @Test
    void revisionPreservesPreviouslyAssignedVersionAndChecksOptimisticVersion() {
        UUID template = create();
        var original = schedules.get(actor, template);
        var first = schedules.versions(actor, template).getFirst();
        List<DayForm> later = replace(week(), new DayForm(1, true, "10:00", "19:00", "13:00-14:00"));
        var command = new TemplateForm(original.code(), "늦은 고정", TODAY.plusDays(1), later, 0L, "새 개정", UUID.randomUUID());
        assertEquals(template, schedules.revise(actor, template, command));
        assertEquals(template, schedules.revise(actor, template, command));
        assertEquals(1, schedules.get(actor, template).version());
        assertEquals(2, schedules.versions(actor, template).size());
        assertEquals(LocalTime.of(9,0), schedules.days(actor, first.id()).getFirst().startTime());
        var second = schedules.versions(actor, template).stream().filter(v -> v.revision()==2).findFirst().orElseThrow();
        assertEquals(LocalTime.of(10,0), schedules.days(actor, second.id()).getFirst().startTime());
        assertStatus(409, () -> schedules.revise(actor, template, form(original.code(), "오래된 화면", TODAY, week(), 0L)));
        assertStatus(422, () -> schedules.revise(actor, template, form(code(), "코드 변경", TODAY, week(), 1L)));
    }

    @Test
    void requiresAllSevenUniqueWeekdaysAndAtLeastOneWorkday() {
        var missing = new ArrayList<>(week()); missing.removeLast();
        assertStatus(422, () -> schedules.create(actor, form(code(), "누락", TODAY, missing, null)));
        var duplicate = new ArrayList<>(week()); duplicate.set(6, duplicate.getFirst());
        assertStatus(422, () -> schedules.create(actor, form(code(), "중복", TODAY, duplicate, null)));
        assertStatus(422, () -> schedules.create(actor, form(code(), "요일 범위", TODAY,
                replace(week(), new DayForm(1, false, "", "", "")).stream().map(d -> d.weekday()==1 ? new DayForm(0,false,"","","") : d).toList(), null)));
        assertStatus(422, () -> schedules.create(actor, form(code(), "모두 비근로일", TODAY,
                week().stream().map(d -> new DayForm(d.weekday(), false, "", "", "")).toList(), null)));
    }

    @Test
    void validatesWorkdayTimesAndRejectsCrossMidnightOrOffdayTimes() {
        for (DayForm day : List.of(new DayForm(1,true,"18:00","09:00",""),
                new DayForm(1,true,"09:00","09:00",""), new DayForm(1,true,"","18:00",""),
                new DayForm(1,false,"09:00","18:00",""), new DayForm(1,false,"","","12:00-13:00"),
                new DayForm(1,true,"25:00","18:00",""))) {
            assertStatus(422, () -> schedules.create(actor, form(code(), "잘못된 시간", TODAY, replace(week(), day), null)));
        }
    }

    @Test
    void validatesBreakContainmentOverlapAndExactMillisecondPrecision() {
        for (String breaks : List.of("08:00-09:30", "17:30-19:00", "12:00-12:00", "13:00-12:00",
                "12:00-12:45,12:30-13:00", "12:00", "12:00-13:00,", "12:00:00.0001-13:00")) {
            assertStatus(422, () -> schedules.create(actor, form(code(), "잘못된 휴게", TODAY,
                    replace(week(), new DayForm(1,true,"09:00","18:00",breaks)), null)));
        }
        assertStatus(422, () -> schedules.create(actor, form(code(), "초미만 절삭 금지", TODAY,
                replace(week(),new DayForm(1,true,"09:00:00.0001","18:00","")), null)));
        UUID id=schedules.create(actor,form(code(),"밀리초 보존",TODAY,
                replace(week(),new DayForm(1,true,"09:00:00.001","18:00:00.999","12:00:00.001-12:30:00.001,12:30:00.001-13:00:00.001")),null));
        var days=schedules.days(actor,schedules.versions(actor,id).getFirst().id());
        assertEquals(1_000_000,days.getFirst().startTime().getNano());
        assertEquals(999_000_000,days.getFirst().endTime().getNano());
    }

    @Test
    void textEffectiveDateReasonAndVersionAreRequired() {
        assertStatus(422, () -> schedules.create(actor, form(" ", "일정", TODAY, week(), null)));
        assertStatus(422, () -> schedules.create(actor, form("x".repeat(41), "일정", TODAY, week(), null)));
        assertStatus(422, () -> schedules.create(actor, form(code(), "x".repeat(121), TODAY, week(), null)));
        assertStatus(422, () -> schedules.create(actor, form(code(), "일정", TODAY.minusDays(1), week(), null)));
        assertStatus(422, () -> schedules.create(actor, form(code(), "일정", null, week(), null)));
        assertStatus(422, () -> schedules.create(actor, new TemplateForm(code(),"일정",TODAY,week(),null," ",UUID.randomUUID())));
        UUID id=create();
        assertStatus(422, () -> schedules.revise(actor,id,form(schedules.get(actor,id).code(),"개정",TODAY,week(),null)));
    }

    @Test
    void codeUniquenessIsTenantScopedAndForeignIdentifiersStayHidden() {
        UUID id=create(); String code=schedules.get(actor,id).code();
        assertStatus(409, () -> schedules.create(actor,form(code,"중복",TODAY,week(),null)));
        UUID otherTenant=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'외부 합성')",otherTenant,"OTHER-"+otherTenant);
        UUID foreignEmployee=UUID.randomUUID(), foreignAccount=UUID.randomUUID(), foreign=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'다른 고객사 사원',?)",otherTenant,foreignEmployee,code(),TODAY.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",otherTenant,foreignAccount,foreignEmployee,foreignAccount+"@example.test","synthetic-foreign-schedule-hash");
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,?,?,?)",
                otherTenant,foreign,code,"다른 고객사",foreignAccount,foreignAccount);
        assertFalse(schedules.list(actor).stream().anyMatch(t -> t.id().equals(foreign)));
        assertStatus(404, () -> schedules.get(actor,foreign));
        assertStatus(404, () -> schedules.versions(actor,foreign));
        assertStatus(404, () -> schedules.revise(actor,foreign,form(code,"변경 시도",TODAY,week(),0L)));
    }

    @Test
    void assignmentAppendsHistoryClosesPriorIntervalAndReplaysOnce() {
        UUID employee=employee(true,TODAY.minusYears(1),null);
        UUID first=schedules.versions(actor,create()).getFirst().id();
        UUID second=schedules.versions(actor,create()).getFirst().id();
        var one=new AssignmentForm(first,TODAY,0L,"첫 배정",UUID.randomUUID());
        assertEquals(employee,schedules.assign(actor,employee,one));
        assertEquals(employee,schedules.assign(actor,employee,one));
        assertEquals(1,schedules.assignments(actor,employee).size());
        var two=new AssignmentForm(second,TODAY.plusDays(2),1L,"다음 배정",UUID.randomUUID());
        schedules.assign(actor,employee,two);
        var history=schedules.assignments(actor,employee);
        assertEquals(2,history.size());
        assertEquals(TODAY.plusDays(2),history.stream().filter(a -> a.scheduleVersionId().equals(first)).findFirst().orElseThrow().validTo());
        assertEquals(2L,db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?",Long.class,tenant,employee));
        assertEquals(1,audit(two.commandId()));
    }

    @Test
    void assignmentRejectsStaleVersionSameDayReplacementAndEarlierReservation() {
        UUID employee=employee(true,TODAY.minusYears(1),null);
        UUID version=schedules.versions(actor,create()).getFirst().id();
        schedules.assign(actor,employee,new AssignmentForm(version,TODAY.plusDays(5),0L,"예약",UUID.randomUUID()));
        assertStatus(409,()->schedules.assign(actor,employee,new AssignmentForm(version,TODAY.plusDays(6),0L,"오래된 화면",UUID.randomUUID())));
        assertStatus(422,()->schedules.assign(actor,employee,new AssignmentForm(version,TODAY.plusDays(5),1L,"같은 날",UUID.randomUUID())));
        assertStatus(422,()->schedules.assign(actor,employee,new AssignmentForm(version,TODAY.plusDays(4),1L,"역순",UUID.randomUUID())));
        assertEquals(1,schedules.assignments(actor,employee).size());
    }

    @Test
    void assignmentChecksEmployeeTemplateAndVersionAvailability() {
        UUID employee=employee(true,TODAY.minusYears(1),TODAY.plusDays(3));
        UUID template=schedules.create(actor,form(code(),"미래 버전",TODAY.plusDays(2),week(),null));
        UUID version=schedules.versions(actor,template).getFirst().id();
        assertStatus(422,()->schedules.assign(actor,employee,new AssignmentForm(version,TODAY,0L,"버전 전",UUID.randomUUID())));
        assertStatus(422,()->schedules.assign(actor,employee,new AssignmentForm(version,TODAY.plusDays(4),0L,"퇴사 후",UUID.randomUUID())));
        UUID inactive=employee(false,TODAY.minusYears(1),null);
        assertStatus(422,()->schedules.assign(actor,inactive,new AssignmentForm(version,TODAY.plusDays(2),0L,"비활성",UUID.randomUUID())));
        db.update("UPDATE tna.schedule_template SET active=false WHERE tenant_id=? AND id=?",tenant,template);
        assertStatus(422,()->schedules.assign(actor,employee,new AssignmentForm(version,TODAY.plusDays(2),0L,"비활성 일정",UUID.randomUUID())));
        assertTrue(schedules.assignableVersions(actor).isEmpty());
        assertStatus(404,()->schedules.assign(actor,employee,new AssignmentForm(UUID.randomUUID(),TODAY,0L,"미존재 버전",UUID.randomUUID())));
    }

    @Test
    void assigningUnknownOrForeignEmployeeNeverCreatesRelationship() {
        UUID version=schedules.versions(actor,create()).getFirst().id();
        assertStatus(404,()->schedules.assign(actor,UUID.randomUUID(),new AssignmentForm(version,TODAY,0L,"미존재 사원",UUID.randomUUID())));
        assertStatus(404,()->schedules.assignments(actor,UUID.randomUUID()));
        assertStatus(404,()->schedules.days(actor,UUID.randomUUID()));
    }

    @Test
    void administrationChecksCurrentRoleAndAuthVersionOnEveryOperation() {
        var employeeOnly=new TnaPrincipal(tenant,actor.accountId(),actor.employeeId(),1,Set.of("EMPLOYEE"),Instant.now());
        assertStatus(403,()->schedules.list(employeeOnly));
        assertStatus(403,()->schedules.create(employeeOnly,form(code(),"무권한",TODAY,week(),null)));
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",tenant,actor.accountId());
        assertStatus(403,()->schedules.create(actor,form(code(),"회수된 권한",TODAY,week(),null)));
    }

    @Test
    void concurrentAssignmentsWithTheSameEmployeeVersionCommitOnlyOnce() throws Exception {
        UUID employee=employee(true,TODAY.minusYears(1),null);
        UUID version=schedules.versions(actor,create()).getFirst().id();
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->assignConcurrently(employee,version,ready,start));
            var b=workers.submit(()->assignConcurrently(employee,version,ready,start));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            var statuses=List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertEquals(1,statuses.stream().filter(s -> s==200).count());
            assertEquals(1,statuses.stream().filter(s -> s==409).count());
        }
        assertEquals(1,schedules.assignments(actor,employee).size());
    }

    private int assignConcurrently(UUID employee,UUID version,CountDownLatch ready,CountDownLatch start) throws Exception {
        ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));
        try {schedules.assign(actor,employee,new AssignmentForm(version,TODAY,0L,"경합 배정",UUID.randomUUID()));return 200;}
        catch(OrgException exception){return exception.status();}
    }
    private UUID create(){return schedules.create(actor,form(code(),"합성 일정",TODAY,week(),null));}
    private UUID employee(boolean active,LocalDate joined,LocalDate left){
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on,active) VALUES (?,?,?,'합성 일정 사원',?,?,?)",tenant,id,code(),joined,left,active);
        return id;
    }
    private int audit(UUID command){return db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",Integer.class,tenant,command);}
    private static String code(){return UUID.randomUUID().toString();}
    private static TemplateForm form(String code,String name,LocalDate from,List<DayForm> days,Long version){
        return new TemplateForm(code,name,from,days,version,"합성 일정 검증",UUID.randomUUID());
    }
    private static List<DayForm> week(){
        var result=new ArrayList<DayForm>();
        for(int i=1;i<=7;i++)result.add(new DayForm(i,i<=5,i<=5?"09:00":"",i<=5?"18:00":"",i<=5?"12:00-12:30,12:30-13:00":""));
        return result;
    }
    private static List<DayForm> replace(List<DayForm> days,DayForm replacement){
        return days.stream().map(d -> d.weekday()==replacement.weekday()?replacement:d).toList();
    }
    private static void assertStatus(int status,Runnable action){assertEquals(status,assertThrows(OrgException.class,action::run).status());}
}
