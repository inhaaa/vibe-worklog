package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.*;
import java.util.*;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 같은 주(월~일) 연장 신청·승인 합계가 12시간을 넘는 신청을 막는지 확인한다(설계 §4). */
@SpringBootTest @AutoConfigureMockMvc
class WeeklyOvertimeLimitIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalTime START = LocalTime.of(18,30), END = LocalTime.of(19,30); // 60분
    @Autowired LeaveWorkflowService flow;
    @Autowired MockMvc mvc;
    @Autowired kr.tna.overtime.OvertimePolicyService overtimePolicy;
    @Autowired kr.tna.schedule.WorkTimeCodeService workCodes;
    @Autowired kr.tna.schedule.WorkGroupService workGroups;
    @Autowired kr.tna.schedule.WorkGroupAssignmentService groupAssignments;
    @Autowired kr.tna.common.tenant.TenantTransactions tenantTransactions;
    JdbcTemplate db;
    UUID tenant, organization;
    LocalDate today, monday;
    TnaPrincipal admin, worker, direct, head;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();
        p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());today=LocalDate.now(SEOUL);
        monday=today.plusDays(7).with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY)); // 시작 전 신청이 되도록 미래 주
        tenant=UUID.randomUUID();db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 주 연장 한도')",tenant,"WOL-"+tenant);
        admin=member("관리자",Set.of("EMPLOYEE","ATTENDANCE_ADMIN","APPROVER"));worker=member("신청자",Set.of("EMPLOYEE"));
        direct=member("직접결재",Set.of("EMPLOYEE","APPROVER"));head=member("조직장",Set.of("EMPLOYEE","APPROVER"));
        organization=UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 조직',?,?)",tenant,organization,"ORG-"+organization,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 소속',?,?)",tenant,UUID.randomUUID(),worker.employeeId(),organization,today.minusMonths(1),admin.accountId(),admin.accountId());
        configureChain("O01","O02");
        prepareSchedule();
    }

    @Test void overTwelveHoursIsRejectedWithTheSameMessageOnPreview() throws Exception {
        seedRequest(monday,360,"PENDING",null);
        seedRequest(monday.plusDays(1),301,"APPROVED","EFFECTIVE");
        var e=assertThrows(kr.tna.org.OrgException.class,()->submit(monday.plusDays(2)));
        assertEquals(422,e.status());
        assertTrue(e.getMessage().contains("주 12시간"),e.getMessage());
        mvc.perform(post("/me/overtime/preview").with(as(worker)).with(csrf()).contentType("application/json")
                .content("{\"date\":\""+monday.plusDays(2)+"\",\"start\":\"18:30\",\"end\":\"19:30\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(content().string(org.hamcrest.Matchers.containsString("주 12시간")));
    }

    @Test void exactlyTwelveHoursIsAllowed() {
        seedRequest(monday,360,"PENDING",null);
        seedRequest(monday.plusDays(1),300,"APPROVED","EFFECTIVE");
        assertNotNull(submit(monday.plusDays(2)));
    }

    @Test void rejectedWithdrawnCanceledAndOtherWeekRequestsDoNotCount() {
        seedRequest(monday,720,"REJECTED",null);
        seedRequest(monday.plusDays(1),720,"WITHDRAWN",null);
        seedRequest(monday.plusDays(3),720,"APPROVED","CANCELED");
        seedRequest(monday.minusDays(1),720,"PENDING",null);  // 지난 주 일요일
        seedRequest(monday.plusDays(7),720,"PENDING",null);   // 다음 주 월요일
        assertNotNull(submit(monday.plusDays(2)));
    }

    private UUID submit(LocalDate date) {
        var basis=tenantTransactions.execute(tenant,()->overtimePolicy.evaluate(tenant,worker.employeeId(),date,START,END,true));
        var route=flow.formPreview(worker,null,"OVERTIME").routeToken();
        return flow.submitOvertime(worker,date,START,END,"연장",basis.basisToken(),route,UUID.randomUUID());
    }

    /** 실제로 신청한 뒤 신청 분·상태를 바꿔 그 날짜의 연장 건을 만든다. effectStatus가 있으면 효력 행도 넣는다. */
    private void seedRequest(LocalDate date,int minutes,String status,String effectStatus) {
        UUID id=submit(date);
        db.update("UPDATE tna.overtime_request_detail SET start_time='06:00',end_time='20:00',requested_minutes=? WHERE tenant_id=? AND request_id=?",minutes,tenant,id);
        db.update("UPDATE tna.approval_request SET status=? WHERE tenant_id=? AND id=?",status,tenant,id);
        if(effectStatus!=null) db.update("""
            INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,work_date,effect_kind,effect_status,created_by)
            SELECT tenant_id,?,id,target_employee_id,NULL,'OVERTIME',?,author_account_id FROM tna.approval_request
            WHERE tenant_id=? AND id=?""",UUID.randomUUID(),effectStatus,tenant,id);
    }

    private void prepareSchedule() {
        LocalDate since=today.plusDays(1);
        var start=LocalTime.of(18,30); var end=LocalTime.of(21,0);
        var form=new kr.tna.schedule.WorkTimeCodeData.Command(UUID.randomUUID(),null,null,"WT","주간",since,true,
                LocalTime.of(9,0),LocalTime.of(18,0),null,null,start,end,
                List.of(new kr.tna.schedule.WorkTimeCodeData.Break("AFTER",LocalTime.of(20,0),LocalTime.of(20,15))),
                "합성 연장",null);
        UUID code=workCodes.save(admin,form);
        UUID version=workCodes.catalog(admin,since).codes().stream().filter(c->c.id().equals(code))
                .findFirst().orElseThrow().versions().getFirst().id();
        var days=java.util.stream.IntStream.rangeClosed(1,7)
                .mapToObj(i->new kr.tna.schedule.WorkGroupData.WeekInput(i,version)).toList();
        var group=new kr.tna.schedule.WorkGroupData.GroupCommand(null,"G1","주간조",since,days,"초기",null,null,null,UUID.randomUUID());
        UUID id=workGroups.saveGroup(admin,group.confirm(workGroups.previewGroup(admin,group).token()));
        var member=new kr.tna.schedule.WorkGroupData.AssignmentCommand(id,since,List.of(worker.employeeId()),"배정",List.of(),null,UUID.randomUUID());
        groupAssignments.saveAssignments(admin,member.confirm(groupAssignments.previewAssignments(admin,member).token()));
        // 서비스는 과거 적용일을 받지 않으므로 내일 시작으로 만든 뒤 시작일을 일주일 전으로 옮긴다.
        LocalDate back=today.minusDays(7);
        db.update("UPDATE tna.work_time_code_version SET effective_from=? WHERE tenant_id=?",back,tenant);
        db.update("UPDATE tna.schedule_version SET valid_from=? WHERE tenant_id=?",back,tenant);
        db.update("UPDATE tna.schedule_assignment SET valid_from=? WHERE tenant_id=?",back,tenant);
        CalendarSeed.period(db,admin,back,today.plusDays(40),"검토","");
    }

    private void configureChain(String... codes) {
        UUID office = UUID.randomUUID(), routeId = UUID.randomUUID(), versionId = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,approval_unit,created_by,updated_by) VALUES (?,?,?,'운영실','OFFICE',?,?)",tenant,office,"O-"+office,admin.accountId(),admin.accountId());
        db.update("UPDATE tna.organization SET parent_id=?,approval_unit='TEAM' WHERE tenant_id=? AND id=?",office,tenant,organization);
        db.update("INSERT INTO tna.organization_head_assignment(tenant_id,id,organization_id,employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'조직장 검증',?,?)",tenant,UUID.randomUUID(),organization,head.employeeId(),today,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.organization_head_assignment(tenant_id,id,organization_id,employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'실장 검증',?,?)",tenant,UUID.randomUUID(),office,direct.employeeId(),today,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.approval_route(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'T2','실장까지',?,?)",tenant,routeId,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.approval_route_version(tenant_id,id,route_id,revision,name,approver_kind,terminal_unit,reason,created_by) VALUES (?,?,?,1,'실장까지','ORGANIZATION_CHAIN','OFFICE','순차 승인',?)",tenant,versionId,routeId,admin.accountId());
        for(String code:codes)db.update("INSERT INTO tna.request_form_version(tenant_id,id,form_id,revision,effective_from,enabled,name,route_version_id,reason,created_by) SELECT tenant_id,?,id,2,?,true,'연장',?,'순차 승인',? FROM tna.request_form WHERE tenant_id=? AND code=?",UUID.randomUUID(),today,versionId,admin.accountId(),tenant,code);
    }

    private RequestPostProcessor as(TnaPrincipal actor){return authentication(new UsernamePasswordAuthenticationToken(actor,null,actor.roles().stream().map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList()));}
    private TnaPrincipal member(String name,Set<String> roles) {UUID employee=UUID.randomUUID(),account=UUID.randomUUID();db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",tenant,employee,"E-"+employee,name,today.minusYears(1));db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused-hash')",tenant,account,employee,account+"@example.test");for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);return new TnaPrincipal(tenant,account,employee,1,roles,Instant.now());}
}
