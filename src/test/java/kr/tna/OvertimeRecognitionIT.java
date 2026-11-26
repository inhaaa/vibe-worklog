package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** 연장 최종 승인·취소 승인이 같은 요청 안에서 그날의 인정 연장(CALC-3)을 다시 계산하는지 확인한다. */
@SpringBootTest @AutoConfigureMockMvc
class OvertimeRecognitionIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    @Autowired LeaveWorkflowService flow;
    @Autowired MockMvc mvc;
    @Autowired kr.tna.overtime.OvertimePolicyService overtimePolicy;
    @Autowired kr.tna.schedule.WorkTimeCodeService workCodes;
    @Autowired kr.tna.schedule.WorkGroupService workGroups;
    @Autowired kr.tna.schedule.WorkGroupAssignmentService groupAssignments;
    @Autowired kr.tna.common.tenant.TenantTransactions tenantTransactions;
    @Autowired kr.tna.worktime.WorktimeService worktime;
    JdbcTemplate db;
    UUID tenant, organization;
    LocalDate today, workDate;
    TnaPrincipal admin, worker, direct, head;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();
        p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());today=LocalDate.now(SEOUL);workDate=today.minusDays(1);
        tenant=UUID.randomUUID();db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 인정 연장')",tenant,"OTR-"+tenant);
        admin=member("관리자",Set.of("EMPLOYEE","ATTENDANCE_ADMIN","APPROVER"));worker=member("신청자",Set.of("EMPLOYEE"));
        direct=member("직접결재",Set.of("EMPLOYEE","APPROVER"));head=member("조직장",Set.of("EMPLOYEE","APPROVER"));
        organization=UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 조직',?,?)",tenant,organization,"ORG-"+organization,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 소속',?,?)",tenant,UUID.randomUUID(),worker.employeeId(),organization,today.minusMonths(1),admin.accountId(),admin.accountId());
    }

    @Test void approvedOvertimeIsRecognizedAndRemovedByTheFinalDecisions() throws Exception {
        configureChain("O01","O02");
        var start=LocalTime.of(18,30); var end=LocalTime.of(21,0);
        prepareSchedule();
        // 사전신청은 시작 전에만 되므로 미래 날짜로 신청한 뒤 근무일·기준 토큰·기간을 어제로 옮긴다(결재 직후 재계산은 오늘까지만 본다).
        LocalDate future=today.plusDays(3);
        var futureBasis=tenantTransactions.execute(tenant,()->overtimePolicy.evaluate(tenant,worker.employeeId(),future,start,end,true));
        var route=flow.formPreview(worker,null,"OVERTIME").routeToken();
        UUID request=flow.submitOvertime(worker,future,start,end,"연장",futureBasis.basisToken(),route,UUID.randomUUID());
        var basis=tenantTransactions.execute(tenant,()->overtimePolicy.evaluate(tenant,worker.employeeId(),workDate,start,end,false));
        db.update("UPDATE tna.overtime_request_detail SET work_date=?,basis_token=? WHERE tenant_id=? AND request_id=?",workDate,basis.basisToken(),tenant,request);
        db.update("UPDATE tna.approval_request SET from_date=?,to_date=? WHERE tenant_id=? AND id=?",workDate,workDate,tenant,request);

        UUID network=UUID.randomUUID();
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 로컬',?,'합성 근거',?)",
                tenant,network,java.sql.Timestamp.from(today.minusDays(30).atStartOfDay(SEOUL).toInstant()),admin.accountId());
        record(network,"IN",workDate.atTime(9,0));record(network,"OUT",workDate.atTime(21,30));
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,2,0)",tenant,worker.employeeId(),workDate);
        worktime.recalculate(admin,worker.employeeId(),workDate,workDate,UUID.randomUUID(),"합성 승인 전");
        assertEquals(0L,latest().get("approved_overtime_millis"));
        assertEquals("CALC-3",latest().get("rule_version"));

        flow.decide(head,request,new DecisionForm("APPROVE",0L,"중간",UUID.randomUUID(),1));
        approveOverHttp(direct,request,1L,2);
        // 18:30~21:00 150분에서 연장 휴게 20:00~20:15 15분을 뺀다.
        assertEquals(135L*60_000,latest().get("approved_overtime_millis"));

        var cancelRoute=flow.formPreview(worker,null,"CANCEL_OVERTIME").routeToken();
        // 취소 신청도 시작 전에만 되므로 원신청을 잠시 미래 날짜로 돌려 신청한 뒤 어제로 되돌린다.
        moveRequest(request,request,future);
        UUID cancel=flow.requestCancellation(worker,request,new CancelForm(false,2L,"연장 취소",UUID.randomUUID()),cancelRoute);
        moveRequest(request,cancel,workDate);
        flow.decide(head,cancel,new DecisionForm("APPROVE",0L,"중간 취소",UUID.randomUUID(),1));
        assertEquals(135L*60_000,latest().get("approved_overtime_millis"));
        approveOverHttp(direct,cancel,1L,2);
        assertEquals(0L,latest().get("approved_overtime_millis"));
        assertEquals("CALC-3",latest().get("rule_version"));
    }

    private void moveRequest(UUID original,UUID target,LocalDate date) {
        db.update("UPDATE tna.overtime_request_detail SET work_date=? WHERE tenant_id=? AND request_id=?",date,tenant,original);
        db.update("UPDATE tna.approval_request SET from_date=?,to_date=? WHERE tenant_id=? AND id=?",date,date,tenant,target);
    }

    private void approveOverHttp(TnaPrincipal actor,UUID request,long version,int step) throws Exception {
        mvc.perform(post("/requests/{id}/approve",request).with(as(actor)).with(csrf()).param("expectedVersion",Long.toString(version))
                .param("reason","최종").param("commandId",UUID.randomUUID().toString()).param("expectedStep",Integer.toString(step)))
                .andExpect(status().is3xxRedirection());
    }

    private Map<String,Object> latest() {
        return db.queryForMap("""
            SELECT approved_overtime_millis, rule_version FROM tna.day_result
            WHERE tenant_id=? AND employee_id=? AND work_date=? ORDER BY result_revision DESC LIMIT 1""",tenant,worker.employeeId(),workDate);
    }

    private void record(UUID network,String kind,LocalDateTime at) {
        var instant=java.sql.Timestamp.from(at.atZone(SEOUL).toInstant());
        db.update("""
            INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,
                source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)
            """,tenant,UUID.randomUUID(),worker.employeeId(),kind,instant,instant,UUID.randomUUID(),network,workDate);
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
        CalendarSeed.period(db,admin,back,today.plusDays(10),"검토","");
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
