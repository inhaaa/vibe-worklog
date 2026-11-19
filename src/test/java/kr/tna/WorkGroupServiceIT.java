package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.schedule.WorkGroupService;
import kr.tna.schedule.WorkGroupData.*;
import kr.tna.schedule.WorkTimeCodeData;
import kr.tna.schedule.WorkTimeCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class WorkGroupServiceIT {
    @Autowired WorkGroupService service;
    @Autowired kr.tna.schedule.WorkGroupAssignmentService assignments;
    @Autowired WorkTimeCodeService codes;
    private JdbcTemplate db;
    private TnaPrincipal admin;
    private UUID tenant, early, late;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private final LocalDate start = LocalDate.of(today.getYear() + 1, 12, 30);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        var fixture = DatabaseFixture.start();
        p.add("spring.datasource.url",fixture::jdbcUrl);
        p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);
        p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url",fixture::jdbcUrl);
        p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void setUp() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 근무조')",tenant,tenant.toString());
        UUID emp = employee("운영자"), account = UUID.randomUUID();
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant,account,emp,account+"@example.test","synthetic");
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')",tenant,account);
        admin = new TnaPrincipal(tenant,account,emp,1,Set.of("ATTENDANCE_ADMIN"),Instant.now());
        early = workCode("W01","09:00","18:00");
        late = workCode("W02","10:00","19:00");
    }

    @Test void revisionsApplyWithoutReassigningAndRespectYearBoundaryAndTransfers() {
        UUID first = createGroup("G01",early), second = createGroup("G02",early);
        UUID stays = employee("잔류"), leaves = employee("전출"), enters = employee("전입");
        assign(first,start,List.of(stays,leaves));
        LocalDate jan1 = start.plusDays(2);
        assign(second,jan1,List.of(leaves));
        assign(first,jan1.plusDays(3),List.of(enters));
        var change = group(first,"G01",jan1,late,0L);
        var preview = service.previewGroup(admin,change);
        assertEquals(List.of(stays), preview.affected().stream().map(Employee::id).toList());
        assertEquals(List.of(leaves), preview.departing().stream().map(Employee::id).toList());
        assertEquals(List.of(enters), preview.incoming().stream().map(Employee::id).toList());
        var confirmed = change.confirm(preview.token());
        assertEquals(first,service.saveGroup(admin,confirmed));
        assertEquals(first,service.saveGroup(admin,confirmed));
        assertEquals(early,codeAt(stays,jan1.minusDays(1)));
        assertEquals(late,codeAt(stays,jan1));
        assertEquals(early,codeAt(leaves,jan1));
        assertEquals(late,codeAt(enters,jan1.plusDays(3)));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.schedule_assignment WHERE tenant_id=? AND employee_id=?",Integer.class,tenant,stays));
    }

    @Test void batchIsAtomicAndAStaleImpactTokenCannotPublish() {
        UUID first = createGroup("G01",early), other = createGroup("G02",late);
        UUID a = employee("예약"), b = employee("새 배정");
        var revision = group(first,"G01",start.plusDays(3),late,0L);
        String token = service.previewGroup(admin,revision).token();
        assign(first,start,List.of(a));
        assertEquals(409,assertThrows(OrgException.class,()->service.saveGroup(admin,revision.confirm(token))).status());
        var batch = assignment(other,start,List.of(a,b));
        var preview = assignments.previewAssignments(admin,batch);
        assertFalse(preview.conflicts().isEmpty());
        assertThrows(OrgException.class,()->assignments.saveAssignments(admin,batch.confirm(preview.token())));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM tna.schedule_assignment WHERE tenant_id=? AND employee_id=?",Integer.class,tenant,b));
        var replacement = new AssignmentCommand(other,start,List.of(a),"예약 정정",
                List.of(preview.rows().getFirst().assignmentId()),null,UUID.randomUUID());
        assignments.saveAssignments(admin,replacement.confirm(assignments.previewAssignments(admin,replacement).token()));
        assertEquals(late,codeAt(a,start));
    }

    @Test void rejectedUnpublishedCodeAndInvalidWeekAndNonAdministrator() {
        assertThrows(OrgException.class,()->service.previewGroup(admin,group(null,"BAD",start,UUID.randomUUID(),null)));
        var invalid = new GroupCommand(null,"G01","합성",start,List.of(),"테스트",null,null,null,UUID.randomUUID());
        assertThrows(OrgException.class,()->service.previewGroup(admin,invalid));
        var employee = new TnaPrincipal(tenant,admin.accountId(),admin.employeeId(),1,Set.of("EMPLOYEE"),Instant.now());
        assertEquals(403,assertThrows(OrgException.class,()->service.groups(employee,start)).status());
    }

    @Test void selectiveGroupKeepsDailyStandardsAndItsPinnedPolicyUntilTheGroupRevisionChanges() {
        var monday=start.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
        var regular=selective("SEL1",null,null,today,420,null);
        var shortDay=selective("SEL2",null,null,today,300,"CALENDAR_MONTH");
        var initial=mixedWeek(null,"GS",monday,null,regular.id(),shortDay.id());
        var preview=service.previewGroup(admin,initial);
        assertEquals(4*420+300,preview.weeklyMinutes());
        assertEquals(420,preview.days().getFirst().minutes());
        assertEquals("SELECTIVE",preview.days().getFirst().workMode());
        assertEquals(LocalTime.of(10,0),preview.days().getFirst().coreStart());
        assertEquals(0,preview.days().getLast().minutes());
        UUID group=service.saveGroup(admin,initial.confirm(preview.token())), employee=employee("선택근무");
        assign(group,monday,List.of(employee));
        var future=monday.plusWeeks(1);
        var code=codes.catalog(admin,monday).codes().stream().filter(c->c.code().equals("SEL1")).findFirst().orElseThrow();
        var revised=selective("SEL1",code.id(),code.version(),future,360,"ISO_WEEK");
        var pinned=service.groups(admin,future).stream().filter(g->g.id().equals(group)).findFirst().orElseThrow().days().getFirst();
        assertEquals(420,pinned.minutes());
        assertEquals("CALENDAR_MONTH",pinned.settlementPeriod());
        var change=mixedWeek(group,"GS",future,0L,revised.id(),revised.id());
        service.saveGroup(admin,change.confirm(service.previewGroup(admin,change).token()));
        assertEquals(shortDay.id(),codeAt(employee,future.minusDays(3)));
        assertEquals(revised.id(),codeAt(employee,future));
        var newDay=service.groups(admin,future).stream().filter(g->g.id().equals(group)).findFirst().orElseThrow().days().getFirst();
        assertEquals("ISO_WEEK",newDay.settlementPeriod());
        assertEquals(360,newDay.standardMinutes());
    }

    @Test void groupRejectsMixedModesAndDifferentSelectiveSettlementPeriods() {
        var month=selective("SM",null,null,today,420,"CALENDAR_MONTH");
        var week=selective("SW",null,null,today,420,"ISO_WEEK");
        var fixed=mixedWeek(null,"MX",start,null,month.id(),early);
        var periods=mixedWeek(null,"MP",start,null,week.id(),month.id());
        assertEquals(422,assertThrows(OrgException.class,()->service.previewGroup(admin,fixed)).status());
        assertEquals(422,assertThrows(OrgException.class,()->service.previewGroup(admin,periods)).status());
    }

    private WorkTimeCodeData.Version selective(String code,UUID id,Long expected,LocalDate date,int minutes,String period) {
        var command=new WorkTimeCodeData.Command(UUID.randomUUID(),id,expected,code,code,date,true,
                LocalTime.of(7,0),LocalTime.of(20,0),null,null,null,null,
                List.of(new WorkTimeCodeData.Break("REGULAR",LocalTime.NOON,LocalTime.of(13,0))),
                "선택근무 합성",null,"SELECTIVE",LocalTime.of(10,0),LocalTime.of(15,0),minutes,period);
        UUID master=codes.save(admin,command);
        assertEquals(master,codes.save(admin,command));
        var version=codes.catalog(admin,date).codes().stream().filter(c->c.id().equals(master)).findFirst().orElseThrow().versions().getFirst();
        assertEquals(minutes,version.scheduledMinutes());
        assertEquals("SELECTIVE",version.workMode());
        assertEquals(0,version.overtimeMinutes());
        return version;
    }

    private GroupCommand mixedWeek(UUID id,String code,LocalDate date,Long version,UUID weekdays,UUID friday) {
        var days=IntStream.rangeClosed(1,7).mapToObj(day->new WeekInput(day,day<5?weekdays:day==5?friday:null)).toList();
        return new GroupCommand(id,code,code,date,days,"요일별 기준",version,null,null,UUID.randomUUID());
    }

    @Test void firstAssignmentTodayCanStartButOngoingWorkCannotSwitchEvenWithExplicitReservation() {
        var midnight=workCode("W00","00:00","08:00");
        var morning=group(null,"G00",today,midnight,null);
        var later=group(null,"G02",today,late,null);
        var group0=service.saveGroup(admin,morning.confirm(service.previewGroup(admin,morning).token()));
        var group2=service.saveGroup(admin,later.confirm(service.previewGroup(admin,later).token()));
        UUID employed=employee("근무 시각 종료");
        assign(group0,today,List.of(employed));
        var edit=group(group0,"G00",today,late,0L);
        var original=service.groups(admin,today).stream().filter(g->g.id().equals(group0)).findFirst().orElseThrow();
        var replace=new GroupCommand(edit.id(),edit.code(),edit.name(),today,edit.days(),"당일 예약 변경",0L,original.revisionId(),null,UUID.randomUUID());
        assertTrue(service.previewGroup(admin,replace).conflicts().stream().anyMatch(c->c.contains("시작 시각")));
        var transfer=assignment(group2,today,List.of(employed));
        assertNull(assignments.previewAssignments(admin,transfer).rows().getFirst().assignmentId());
        var reserved=db.queryForObject("SELECT id FROM tna.schedule_assignment WHERE tenant_id=? AND employee_id=?",UUID.class,tenant,employed);
        var replaceAssignment=new AssignmentCommand(group2,today,List.of(employed),"당일 변경",List.of(reserved),null,UUID.randomUUID());
        assertTrue(assignments.previewAssignments(admin,replaceAssignment).conflicts().stream().anyMatch(c->c.contains("시작 시각")));
    }

    @Test void insertingAGroupBetweenLegacyReservationsPreservesPinnedHistoryAndFutureAssignment() {
        UUID first = createGroup("G01",early);
        UUID person = employee("기존 고정배정");
        UUID template = UUID.randomUUID(),version = UUID.randomUUID(),physical = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'LEGACY','원래 일정',?,?)",
                tenant,template,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'원래 일정',?)",
                tenant,version,template,today,admin.accountId());
        db.update("""
                INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,valid_to,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,?,'기존 일정',?,?)
                """,tenant,physical,person,version,today,start.plusDays(5),admin.accountId(),admin.accountId());
        db.update("""
                INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'기존 예약',?,?)
                """,tenant,UUID.randomUUID(),person,version,start.plusDays(5),admin.accountId(),admin.accountId());
        var form=assignment(first,start,List.of(person));
        var preview=assignments.previewAssignments(admin,form);
        assertTrue(preview.rows().getFirst().currentGroup().contains("기존 고정배정"));
        assertEquals(start.plusDays(5),preview.rows().getFirst().nextStart());
        var command=form.confirm(preview.token());
        assertEquals(assignments.saveAssignments(admin,command),assignments.saveAssignments(admin,command));
        assertEquals(early,codeAt(person,start));
        assertEquals(2,db.queryForObject("""
                SELECT count(*) FROM tna.effective_schedule_assignment WHERE tenant_id=? AND employee_id=?
                AND schedule_version_id=? AND work_group_id IS NULL
                """,Integer.class,tenant,person,version));
        assertEquals(start,db.queryForObject("SELECT valid_to FROM tna.schedule_assignment WHERE tenant_id=? AND id=?",LocalDate.class,tenant,physical));
    }

    @Test void simultaneousTransfersFromOnePreviewCanCommitOnlyOneReservation() throws Exception {
        UUID a=createGroup("G01",early), b=createGroup("G02",late), person=employee("경합 사원");
        var aCommand=assignment(a,start,List.of(person));
        var bCommand=assignment(b,start,List.of(person));
        var ready=new java.util.concurrent.CountDownLatch(1);
        List<AssignmentCommand> drafts=List.of(aCommand.confirm(assignments.previewAssignments(admin,aCommand).token()),
                bCommand.confirm(assignments.previewAssignments(admin,bCommand).token()));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures=drafts.stream().map(form->executor.submit(()->{
                ready.await();
                try { assignments.saveAssignments(admin,form); return 200; }
                catch(OrgException e) { return e.status(); }
            })).toList();
            ready.countDown();
            var statuses=new java.util.ArrayList<Integer>();
            for(var result:futures) statuses.add(result.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(List.of(200,409),statuses.stream().sorted().toList());
        }
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.schedule_assignment WHERE tenant_id=? AND employee_id=?",
                Integer.class,tenant,person));
    }

    @Test void revisingACodeRequiresEachGroupToAdoptItsNewVersionIndependently() {
        UUID a=createGroup("G01",early), b=createGroup("G02",early);
        UUID stays=employee("기존 버전"), changes=employee("개정 버전");
        assign(a,start,List.of(changes));
        assign(b,start,List.of(stays));
        var date=start.plusDays(1);
        UUID newCode=UUID.randomUUID();
        db.update("""
                INSERT INTO tna.work_time_code_version(tenant_id,id,code_id,revision,effective_from,enabled,name,start_time,end_time,reason,created_by)
                SELECT tenant_id,?,code_id,2,?,enabled,name,TIME '10:00',TIME '19:00','기준 변경',created_by
                FROM tna.work_time_code_version WHERE tenant_id=? AND id=?
                """,newCode,date,tenant,early);
        assertEquals(early,codeAt(changes,date));
        var edit=group(a,"G01",date,newCode,0L);
        service.saveGroup(admin,edit.confirm(service.previewGroup(admin,edit).token()));
        assertEquals(early,codeAt(changes,start));
        assertEquals(newCode,codeAt(changes,date));
        assertEquals(early,codeAt(stays,date));
        var revise=group(a,"G01",date,late,1L);
        var published=service.groups(admin,date).stream().filter(g->g.id().equals(a)).findFirst().orElseThrow();
        assertFalse(service.previewGroup(admin,revise).conflicts().isEmpty());
        var replace=new GroupCommand(a,"G01","G01",date,revise.days(),"예약 교체",1L,published.revisionId(),null,UUID.randomUUID());
        service.saveGroup(admin,replace.confirm(service.previewGroup(admin,replace).token()));
        assertEquals(late,codeAt(changes,date));
        assertEquals(3,service.groups(admin,date).stream().filter(g->g.id().equals(a)).findFirst().orElseThrow().revisions().size());
    }

    @Test void explicitlyReplacingAnExistingReservationStopsAtTheLastEmploymentDay() {
        UUID a=createGroup("G01",early), b=createGroup("G02",late), person=employee("퇴사 예정");
        assign(a,start,List.of(person));
        LocalDate lastDay=start.plusDays(3);
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?",lastDay,tenant,person);
        var request=assignment(b,start,List.of(person));
        var old=assignments.previewAssignments(admin,request).rows().getFirst().assignmentId();
        var replace=new AssignmentCommand(b,start,List.of(person),"예약 변경",List.of(old),null,UUID.randomUUID());
        assignments.saveAssignments(admin,replace.confirm(assignments.previewAssignments(admin,replace).token()));
        assertEquals(lastDay.plusDays(1),db.queryForObject("SELECT valid_to FROM tna.schedule_assignment WHERE tenant_id=? AND id=?",
                LocalDate.class,tenant,old));
        assertEquals(late,codeAt(person,lastDay));
        assertFalse(assignments.previewAssignments(admin,assignment(a,lastDay.plusDays(1),List.of(person))).conflicts().isEmpty());
    }

    @Test void tenantViewsDoNotLeakAndMembershipCannotReferenceForeignEmployees() {
        UUID firstTenant=tenant,firstGroup=createGroup("G01",early),firstEmployee=employee("다른 회사");
        assign(firstGroup,start,List.of(firstEmployee));
        setUp();
        var group=createGroup("G02",late);
        var foreign=assignment(group,start,List.of(firstEmployee));
        assertEquals(404,assertThrows(OrgException.class,()->assignments.previewAssignments(admin,foreign)).status());
        var source=DatabaseFixture.start().appDataSource();
        var app=new JdbcTemplate(source);
        var transactions=new kr.tna.common.tenant.TenantTransactions(source);
        for(String table:List.of("work_time_code","work_time_code_version","work_time_code_break","work_group","work_group_day")) {
            assertTrue(db.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid=to_regclass(?)",
                    Boolean.class,"tna."+table));
        }
        for(String table:List.of("work_time_code_version","work_time_code_break","work_group_day","schedule_version")) {
            assertFalse(db.queryForObject("SELECT has_any_column_privilege('tna_app',?,'UPDATE') OR has_table_privilege('tna_app',?,'DELETE')",
                    Boolean.class,"tna."+table,"tna."+table));
        }
        for(String table:List.of("work_time_code","work_time_code_version","work_group","work_group_day","effective_schedule_assignment","work_group_revision_period")) {
            assertEquals(0,transactions.execute(tenant,()->app.queryForObject(
                    "SELECT count(*) FROM tna."+table+" WHERE tenant_id=?",Integer.class,firstTenant)));
            assertEquals(0,app.queryForObject("SELECT count(*) FROM tna."+table,Integer.class));
        }
    }

    private UUID createGroup(String code, UUID timeCode) {
        var command = group(null,code,start,timeCode,null);
        return service.saveGroup(admin,command.confirm(service.previewGroup(admin,command).token()));
    }
    private GroupCommand group(UUID id,String code,LocalDate date,UUID timeCode,Long version) {
        var days= IntStream.rangeClosed(1,7).mapToObj(day->new WeekInput(day,timeCode)).toList();
        return new GroupCommand(id,code,code,date,days,"합성 근무조",version,null,null,UUID.randomUUID());
    }
    private AssignmentCommand assignment(UUID group, LocalDate date,List<UUID> ids) {
        return new AssignmentCommand(group,date,ids,"합성 배정",List.of(),null,UUID.randomUUID());
    }
    private void assign(UUID group,LocalDate date,List<UUID> ids) {
        var command=assignment(group,date,ids);
        assignments.saveAssignments(admin,command.confirm(assignments.previewAssignments(admin,command).token()));
    }
    private UUID employee(String name) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                tenant,id,id.toString(),name,today.minusYears(1));
        return id;
    }
    private UUID workCode(String code,String from,String to) {
        UUID id=UUID.randomUUID(), version=UUID.randomUUID();
        db.update("INSERT INTO tna.work_time_code(tenant_id,id,code) VALUES (?,?,?)",tenant,id,code);
        db.update("""
                INSERT INTO tna.work_time_code_version(tenant_id,id,code_id,revision,effective_from,name,
                    enabled,start_time,end_time,reason,created_by) VALUES (?,?,?,1,?,?,true,?::time,?::time,'합성',?)
                """,tenant,version,id,today,code,from,to,admin.accountId());
        return version;
    }
    private UUID codeAt(UUID emp,LocalDate date) {
        return db.queryForObject("""
                SELECT d.code_version_id FROM tna.effective_schedule_assignment a
                JOIN tna.work_group_day d ON d.tenant_id=a.tenant_id AND d.schedule_version_id=a.schedule_version_id
                WHERE a.tenant_id=? AND a.employee_id=? AND a.valid_from<=? AND (a.valid_to IS NULL OR a.valid_to>?) AND d.weekday=?
                """,UUID.class,tenant,emp,date,date,date.getDayOfWeek().getValue());
    }
}
