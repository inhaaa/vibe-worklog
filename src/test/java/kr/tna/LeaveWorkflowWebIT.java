package kr.tna;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import kr.tna.common.auth.*;
import kr.tna.org.*;
import kr.tna.request.*;
import kr.tna.request.RequestData.*;
import kr.tna.schedule.*;
import kr.tna.schedule.ScheduleData.*;
import kr.tna.clock.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest @AutoConfigureMockMvc
class LeaveWorkflowWebIT {
    static final String PASSWORD="Synthetic workflow web 43!";
    @Autowired MockMvc mvc;@Autowired LeaveWorkflowService flow;@Autowired ScheduleService schedules;@Autowired ApproverService approvers;
    JdbcTemplate db;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){var d=DatabaseFixture.start();r.add("spring.datasource.url",d::jdbcUrl);r.add("spring.datasource.username",()->DatabaseFixture.APP_USER);r.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);r.add("tna.auth.url",d::jdbcUrl);r.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);r.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);}
    @BeforeEach void seed(){var d=DatabaseFixture.start();d.seedIdentity(PasswordPolicy.passwordEncoder().encode(PASSWORD));db=new JdbcTemplate(d.adminDataSource());db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')",DatabaseFixture.TENANT_A,DatabaseFixture.ACCOUNT_A);}
    @Test void employeeCanOpenOwnRequestAndCorrectionForms() throws Exception {var s=login(DatabaseFixture.COMPANY_B,DatabaseFixture.EMAIL);mvc.perform(get("/me/requests").session(s)).andExpect(status().isOk()).andExpect(view().name("request/mine"));mvc.perform(get("/me/requests/correction").session(s)).andExpect(status().isOk()).andExpect(ReactPageAssertions.page());}
    @Test void employeeCannotManageBalancesOrOpenApprovalInbox() throws Exception {var s=login(DatabaseFixture.COMPANY_B,DatabaseFixture.EMAIL);for(String p:List.of("/admin/leave-balances","/approvals"))mvc.perform(get(p).session(s)).andExpect(status().isForbidden());}
    @Test void workflowWritesRequireCsrfAndForeignIdsStayHidden() throws Exception {var s=login(DatabaseFixture.COMPANY_B,DatabaseFixture.EMAIL);mvc.perform(post("/me/requests/leave").session(s)).andExpect(status().isForbidden());mvc.perform(get("/requests/{id}",UUID.randomUUID()).session(s)).andExpect(status().isNotFound());}
    @Test void administratorGetsGroundedOpeningAndExceptionalCorrectionForms() throws Exception {var s=login(DatabaseFixture.COMPANY_A,DatabaseFixture.EMAIL);mvc.perform(get("/admin/leave-balances").session(s)).andExpect(status().isOk()).andExpect(ReactPageAssertions.page());mvc.perform(get("/admin/requests/exceptions/new").session(s).param("employeeId",DatabaseFixture.EMPLOYEE_A.toString())).andExpect(status().isOk()).andExpect(ReactPageAssertions.page());}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2,FULL,1", "7.5,AM,7"})
    void leaveApprovalAndCancellationWorkThroughServerRenderedForms(String opening, String part, double afterApproval) throws Exception {
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul")),date=today.plusDays(2);UUID tenant=UUID.randomUUID();String company="WEB-"+tenant.toString().toUpperCase(Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 흐름')",tenant,company);
        var manager=member(tenant,Set.of("EMPLOYEE","ATTENDANCE_ADMIN","APPROVER"));var worker=member(tenant,Set.of("EMPLOYEE"));
        approvers.set(manager,new AccountData.ApproverForm(worker.employeeId(),manager.employeeId(),today,0L,"합성 배정",UUID.randomUUID()));
        var days=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(i->new DayForm(i,true,"09:00","18:00","12:00-13:00")).toList();
        UUID template=schedules.create(manager,new TemplateForm(UUID.randomUUID().toString(),"합성 일정",today,days,null,"합성 일정",UUID.randomUUID()));
        schedules.assign(manager,worker.employeeId(),new AssignmentForm(schedules.versions(manager,template).getFirst().id(),today,1L,"합성 배정",UUID.randomUUID()));
        CalendarSeed.period(db, manager, today,today.plusDays(30), "합성 근거", "");
        var workerSession=login(company,worker.accountId()+"@example.test");var managerSession=login(company,manager.accountId()+"@example.test");
        mvc.perform(post("/admin/leave-balances/entries").session(managerSession).with(csrf()).param("employeeId",worker.employeeId().toString())
                .param("baseDate",today.toString()).param("usableFrom",today.toString()).param("quantityDays",opening)
                .param("sourceReference","합성 기초 근거").param("reason","합성 기초").param("commandId",UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther());
        var initial=flow.buckets(worker,worker.employeeId()).getFirst();
        assertEquals(Double.parseDouble(opening),initial.balanceDays());
        var submitted=mvc.perform(post("/me/requests/leave").session(workerSession).with(csrf()).param("bucketId",initial.id().toString())
                .param("from",date.toString()).param("to",date.toString()).param("leavePart",part).param("reason","합성 연차")
                .param("commandId",UUID.randomUUID().toString())).andExpect(status().isSeeOther()).andReturn();
        String url=submitted.getResponse().getRedirectedUrl();assertNotNull(url);
        mvc.perform(get(url).session(managerSession)).andExpect(status().isOk()).andExpect(content().string(containsString("합성 연차")));
        mvc.perform(post(url+"/approve").session(managerSession).with(csrf()).param("expectedVersion","0").param("reason","독립 승인").param("commandId",UUID.randomUUID().toString())).andExpect(status().isSeeOther());
        assertEquals(afterApproval,flow.buckets(worker,worker.employeeId()).getFirst().balanceDays());
        var canceled=mvc.perform(post(url+"/cancellations").session(workerSession).with(csrf()).param("expectedVersion","1").param("reason","합성 취소").param("commandId",UUID.randomUUID().toString())).andExpect(status().isSeeOther()).andReturn();
        String cancelUrl=canceled.getResponse().getRedirectedUrl();assertNotNull(cancelUrl);
        mvc.perform(post(cancelUrl+"/approve").session(managerSession).with(csrf()).param("expectedVersion","0").param("reason","취소 독립 승인").param("commandId",UUID.randomUUID().toString())).andExpect(status().isSeeOther());
        assertEquals(initial.balanceDays(),flow.buckets(worker,worker.employeeId()).getFirst().balanceDays());
    }
    @Test void generalCancellationButtonIsLimitedToTheOwningAuthorWhileAdminExceptionRemainsAvailable() throws Exception {
        var f=approvedLeave();String path="/requests/"+f.requestId();
        mvc.perform(get(path).session(login(f.company(),f.worker().accountId()+"@example.test")))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/canCancel", true));
        mvc.perform(get(path).session(login(f.company(),f.manager().accountId()+"@example.test")))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/canCancel", false))
                .andExpect(ReactPageAssertions.data("/canCancelException", true));
    }
    @Test void alreadyStartedLeaveHidesGeneralCancellationButKeepsTheAdminException() throws Exception {
        var f=approvedLeave();String path="/requests/"+f.requestId();LocalDate past=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        db.update("UPDATE tna.approval_request SET from_date=?,to_date=? WHERE tenant_id=? AND id=?",past,past,f.manager().tenantId(),f.requestId());
        db.update("UPDATE tna.leave_request_day SET leave_date=? WHERE tenant_id=? AND request_id=?",past,f.manager().tenantId(),f.requestId());
        mvc.perform(get(path).session(login(f.company(),f.worker().accountId()+"@example.test")))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/canCancel", false))
                .andExpect(ReactPageAssertions.data("/ownRequest", true));
        mvc.perform(get(path).session(login(f.company(),f.manager().accountId()+"@example.test")))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/canCancelException", true))
                .andExpect(ReactPageAssertions.data("/ownRequest", false));
    }
    @Test void zeroDayRangePrefixDoesNotHideCancellationBeforeTheFirstChargedDay() throws Exception {
        var f=approvedLeave();String path="/requests/"+f.requestId();
        LocalDate yesterday=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        db.update("UPDATE tna.approval_request SET from_date=? WHERE tenant_id=? AND id=?",yesterday,f.manager().tenantId(),f.requestId());
        mvc.perform(get(path).session(login(f.company(),f.worker().accountId()+"@example.test")))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/canCancel", true));
    }
    private ApprovedLeave approvedLeave(){
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul")),date=today.plusDays(2);UUID tenant=UUID.randomUUID();String company="BUTTON-"+tenant.toString().toUpperCase(Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 취소 버튼')",tenant,company);
        var manager=member(tenant,Set.of("EMPLOYEE","ATTENDANCE_ADMIN","APPROVER"));var worker=member(tenant,Set.of("EMPLOYEE"));
        approvers.set(manager,new AccountData.ApproverForm(worker.employeeId(),manager.employeeId(),today,0L,"합성 배정",UUID.randomUUID()));
        var days=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(i->new DayForm(i,true,"09:00","18:00","12:00-13:00")).toList();
        UUID template=schedules.create(manager,new TemplateForm(UUID.randomUUID().toString(),"합성 일정",today,days,null,"합성 일정",UUID.randomUUID()));
        schedules.assign(manager,worker.employeeId(),new AssignmentForm(schedules.versions(manager,template).getFirst().id(),today,1L,"합성 배정",UUID.randomUUID()));
        CalendarSeed.period(db, manager, today,today.plusDays(30), "합성 근거", "");
        UUID bucket=flow.openBucket(manager,new BucketForm(worker.employeeId(),today,today,today.plusDays(30),2,"합성 기초 근거","합성 기초",UUID.randomUUID()));
        UUID request=flow.submitLeave(worker,new LeaveForm(bucket,date,date,null,"합성 연차",UUID.randomUUID()));
        flow.decide(manager,request,new DecisionForm("APPROVE",0L,"합성 독립 승인",UUID.randomUUID()));
        return new ApprovedLeave(company,manager,worker,request);
    }
    private record ApprovedLeave(String company,TnaPrincipal manager,TnaPrincipal worker,UUID requestId) { }
    private TnaPrincipal member(UUID tenant,Set<String> roles){UUID e=UUID.randomUUID(),a=UUID.randomUUID();db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 웹 사원',DATE '2020-01-01')",tenant,e,e.toString());db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",tenant,a,e,a+"@example.test",PasswordPolicy.passwordEncoder().encode(PASSWORD));for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,a,role);return new TnaPrincipal(tenant,a,e,1,roles,Instant.now());}
    private MockHttpSession login(String company,String email) throws Exception{return(MockHttpSession)mvc.perform(post("/login").with(csrf()).param("companyCode",company).param("email",email).param("password",PASSWORD)).andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);}
}
