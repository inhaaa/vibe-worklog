package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.clock.AttendanceSettingsData.CalendarForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.LeaveWorkflowMapper;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.OrgCommands;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.ApproverMapper;
import kr.tna.request.RequestData.*;
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
class LeaveWorkflowIT {
    @Autowired LeaveWorkflowService workflow;
    @Autowired ScheduleService schedules;
    @Autowired AttendanceSettingsService settings;
    @Autowired ApproverService approvers;
    @Autowired ClockService clocks;
    @Autowired LeaveWorkflowMapper workflowMapper;
    @Autowired BusinessCommands businessCommands;
    @Autowired OrgCommands administrativeCommands;
    @Autowired EmployeeAdminMapper workflowEmployees;
    @Autowired ApproverMapper workflowApprovers;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal manager,employee,approver,replacement;
    private LocalDate today,date,correctionDate;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();
        p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("spring.datasource.hikari.maximum-pool-size",()->5);
        p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void identitiesAndReviewedInputs() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());today=LocalDate.now(ZoneId.of("Asia/Seoul"));date=today.plusDays(2);correctionDate=today.minusDays(1);
        tenant=UUID.randomUUID();db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 휴가 고객사')",tenant,"WF-"+tenant);
        manager=member(Set.of("EMPLOYEE","ATTENDANCE_ADMIN"));employee=member(Set.of("EMPLOYEE"));
        approver=member(Set.of("EMPLOYEE","APPROVER"));replacement=member(Set.of("EMPLOYEE","APPROVER"));
        approvers.set(manager,new ApproverForm(employee.employeeId(),approver.employeeId(),today,0L,"합성 승인자",UUID.randomUUID()));
        var days=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(i->new DayForm(i,true,"09:00","18:00","12:00-13:00")).toList();
        UUID template=schedules.create(manager,new TemplateForm("WF-"+UUID.randomUUID(),"가상 날짜 테스트",today,days,null,"합성 일정",UUID.randomUUID()));
        UUID version=schedules.versions(manager,template).getFirst().id();
        schedules.assign(manager,employee.employeeId(),new AssignmentForm(version,today,employeeVersion(),"합성 배정",UUID.randomUUID()));
        settings.calendar(manager,new CalendarForm(today,today.plusDays(30),true,"합성 검토 자료","","합성 달력",UUID.randomUUID()));
    }

    @Test void openingIsAnImmutableGroundedLedgerAndOwnOpeningIsBlocked() {
        UUID bucket=bucket(3);var value=workflow.buckets(employee,employee.employeeId()).getFirst();
        assertEquals(3,value.balanceDays());assertEquals(3,value.availableDays());
        assertEquals("OPENING",workflow.ledger(employee,bucket).getFirst().entryKind());
        assertStatus(403,()->workflow.openBucket(manager,new BucketForm(manager.employeeId(),today,today,today.plusDays(30),1,"합성 근거","본인 기초",UUID.randomUUID())));
        assertStatus(422,()->workflow.openBucket(manager,new BucketForm(employee.employeeId(),today,today,today.plusDays(30),-1,"합성 근거","음수",UUID.randomUUID())));
        assertStatus(422,()->workflow.openBucket(manager,new BucketForm(employee.employeeId(),today,today,today.plusDays(30),1," ","근거 없음",UUID.randomUUID())));
    }
    @Test void foreignOrdinaryCancellationCannotProbeVersionOrStatus() {
        UUID request=leave(bucket(1),date);
        assertStatus(404,()->workflow.requestCancellation(manager,request,new CancelForm(false,999L,"타인 상태 추론 차단",UUID.randomUUID())));
    }
    @Test void adjustmentsAppendRatherThanOverwriteAndCannotMakeNegativeBalance() {
        UUID bucket=bucket(2);workflow.adjust(manager,bucket,new AdjustmentForm(-1,today,0L,"합성 조정 근거","차이 정정",UUID.randomUUID()));
        assertEquals(1,balance(bucket));assertEquals(2,workflow.ledger(manager,bucket).size());
        assertStatus(422,()->workflow.adjust(manager,bucket,new AdjustmentForm(-2,today,1L,"근거","과도한 감액",UUID.randomUUID())));
        assertEquals(1,balance(bucket));
    }
    @Test void pendingLeaveDoesNotReserveAndApprovalConsumesExactlyOnce() {
        UUID bucket=bucket(1),first=leave(bucket,date),second=leave(bucket,date.plusDays(1));
        assertEquals(1,balance(bucket));assertEquals(2,workflow.buckets(employee,employee.employeeId()).getFirst().pendingDays());
        var form=new DecisionForm("APPROVE",0L,"합성 승인",UUID.randomUUID());
        assertEquals(first,workflow.decide(approver,first,form));assertEquals(first,workflow.decide(approver,first,form));
        assertEquals(0,balance(bucket));assertEquals(1,count("tna.leave_ledger","entry_kind='USE'"));
        assertStatus(422,()->workflow.decide(approver,second,new DecisionForm("APPROVE",0L,"잔여 검증",UUID.randomUUID())));
        assertEquals("PENDING",workflow.get(employee,second).request().status());
    }
    @Test void duplicatePendingDayIsBlockedAndWithdrawalAllowsANewRequest() {
        UUID bucket=bucket(3),request=leave(bucket,date);
        assertStatus(409,()->leave(bucket,date));
        workflow.withdraw(employee,request,new DecisionForm("WITHDRAW",0L,"철회 후 재신청",UUID.randomUUID()));
        assertEquals("WITHDRAWN",workflow.get(employee,request).request().status());assertNotNull(leave(bucket,date));assertEquals(3,balance(bucket));
    }
    @Test void onlyAssignedApproverCanDecideAndManagerRoleDoesNotApprove() {
        UUID request=leave(bucket(3),date);
        assertStatus(403,()->workflow.decide(manager,request,new DecisionForm("APPROVE",0L,"담당자 우회",UUID.randomUUID())));
        assertStatus(403,()->workflow.decide(replacement,request,new DecisionForm("APPROVE",0L,"미배정 승인자",UUID.randomUUID())));
        assertEquals("PENDING",workflow.get(employee,request).request().status());
    }
    @Test void reassignInvalidatesTheOldApproverAndVersion() {
        UUID request=leave(bucket(3),date);
        workflow.reassign(manager,request,new ReassignForm(replacement.employeeId(),0L,"명시적 재할당",UUID.randomUUID()));
        assertStatus(403,()->workflow.decide(approver,request,new DecisionForm("APPROVE",0L,"이전 화면",UUID.randomUUID())));
        assertStatus(409,()->workflow.decide(replacement,request,new DecisionForm("APPROVE",0L,"오래된 버전",UUID.randomUUID())));
        workflow.decide(replacement,request,new DecisionForm("APPROVE",1L,"새 할당 승인",UUID.randomUUID()));
        assertEquals("APPROVED",workflow.get(employee,request).request().status());
    }
    @Test void approverRevocationPreventsUseOfAnOldPrincipal() {
        UUID request=leave(bucket(3),date);
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='APPROVER'",tenant,approver.accountId());
        assertStatus(403,()->workflow.decide(approver,request,new DecisionForm("APPROVE",0L,"이전 권한",UUID.randomUUID())));
        assertEquals("PENDING",workflow.get(employee,request).request().status());
    }
    @Test void cancellationPendingAndRejectionKeepTheOriginalEffect() {
        UUID bucket=bucket(2),original=leave(bucket,date);approve(original);
        UUID cancel=workflow.requestCancellation(employee,original,new CancelForm(false,1L,"취소 요청",UUID.randomUUID()));
        assertNotNull(cancel);assertEquals(1,balance(bucket));assertEquals("EFFECTIVE",workflow.get(employee,original).request().effectStatus());
        workflow.decide(approver,cancel,new DecisionForm("REJECT",0L,"취소 반려",UUID.randomUUID()));
        assertEquals(1,balance(bucket));assertEquals("EFFECTIVE",workflow.get(employee,original).request().effectStatus());
    }
    @Test void cancellationRestoresOriginalLedgerOnceAndLeavesApprovalHistory() {
        UUID bucket=bucket(2),original=leave(bucket,date);approve(original);
        UUID cancel=workflow.requestCancellation(employee,original,new CancelForm(false,1L,"합성 취소",UUID.randomUUID()));
        var form=new DecisionForm("APPROVE",0L,"독립 취소 승인",UUID.randomUUID());
        workflow.decide(approver,cancel,form);workflow.decide(approver,cancel,form);
        assertEquals(2,balance(bucket));assertEquals("APPROVED",workflow.get(employee,original).request().status());
        assertEquals("CANCELED",workflow.get(employee,original).request().effectStatus());assertEquals(1,count("tna.leave_ledger","entry_kind='RESTORE'"));
        assertNotNull(leave(bucket,date));
    }
    @Autowired kr.tna.approval.RequestFormMapper requestForms;
    @Test void cancellationApprovalRechecksThatTheLeaveHasNotAlreadyStarted() {
        UUID bucket=bucket(2),original=leave(bucket,date);approve(original);
        UUID cancellation=workflow.requestCancellation(employee,original,new CancelForm(false,1L,"시작 전 일반 취소 요청",UUID.randomUUID()));
        assertEquals(1,balance(bucket));
        Instant afterStart=date.atTime(10,0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        var after=new LeaveWorkflowService(workflowMapper,businessCommands,administrativeCommands,
                workflowEmployees,workflowApprovers,clocks,Clock.fixed(afterStart,ZoneId.of("Asia/Seoul")),requestForms);
        assertStatus(422,()->after.decide(approver,cancellation,new DecisionForm("APPROVE",0L,"사용 시작 후 일반 취소 승인",UUID.randomUUID())));
        assertEquals("PENDING",workflow.get(employee,cancellation).request().status());
        assertEquals("APPROVED",workflow.get(employee,original).request().status());
        assertEquals("EFFECTIVE",workflow.get(employee,original).request().effectStatus());
        assertEquals(1,balance(bucket));assertEquals(0,count("tna.leave_ledger","entry_kind='RESTORE'"));
    }
    @Test void aBucketBoundaryIsNeverSplitOrSilentlyAllocated() {
        UUID bucket=workflow.openBucket(manager,new BucketForm(employee.employeeId(),today,today,date,3,"합성 기간 근거","기간 기초",UUID.randomUUID()));
        assertNotNull(bucket);
        assertStatus(422,()->workflow.submitLeave(employee,new LeaveForm(bucket,date,date.plusDays(1),null,"기간 넘김",UUID.randomUUID())));
        assertEquals(3,balance(bucket));
    }
    @Test void changedDayQuantityBlocksApprovalAndRetainsPending() {
        UUID bucket=bucket(3),request=leave(bucket,date);
        db.update("UPDATE tna.calendar_day SET kind='HOLIDAY' WHERE tenant_id=? AND work_date=?",tenant,date);
        assertStatus(422,()->workflow.decide(approver,request,new DecisionForm("APPROVE",0L,"달력 변경 뒤 검증",UUID.randomUUID())));
        assertEquals(3,balance(bucket));assertEquals("PENDING",workflow.get(employee,request).request().status());
    }
    @Test void sourceAndTargetSelfApprovalAreBothBlocked() {
        UUID bucket=bucket(3),request=leave(bucket,date);
        assertStatus(422,()->workflow.reassign(manager,request,new ReassignForm(employee.employeeId(),0L,"자기 지정",UUID.randomUUID())));
        var snapshot=clocks.day(employee,correctionDate);
        UUID correction=workflow.submitCorrection(manager,new CorrectionForm(employee.employeeId(),correctionDate,snapshot.inputRevision(),snapshot.sourceFingerprint(),"SET",correctionDate+"T09:00","SET",correctionDate+"T18:00",true,true,correctionDate+"T12:00/"+correctionDate+"T13:00",null,null,true,"담당자 예외 작성",UUID.randomUUID()));
        assertNotNull(correction);
        assertStatus(422,()->workflow.reassign(manager,correction,new ReassignForm(manager.employeeId(),0L,"작성자 자기 지정",UUID.randomUUID())));
    }
    @Test void pendingCorrectionChangesReviewOnlyAndWithdrawalDoesNotChangeFacts() {
        var before=clocks.day(employee,correctionDate);UUID request=correction(null);
        var pending=clocks.day(employee,correctionDate);assertEquals(before.inputRevision(),pending.inputRevision());assertEquals(before.reviewRevision()+1,pending.reviewRevision());
        workflow.withdraw(employee,request,new DecisionForm("WITHDRAW",0L,"정정 철회",UUID.randomUUID()));
        var withdrawn=clocks.day(employee,correctionDate);assertEquals(before.inputRevision(),withdrawn.inputRevision());assertEquals(pending.reviewRevision()+1,withdrawn.reviewRevision());
    }
    @Test void approvedCorrectionKeepsPriorHistoryAndBindsNewEvidenceToTheNewFactRevision() {
        UUID first=correction(null);approve(first);
        var one=clocks.day(employee,correctionDate);assertEquals(1L,one.inputRevision());
        UUID second=correction(first);approve(second);
        assertEquals("APPROVED",workflow.get(employee,first).request().status());assertEquals("SUPERSEDED",workflow.get(employee,first).request().effectStatus());
        assertEquals("EFFECTIVE",workflow.get(employee,second).request().effectStatus());
        var two=clocks.day(employee,correctionDate);assertEquals(2L,two.inputRevision());
        assertStatus(422,()->workflow.requestCancellation(employee,second,new CancelForm(false,1L,"정정 취소 금지",UUID.randomUUID())));
    }
    @Test void correctionFormReadsTheCurrentApprovedCorrectionId() {
        assertNull(workflow.currentCorrectionRequestId(employee,employee.employeeId(),correctionDate));
        UUID first=correction(null);approve(first);
        assertEquals(first,workflow.currentCorrectionRequestId(employee,employee.employeeId(),correctionDate));
        assertEquals(first,workflow.currentCorrectionRequestId(manager,employee.employeeId(),correctionDate));
        assertStatus(403,()->workflow.currentCorrectionRequestId(approver,employee.employeeId(),correctionDate));
    }
    @Test void staleCorrectionBaseAndForeignRequestAreRejectedWithoutDisclosure() {
        var before=clocks.day(employee,correctionDate);
        assertStatus(409,()->workflow.submitCorrection(employee,new CorrectionForm(null,correctionDate,before.inputRevision()+1,before.sourceFingerprint(),"SET",correctionDate+"T09:00","SET",correctionDate+"T18:00",true,true,"",null,null,false,"오래된 사실",UUID.randomUUID())));
        UUID request=leave(bucket(1),date);UUID otherTenant=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'다른 합성 고객사')",otherTenant,"OTHER-"+otherTenant);
        var foreign=new TnaPrincipal(otherTenant,manager.accountId(),manager.employeeId(),1,manager.roles(),Instant.now());
        assertStatus(403,()->workflow.get(foreign,request));
        assertStatus(404,()->workflow.get(employee,UUID.randomUUID()));
    }
    @Test void simultaneousApprovalsCannotOverspendTheSameBucket() throws Exception {
        UUID bucket=bucket(1),first=leave(bucket,date),second=leave(bucket,date.plusDays(1));var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->approveAfter(start,first));var b=executor.submit(()->approveAfter(start,second));start.countDown();
            assertEquals(1,a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(0,balance(bucket));assertEquals(1,count("tna.leave_ledger","entry_kind='USE'"));
    }
    @Test void ledgerFailureRollsBackDecisionEffectAndAuditTogether() {
        UUID bucket=bucket(1),request=leave(bucket,date);String constraint="synthetic_ledger_failure_"+tenant.toString().replace("-","");
        db.execute("ALTER TABLE tna.leave_ledger ADD CONSTRAINT "+constraint+" CHECK (entry_kind<>'USE' OR tenant_id<>'"+tenant+"'::uuid)");
        try {assertStatus(409,()->workflow.decide(approver,request,new DecisionForm("APPROVE",0L,"실패 주입",UUID.randomUUID())));}
        finally {db.execute("ALTER TABLE tna.leave_ledger DROP CONSTRAINT "+constraint);}
        assertEquals("PENDING",workflow.get(employee,request).request().status());assertEquals(1,balance(bucket));
        assertEquals(0,count("tna.request_effect","request_id='"+request+"'"));
    }
    @Test void zeroDayPrefixDoesNotBlockCancellationBeforeTheFirstChargedDay() {
        UUID bucket=bucket(2),original=leave(bucket,date);approve(original);
        db.update("UPDATE tna.approval_request SET from_date=? WHERE tenant_id=? AND id=?",today.minusDays(1),tenant,original);
        db.update("""
            INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,schedule_version_id,calendar_version_id,zero_reason)
            SELECT tenant_id,?,request_id,?,0,schedule_version_id,calendar_version_id,'합성 비차감일'
            FROM tna.leave_request_day WHERE tenant_id=? AND request_id=? AND quantity_days=1
            """,UUID.randomUUID(),today.minusDays(1),tenant,original);
        UUID cancellation=assertDoesNotThrow(()->workflow.requestCancellation(employee,original,new CancelForm(false,1L,"첫 차감일 전 취소",UUID.randomUUID())));
        assertEquals(1,balance(bucket));
        assertDoesNotThrow(()->approve(cancellation));
        assertEquals(2,balance(bucket));
        assertEquals("CANCELED",workflow.get(employee,original).request().effectStatus());
    }
    @Test void alreadyStartedLeaveNeedsAnAdministrativeExceptionalCancellation() {
        UUID bucket=bucket(1),original=leave(bucket,date);approve(original);
        db.update("UPDATE tna.approval_request SET from_date=?,to_date=? WHERE tenant_id=? AND id=?",today.minusDays(1),today.minusDays(1),tenant,original);
        db.update("UPDATE tna.leave_request_day SET leave_date=? WHERE tenant_id=? AND request_id=?",today.minusDays(1),tenant,original);
        assertStatus(422,()->workflow.requestCancellation(employee,original,new CancelForm(false,1L,"사용 뒤 일반 취소",UUID.randomUUID())));
        UUID exceptional=workflow.requestCancellation(manager,original,new CancelForm(true,1L,"과거 사실 근거 검토",UUID.randomUUID()));
        assertNotNull(exceptional);approve(exceptional);assertEquals(1,balance(bucket));
    }

    private UUID bucket(int quantity) {UUID id=workflow.openBucket(manager,new BucketForm(employee.employeeId(),today,today,today.plusDays(30),quantity,"합성 기초 자료 참조","기초 등록",UUID.randomUUID()));assertNotNull(id);return id;}
    private UUID leave(UUID bucket,LocalDate selected) {UUID id=workflow.submitLeave(employee,new LeaveForm(bucket,selected,selected,null,"합성 종일 연차",UUID.randomUUID()));assertNotNull(id);return id;}
    private void approve(UUID request) {workflow.decide(approver,request,new DecisionForm("APPROVE",workflow.get(approver,request).request().version(),"합성 독립 승인",UUID.randomUUID()));}
    private UUID correction(UUID replaces) {
        var snapshot=clocks.day(employee,correctionDate);
        UUID id=workflow.submitCorrection(employee,new CorrectionForm(null,correctionDate,snapshot.inputRevision(),snapshot.sourceFingerprint(),"SET",correctionDate+"T09:00","SET",correctionDate+"T18:00",true,true,correctionDate+"T12:00/"+correctionDate+"T13:00",replaces,null,false,"합성 누락 정정",UUID.randomUUID()));assertNotNull(id);return id;
    }
    private int balance(UUID bucket) {return workflow.buckets(manager,employee.employeeId()).stream().filter(b->b.id().equals(bucket)).findFirst().orElseThrow().balanceDays();}
    private long employeeVersion() {return db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?",Long.class,tenant,employee.employeeId());}
    private int count(String table,String condition) {return db.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND "+condition,Integer.class,tenant);}
    private int approveAfter(CountDownLatch start,UUID request) throws Exception {start.await();try{workflow.decide(approver,request,new DecisionForm("APPROVE",0L,"동시 승인",UUID.randomUUID()));return 1;}catch(OrgException error){assertEquals(422,error.status());return 0;}}
    private TnaPrincipal member(Set<String> roles) {
        UUID employeeId=UUID.randomUUID(),account=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 휴가 사원',?)",tenant,employeeId,"E-"+employeeId,today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?, 'synthetic-unused-password-hash')",tenant,account,employeeId,account+"@example.test");
        for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);
        return new TnaPrincipal(tenant,account,employeeId,1,roles,Instant.now());
    }
    private void assertStatus(int status,Runnable action) {assertEquals(status,assertThrows(OrgException.class,action::run).status());}
}
