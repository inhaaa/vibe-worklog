package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.AttendanceSettingsData.CalendarForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.*;
import kr.tna.schedule.ScheduleData.*;
import kr.tna.schedule.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc
class AttendanceTypeWorkflowIT {
    @Autowired LeaveWorkflowService flow;
    @Autowired ScheduleService schedules;
    @Autowired AttendanceSettingsService settings;
    @Autowired ApproverService approvers;
    @Autowired ClockService clocks;
    @Autowired MockMvc mvc;
    JdbcTemplate db;
    UUID tenant, bucket;
    TnaPrincipal admin, worker, approver;
    LocalDate today, date;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();
        p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void seed() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());today=LocalDate.now(ZoneId.of("Asia/Seoul"));date=today.plusDays(2);
        tenant=UUID.randomUUID();db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 근태코드')",tenant,"TYPE-"+tenant);
        admin=member(Set.of("EMPLOYEE","ATTENDANCE_ADMIN"));worker=member(Set.of("EMPLOYEE"));approver=member(Set.of("EMPLOYEE","APPROVER"));
        approvers.set(admin,new ApproverForm(worker.employeeId(),approver.employeeId(),today,0L,"합성 지정",UUID.randomUUID()));
        var days=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(i->new DayForm(i,true,"09:00","18:00","12:00-13:00")).toList();
        UUID template=schedules.create(admin,new TemplateForm("T-"+UUID.randomUUID(),"합성 일정",today,days,null,"합성 일정",UUID.randomUUID()));
        UUID schedule=schedules.versions(admin,template).getFirst().id();
        long version=db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?",Long.class,tenant,worker.employeeId());
        schedules.assign(admin,worker.employeeId(),new AssignmentForm(schedule,today,version,"합성 배정",UUID.randomUUID()));
        settings.calendar(admin,new CalendarForm(today,today.plusDays(30),true,"합성 근거","","합성 달력",UUID.randomUUID()));
        bucket=flow.openBucket(admin,new BucketForm(worker.employeeId(),today,today,today.plusDays(30),20,"합성 기초","합성 등록",UUID.randomUUID()));
    }

    @Test void aNewTenantHasExactlyTheTwoSupportedBaselinePolicies() {
        assertEquals(2,db.queryForObject("SELECT count(*) FROM tna.attendance_type WHERE tenant_id=? AND system_kind IS NOT NULL",Integer.class,tenant));
        assertEquals(2,db.queryForObject("SELECT count(*) FROM tna.attendance_type_version WHERE tenant_id=? AND effective_from=DATE '0001-01-01' AND enabled",Integer.class,tenant));
    }
    @Test void leavePinsTheBaselineAndExposesOnlyThatImmutablePolicy() throws Exception {
        UUID request=leave(date,date),policy=baseline("ANNUAL_LEAVE");assertEquals(policy,pinned(request));
        var authentication=new UsernamePasswordAuthenticationToken(worker,null,worker.roles().stream().map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList());
        mvc.perform(get("/requests/"+request).with(SecurityMockMvcRequestPostProcessors.authentication(authentication)).accept("application/vnd.worklog.page+json"))
            .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/detail/attendancePolicy/id",policy.toString()))
            .andExpect(ReactPageAssertions.data("/detail/attendancePolicy/name","연차"))
            .andExpect(ReactPageAssertions.data("/detail/attendancePolicy/unit","DAY"));
    }
    @Test void sameDateRevisionWinsAndLaterEffectiveDateDoesNotApplyEarly() {
        UUID first=policy("ANNUAL_LEAVE",2,date,true,"연차 2");
        UUID second=policy("ANNUAL_LEAVE",3,date,true,"연차 3");
        UUID future=policy("ANNUAL_LEAVE",4,date.plusDays(2),true,"미래 연차");
        assertNotEquals(first,second);assertEquals(second,pinned(leave(date,date)));
        assertEquals(future,pinned(leave(date.plusDays(2),date.plusDays(2))));
        assertEquals(baseline("ANNUAL_LEAVE"),pinned(leave(today.plusDays(1),today.plusDays(1))));
    }
    @Test void aPolicyBoundaryInsideALeaveRangeRequiresSeparateRequestsAndRollsBack() {
        policy("ANNUAL_LEAVE",2,date.plusDays(1),true,"중간 정책");
        assertStatus(422,()->leave(date,date.plusDays(2)));
        assertEquals(0,count("approval_request"));assertEquals(0,count("leave_request_day"));
        assertNotNull(leave(date,date));assertNotNull(leave(date.plusDays(1),date.plusDays(2)));
    }
    @Test void aDisabledFuturePolicyBlocksOnlyDatesAtOrAfterItsEffectiveDate() {
        policy("ANNUAL_LEAVE",2,date.plusDays(1),false,"사용 종료");
        assertNotNull(leave(date,date));assertStatus(422,()->leave(date.plusDays(1),date.plusDays(1)));
        assertStatus(422,()->leave(date.plusDays(2),date.plusDays(2)));assertEquals(1,count("approval_request"));
    }
    @Test void disablingAfterSubmissionDoesNotBlockApprovalCancellationOrOriginalRestoration() {
        UUID original=leave(date,date),before=pinned(original);
        policy("ANNUAL_LEAVE",2,today,false,"신규 사용 중지");
        approve(original);assertEquals(19,balance());
        assertStatus(422,()->leave(date.plusDays(1),date.plusDays(1)));
        UUID cancel=flow.requestCancellation(worker,original,new CancelForm(false,1L,"합성 취소",UUID.randomUUID()));
        assertEquals(before,pinned(cancel));approve(cancel);assertEquals(20,balance());
        assertEquals("CANCELED",flow.get(worker,original).request().effectStatus());assertEquals(before,pinned(original));
    }
    @Test void aSuccessfulCommandCanReplayAfterTheTypeWasDisabled() {
        var form=new LeaveForm(bucket,date,date,null,"합성 멱등 신청",UUID.randomUUID());
        UUID request=flow.submitLeave(worker,form);policy("ANNUAL_LEAVE",2,today,false,"신규 중지");
        assertEquals(request,flow.submitLeave(worker,form));assertEquals(1,count("approval_request"));
    }
    @Test void correctionPinsItsOwnCapabilityAndDisablingDoesNotBlockPendingApproval() {
        UUID request=correction(today.minusDays(1));assertEquals(baseline("ATTENDANCE_CORRECTION"),pinned(request));
        policy("ATTENDANCE_CORRECTION",2,today.minusDays(2),false,"합성 정정 사용 중지");
        approve(request);assertEquals("APPROVED",flow.get(worker,request).request().status());
        assertStatus(422,()->correction(today.minusDays(2)));assertEquals(1,count("approval_request"));
    }
    @Test void anUnsupportedOrMismatchedPinnedCapabilityCannotApproveOrCreateEffects() {
        UUID request=leave(date,date);
        db.update("UPDATE tna.approval_request SET attendance_type_version_id=? WHERE tenant_id=? AND id=?",baseline("ATTENDANCE_CORRECTION"),tenant,request);
        assertStatus(422,()->approve(request));assertEquals("PENDING",flow.get(worker,request).request().status());
        assertEquals(0,count("request_effect"));assertEquals(20,balance());
        flow.decide(approver,request,new DecisionForm("REJECT",0L,"지원 불일치 반려",UUID.randomUUID()));
        assertEquals("REJECTED",flow.get(worker,request).request().status());
    }
    @Test void changingTheMasterStateCannotOverrideAnEnabledEffectivePolicy() {
        db.update("UPDATE tna.attendance_type SET active=false WHERE tenant_id=? AND system_kind='ANNUAL_LEAVE'",tenant);
        assertNotNull(leave(date,date));
    }
    @Test void aForeignPolicyCannotBeAttachedToASameTenantRequest() {
        UUID request=leave(date,date),foreign=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'다른 합성 회사')",foreign,"FOREIGN-"+foreign);
        UUID policy=db.queryForObject("SELECT v.id FROM tna.attendance_type_version v JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id WHERE t.tenant_id=? AND t.system_kind='ANNUAL_LEAVE'",UUID.class,foreign);
        var error=assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->db.update("UPDATE tna.approval_request SET attendance_type_version_id=? WHERE tenant_id=? AND id=?",policy,tenant,request));
        assertNotNull(error);assertEquals(baseline("ANNUAL_LEAVE"),pinned(request));
    }

    private UUID baseline(String kind) {return db.queryForObject("SELECT v.id FROM tna.attendance_type_version v JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id WHERE t.tenant_id=? AND t.system_kind=? AND v.revision=1",UUID.class,tenant,kind);}
    private UUID policy(String kind,long revision,LocalDate from,boolean enabled,String name) {
        UUID id=UUID.randomUUID();
        db.update("""
            INSERT INTO tna.attendance_type_version(tenant_id,id,type_id,revision,effective_from,enabled,name,unit,pay_kind,deduction_rule,recognition_rule,basis,reason,created_by)
            SELECT tenant_id,?,type_id,?,?,?, ?,unit,pay_kind,deduction_rule,recognition_rule,'합성 검토 근거','합성 변경',?
            FROM tna.attendance_type_version WHERE tenant_id=? AND id=?
            """,id,revision,from,enabled,name,admin.accountId(),tenant,baseline(kind));
        return id;
    }
    private UUID pinned(UUID request) {return db.queryForObject("SELECT attendance_type_version_id FROM tna.approval_request WHERE tenant_id=? AND id=?",UUID.class,tenant,request);}
    private UUID leave(LocalDate from,LocalDate to) {return flow.submitLeave(worker,new LeaveForm(bucket,from,to,null,"합성 신청",UUID.randomUUID()));}
    private UUID correction(LocalDate selected) {
        var snapshot=clocks.day(worker,selected);
        return flow.submitCorrection(worker,new CorrectionForm(null,selected,snapshot.inputRevision(),snapshot.sourceFingerprint(),"SET",selected+"T09:00","SET",selected+"T18:00",true,true,selected+"T12:00/"+selected+"T13:00",null,null,false,"합성 정정",UUID.randomUUID()));
    }
    private void approve(UUID request) {flow.decide(approver,request,new DecisionForm("APPROVE",flow.get(approver,request).request().version(),"합성 승인",UUID.randomUUID()));}
    private int count(String table) {return db.queryForObject("SELECT count(*) FROM tna."+table+" WHERE tenant_id=?",Integer.class,tenant);}
    private int balance() {return flow.buckets(worker,worker.employeeId()).getFirst().balanceDays();}
    private void assertStatus(int expected,Runnable action) {assertEquals(expected,assertThrows(OrgException.class,action::run).status());}
    private TnaPrincipal member(Set<String> roles) {
        UUID employee=UUID.randomUUID(),account=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 사원',?)",tenant,employee,"E-"+employee,today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused-password-hash')",tenant,account,employee,account+"@example.test");
        for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);
        return new TnaPrincipal(tenant,account,employee,1,roles,Instant.now());
    }
}
