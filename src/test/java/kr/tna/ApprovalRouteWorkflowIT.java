package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.time.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import kr.tna.codes.CodeData.TypeRow;
import kr.tna.codes.CodeManagementService;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.org.BulkData;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.*;
import kr.tna.schedule.ScheduleService;
import kr.tna.schedule.ScheduleData.*;
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

@SpringBootTest @AutoConfigureMockMvc
class ApprovalRouteWorkflowIT {
    @Autowired LeaveWorkflowService flow;
    @Autowired ClockService clocks;
    @Autowired MockMvc mvc;
    @Autowired ScheduleService schedules;
    @Autowired CodeManagementService codes;
    JdbcTemplate db;
    UUID tenant, organization, membership;
    LocalDate today, yesterday;
    TnaPrincipal admin, worker, direct, head;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();
        p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void seed() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());today=LocalDate.now(ZoneId.of("Asia/Seoul"));yesterday=today.minusDays(1);
        tenant=UUID.randomUUID();db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 결재선')",tenant,"ROUTE-"+tenant);
        admin=member("관리자",Set.of("EMPLOYEE","ATTENDANCE_ADMIN","APPROVER"));worker=member("신청자",Set.of("EMPLOYEE"));
        direct=member("직접결재",Set.of("EMPLOYEE","APPROVER"));head=member("조직장",Set.of("EMPLOYEE","APPROVER"));
        organization=UUID.randomUUID();membership=UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 조직',?,?)",tenant,organization,"ORG-"+organization,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 소속',?,?)",tenant,membership,worker.employeeId(),organization,today.minusMonths(1),admin.accountId(),admin.accountId());
    }

    @Test void intermediateApprovalTransfersTheCorrectionWithoutApplyingIt() {
        configureChain("A01");
        var preview=flow.formPreview(worker,worker.employeeId(),"ATTENDANCE_CORRECTION");
        assertEquals(List.of(head.employeeId(),direct.employeeId()),preview.steps().stream().map(PreviewStep::approverId).toList());
        var day=clocks.day(worker,yesterday);
        var form=form(worker.employeeId(),day.inputRevision(),day.sourceFingerprint(),false,UUID.randomUUID());
        UUID request=flow.submitCorrection(worker,form,preview.routeToken());
        assertThrows(OrgException.class,()->flow.get(direct,request));
        flow.decide(head,request,new DecisionForm("APPROVE",0L,"팀장 검토",UUID.randomUUID(),1));
        var intermediate=flow.get(head,request).request();
        assertEquals("PENDING",intermediate.status());
        assertEquals(direct.employeeId(),intermediate.currentApproverId());
        assertEquals("NONE",intermediate.effectStatus());
        assertEquals(2,intermediate.currentStep());
        assertTrue(flow.approvalPage(head,true,0).requests().stream().anyMatch(r->r.id().equals(request)));
        flow.decide(direct,request,new DecisionForm("APPROVE",1L,"실장 최종",UUID.randomUUID(),2));
        assertEquals("EFFECTIVE",flow.get(worker,request).request().effectStatus());
        assertEquals(List.of("APPROVED","APPROVED"),flow.get(worker,request).steps().stream().map(DecisionStep::status).toList());
    }

    @Autowired kr.tna.request.LeaveWorkflowMapper flowMapper;
    @Autowired kr.tna.overtime.OvertimePolicyService overtimePolicy;
    @Autowired kr.tna.schedule.WorkTimeCodeService workCodes;
    @Autowired kr.tna.schedule.WorkGroupService workGroups;
    @Autowired kr.tna.schedule.WorkGroupAssignmentService groupAssignments;

    private UUID prepareOvertime(LocalDate date) {
        var start=LocalTime.of(18,30); var end=LocalTime.of(21,0);
        var form=new kr.tna.schedule.WorkTimeCodeData.Command(UUID.randomUUID(),null,null,"WT","주간",today,true,
                LocalTime.of(9,0),LocalTime.of(18,0),null,null,start,end,
                List.of(new kr.tna.schedule.WorkTimeCodeData.Break("AFTER",LocalTime.of(20,0),LocalTime.of(20,15))),
                "합성 연장",null);
        UUID code=workCodes.save(admin,form);
        UUID version=workCodes.catalog(admin,today).codes().stream().filter(c->c.id().equals(code))
                .findFirst().orElseThrow().versions().getFirst().id();
        var days=java.util.stream.IntStream.rangeClosed(1,7)
                .mapToObj(i->new kr.tna.schedule.WorkGroupData.WeekInput(i,version)).toList();
        var group=new kr.tna.schedule.WorkGroupData.GroupCommand(null,"G1","주간조",date,days,"초기",null,null,null,UUID.randomUUID());
        UUID id=workGroups.saveGroup(admin,group.confirm(workGroups.previewGroup(admin,group).token()));
        var member=new kr.tna.schedule.WorkGroupData.AssignmentCommand(id,date,List.of(worker.employeeId()),"배정",List.of(),null,UUID.randomUUID());
        groupAssignments.saveAssignments(admin,member.confirm(groupAssignments.previewAssignments(admin,member).token()));
        CalendarSeed.period(db, admin, today,date.plusDays(10), "검토", "");
        return id;
    }

    @Autowired kr.tna.common.tenant.TenantTransactions tenantTransactions;

    @Test void changedScheduleCannotBeFinallyApprovedAndWithdrawalReleasesTheSlot() {
        configureChain("O01");
        LocalDate date=today.plusDays(4);
        UUID group=prepareOvertime(date);
        var start=LocalTime.of(18,30); var end=LocalTime.of(21,0);
        var basis=tenantTransactions.execute(tenant,()->overtimePolicy.evaluate(tenant,worker.employeeId(),date,start,end,true));
        var route=flow.formPreview(worker,null,"OVERTIME").routeToken();
        UUID request=flow.submitOvertime(worker,date,start,end,"사전 연장",basis.basisToken(),route,UUID.randomUUID());
        flow.decide(head,request,new DecisionForm("APPROVE",0L,"중간",UUID.randomUUID(),1));
        var inputs=java.util.stream.IntStream.rangeClosed(1,7)
                .mapToObj(i->new kr.tna.schedule.WorkGroupData.WeekInput(i,basis.codeVersionId())).toList();
        var revised=new kr.tna.schedule.WorkGroupData.GroupCommand(group,"G1","주간조",date,inputs,
                "예약 정정",0L,basis.scheduleVersionId(),null,UUID.randomUUID());
        workGroups.saveGroup(admin,revised.confirm(workGroups.previewGroup(admin,revised).token()));
        assertThrows(OrgException.class,()->flow.decide(direct,request,new DecisionForm("APPROVE",1L,"변경 후",UUID.randomUUID(),2)));
        assertEquals("NONE",flow.get(worker,request).request().effectStatus());
        flow.withdraw(worker,request,new DecisionForm("WITHDRAW",1L,"재신청",UUID.randomUUID(),2));
        assertEquals("WITHDRAWN",flow.get(worker,request).request().status());
        var newBasis=tenantTransactions.execute(tenant,()->overtimePolicy.evaluate(tenant,worker.employeeId(),date,start,end,true));
        UUID resubmitted=flow.submitOvertime(worker,date,start,end,"재신청",newBasis.basisToken(),route,UUID.randomUUID());
        flow.decide(head,resubmitted,new DecisionForm("REJECT",0L,"반려",UUID.randomUUID(),1));
        assertEquals("REJECTED",flow.get(worker,resubmitted).request().status());
        assertEquals("NONE",flow.get(worker,resubmitted).request().effectStatus());
    }

    @Test void overtimeAndItsCancellationOnlyTakeEffectOnTheFinalDecision() throws Exception {
        configureChain("O01","O02");
        LocalDate date=today.plusDays(3);
        prepareOvertime(date);
        var start=LocalTime.of(18,30); var end=LocalTime.of(21,0);
        var basis=tenantTransactions.execute(tenant,()->overtimePolicy.evaluate(tenant,worker.employeeId(),date,start,end,true));
        var route=flow.formPreview(worker,null,"OVERTIME").routeToken();
        UUID command=UUID.randomUUID();
        UUID request=flow.submitOvertime(worker,date,start,end,"연장",basis.basisToken(),route,command);
        assertEquals(request,flow.submitOvertime(worker,date,start,end,"연장",basis.basisToken(),route,command));
        assertThrows(OrgException.class,()->flow.submitOvertime(worker,date,start,end,"중복",basis.basisToken(),route,UUID.randomUUID()));
        assertEquals(135,flow.get(worker,request).overtime().requestedMinutes());
        assertThrows(OrgException.class,()->flow.get(direct,request));
        assertThrows(OrgException.class,()->flow.decide(worker,request,new DecisionForm("APPROVE",0L,"자기 승인",UUID.randomUUID(),1)));
        assertNull(tenantTransactions.execute(UUID.randomUUID(),()->flowMapper.overtime(tenant,request)));

        flow.decide(head,request,new DecisionForm("APPROVE",0L,"중간",UUID.randomUUID(),1));
        assertEquals("NONE",flow.get(worker,request).request().effectStatus());
        assertEquals("PENDING",flow.get(worker,request).request().status());
        overtimeMinutesOnBothPages(request,date,0);
        mvc.perform(get("/me/overtime").param("date",date.toString()).with(as(direct)).accept(ReactPageView()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/overtime/rows",List.of()));
        mvc.perform(get("/admin/overtime").with(as(worker)).accept(ReactPageView())).andExpect(status().isForbidden());
        flow.decide(direct,request,new DecisionForm("APPROVE",1L,"최종",UUID.randomUUID(),2));
        overtimeMinutesOnBothPages(request,date,135);
        assertEquals("EFFECTIVE",flow.get(worker,request).request().effectStatus());
        var cancelRoute=flow.formPreview(worker,null,"CANCEL_OVERTIME").routeToken();
        UUID cancel=flow.requestCancellation(worker,request,new CancelForm(false,2L,"연장 취소",UUID.randomUUID()),cancelRoute);
        assertThrows(OrgException.class,()->flow.requestCancellation(worker,request,new CancelForm(false,2L,"중복 취소",UUID.randomUUID()),cancelRoute));
        flow.decide(head,cancel,new DecisionForm("APPROVE",0L,"중간 취소",UUID.randomUUID(),1));
        assertEquals("EFFECTIVE",flow.get(worker,request).request().effectStatus());
        flow.decide(direct,cancel,new DecisionForm("APPROVE",1L,"최종 취소",UUID.randomUUID(),2));
        assertEquals("CANCELED",flow.get(worker,request).request().effectStatus());
        overtimeMinutesOnBothPages(request,date,0);
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM tna.leave_ledger WHERE tenant_id=?",Long.class,tenant));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=?",Long.class,tenant));
    }

    private void overtimeMinutesOnBothPages(UUID request,LocalDate date,int expected) throws Exception {
        var employeePage=get("/me/overtime").param("date",date.toString()).with(as(worker));
        var adminPage=get("/admin/overtime").param("from",date.toString()).param("to",date.toString()).with(as(admin));
        for(var page:List.of(employeePage,adminPage)) {
            var result=mvc.perform(page.accept(ReactPageView())).andExpect(status().isOk()).andReturn();
            var rows=ReactPageAssertions.envelope(result).at("/data/overtime/rows");
            var matching=new ArrayList<Integer>();
            for(var row:rows) if(request.toString().equals(row.path("id").asString())) {
                assertEquals(135,row.path("requestedMinutes").asInt());
                matching.add(row.path("approvedMinutes").asInt());
            }
            assertEquals(List.of(expected),matching);
        }
    }

    private void configureChain(String... codes) {
        UUID office = UUID.randomUUID(), routeId = UUID.randomUUID(), versionId = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,approval_unit,created_by,updated_by) VALUES (?,?,?,'운영실','OFFICE',?,?)",tenant,office,"O-"+office,admin.accountId(),admin.accountId());
        db.update("UPDATE tna.organization SET parent_id=?,approval_unit='TEAM' WHERE tenant_id=? AND id=?",office,tenant,organization);
        head(head.employeeId());
        db.update("INSERT INTO tna.organization_head_assignment(tenant_id,id,organization_id,employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'실장 검증',?,?)",tenant,UUID.randomUUID(),office,direct.employeeId(),today,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.approval_route(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'T2','실장까지',?,?)",tenant,routeId,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.approval_route_version(tenant_id,id,route_id,revision,name,approver_kind,terminal_unit,reason,created_by) VALUES (?,?,?,1,'실장까지','ORGANIZATION_CHAIN','OFFICE','순차 승인',?)",tenant,versionId,routeId,admin.accountId());
        for(String code:codes)db.update("INSERT INTO tna.request_form_version(tenant_id,id,form_id,revision,effective_from,enabled,name,route_version_id,reason,created_by) SELECT tenant_id,?,id,2,?,true,'출퇴근 정정',?,'순차 승인',? FROM tna.request_form WHERE tenant_id=? AND code=?",UUID.randomUUID(),today,versionId,admin.accountId(),tenant,code);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"FULL,1,ANNUAL","AM,0.5,ANNUAL","PM,0.5,ANNUAL","FULL,0,COMPANY","AM,0.5,COMPANY"})
    void leaveAndItsCancellationOnlyApplyAtTheirOwnLastStage(String part,double charge,String source) {
        configureChain("L01","L02");
        var days=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(i->new DayForm(i,true,"09:00","18:00","12:00-13:00")).toList();
        UUID template=schedules.create(admin,new TemplateForm("S-"+UUID.randomUUID(),"검증 일정",today,days,null,"합성 일정",UUID.randomUUID()));
        schedules.assign(admin,worker.employeeId(),new AssignmentForm(schedules.versions(admin,template).getFirst().id(),today,0L,"배정",UUID.randomUUID()));
        CalendarSeed.period(db, admin, today,today.plusDays(20), "달력 근거", "");
        double opening=charge==0?0:3;
        UUID bucket=charge==0?null:flow.openBucket(admin,new BucketForm(worker.employeeId(),today,today,today.plusDays(20),3,"기초 근거","잔여 검증",UUID.randomUUID()));
        var date=today.plusDays(2);
        LeaveForm leaveForm;
        if ("COMPANY".equals(source)) {
            var row=new TypeRow(UUID.randomUUID().toString(),null,null,BulkData.Operation.CREATE,"C10",null,today,true,"경조휴가",
                    "DAY_HALF","PAID",charge==0?"NONE":"LEAVE_DAY","COMPANY_LEAVE","회사 휴가 운영 기준");
            var type=codes.saveTypes(admin,new BulkData.Form<>(UUID.randomUUID(),"회사 기준 적용",List.of(row))).rows().getFirst().id();
            var option=flow.leaveTypes(worker,date).stream().filter(t->t.id().equals(type)).findFirst().orElseThrow();
            leaveForm=new LeaveForm(bucket,date,date,null,"경조휴가 신청",UUID.randomUUID(),part,type,option.versionId());
        } else {
            leaveForm=new LeaveForm(bucket,date,date,null,"연차 신청",UUID.randomUUID(),part);
        }
        var preview=flow.formPreview(worker,null,"LEAVE_DAY");
        assertEquals(409,assertThrows(OrgException.class,()->flow.submitLeave(worker,leaveForm,"old-route")).status());
        UUID leave=flow.submitLeave(worker,leaveForm,preview.routeToken());
        assertEquals(opening,workerAnnualBalance());
        decideStage(head,leave,0);
        assertEquals("PENDING",flow.get(worker,leave).request().status());
        assertEquals("NONE",flow.get(worker,leave).request().effectStatus());
        assertEquals(opening,workerAnnualBalance());
        decideStage(direct,leave,1);
        assertEquals(opening-charge,workerAnnualBalance());
        UUID cancel=flow.requestCancellation(worker,leave,new CancelForm(false,2L,"휴가 취소",UUID.randomUUID()),
                flow.formPreview(worker,null,"CANCEL_LEAVE").routeToken());
        decideStage(head,cancel,0);
        assertEquals("EFFECTIVE",flow.get(worker,leave).request().effectStatus());
        assertEquals(opening-charge,workerAnnualBalance());
        decideStage(direct,cancel,1);
        assertEquals("CANCELED",flow.get(worker,leave).request().effectStatus());
        assertEquals(opening,workerAnnualBalance());
    }

    private double workerAnnualBalance() {
        return flow.buckets(worker,worker.employeeId()).stream().mapToDouble(Bucket::balanceDays).sum();
    }

    private void decideStage(TnaPrincipal actor,UUID request,long version) {
        flow.decide(actor,request,new DecisionForm("APPROVE",version,"단계 검토",UUID.randomUUID(),(int)version+1));
    }

    @Test void stepApprovalNotifiesTheNextApproverAndOnlyTheFinalOneNotifiesTheRequester() {
        configureChain("A01");
        var day=clocks.day(worker,yesterday);
        UUID request=flow.submitCorrection(worker,form(worker.employeeId(),day.inputRevision(),day.sourceFingerprint(),false,UUID.randomUUID()),flow.formPreview(worker,null,"ATTENDANCE_CORRECTION").routeToken());
        assertEquals(List.of(head.employeeId()+" APPROVAL_REQUESTED"),notified(request));
        decideStage(head,request,0);
        assertEquals(List.of(head.employeeId()+" APPROVAL_REQUESTED",direct.employeeId()+" APPROVAL_REQUESTED"),notified(request),"요청자는 중간 단계에서 알림을 받지 않는다");
        decideStage(direct,request,1);
        assertEquals(worker.employeeId()+" APPROVED",notified(request).getLast());
        assertEquals(3,notified(request).size());
    }
    private List<String> notified(UUID request) {
        return db.queryForList("SELECT n.recipient_employee_id||' '||n.kind FROM tna.notification n JOIN tna.approval_event e ON e.tenant_id=n.tenant_id AND e.id=n.event_id WHERE n.tenant_id=? AND n.request_id=? ORDER BY e.created_at,e.id",String.class,tenant,request);
    }

    @Test void repeatedIntermediateDecisionAndWithdrawalCannotApplyAnEffect() {
        configureChain("A01");
        var preview=flow.formPreview(worker,null,"ATTENDANCE_CORRECTION");
        var day=clocks.day(worker,yesterday);
        UUID request=flow.submitCorrection(worker,form(worker.employeeId(),day.inputRevision(),day.sourceFingerprint(),false,UUID.randomUUID()),preview.routeToken());
        var approve=new DecisionForm("APPROVE",0L,"팀장 검토",UUID.randomUUID(),1);
        assertEquals(409,assertThrows(OrgException.class,()->flow.decide(head,request,
                new DecisionForm("APPROVE",0L,"잘못된 단계",UUID.randomUUID(),2))).status());
        flow.decide(head,request,approve);
        flow.decide(head,request,approve);
        assertEquals(409,assertThrows(OrgException.class,()->flow.decide(head,request,
                new DecisionForm("APPROVE",0L,approve.reason(),approve.commandId(),2))).status());
        assertEquals(1,flow.get(worker,request).history().stream().filter(h->"STEP_APPROVED".equals(h.eventKind())).count());
        invalid(()->flow.reassign(admin,request,new ReassignForm(head.employeeId(),1L,"팀장에게 되돌리기",UUID.randomUUID(),2)));
        flow.withdraw(worker,request,new DecisionForm("WITHDRAW",1L,"직원 철회",UUID.randomUUID(),2));
        var detail=flow.get(head,request);
        assertEquals("WITHDRAWN",detail.request().status());
        assertEquals(List.of("APPROVED","CLOSED"),detail.steps().stream().map(DecisionStep::status).toList());
        assertEquals("NONE",detail.request().effectStatus());
        assertEquals(List.of(1,1,2),detail.history().stream().map(ApprovalHistory::stepNumber).toList());
        assertTrue(flow.inbox(direct).isEmpty());
    }

    @Test void absentFutureApproverNeedsExplicitReassignmentAndRejectionEndsPendingWork() {
        configureChain("A01");
        var preview=flow.formPreview(worker,null,"ATTENDANCE_CORRECTION");
        var day=clocks.day(worker,yesterday);
        UUID request=flow.submitCorrection(worker,form(worker.employeeId(),day.inputRevision(),day.sourceFingerprint(),false,UUID.randomUUID()),preview.routeToken());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='APPROVER'",tenant,direct.accountId());
        decideStage(head,request,0);
        assertTrue(flow.get(admin,request).reassignmentRequired());
        flow.reassign(admin,request,new ReassignForm(admin.employeeId(),1L,"실장 부재",UUID.randomUUID(),2));
        assertFalse(flow.get(worker,request).reassignmentRequired());
        flow.decide(admin,request,new DecisionForm("REJECT",2L,"최종 반려",UUID.randomUUID(),2));
        var detail=flow.get(worker,request);
        assertEquals("REJECTED",detail.request().status());
        assertEquals("NONE",detail.request().effectStatus());
        assertEquals("직접결재",detail.steps().get(1).originalApproverName());
        assertEquals("관리자",detail.steps().get(1).decidedByName());
    }

    @Test void rejectsDoubleHeadOrAuthorButPermitsTeamHeadsOwnLeaveToGoDirectlyToOffice() {
        configureChain("L01");
        var route=flow.formPreview(worker,null,"LEAVE_DAY");
        assertEquals(2,route.steps().size());
        db.update("INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'팀장 소속',?,?)",tenant,UUID.randomUUID(),head.employeeId(),organization,today,admin.accountId(),admin.accountId());
        var own=flow.formPreview(head,null,"LEAVE_DAY");
        assertEquals(List.of(direct.employeeId()),own.steps().stream().map(PreviewStep::approverId).toList());
        db.update("UPDATE tna.organization_head_assignment SET employee_id=? WHERE tenant_id=? AND organization_id=?",admin.employeeId(),tenant,organization);
        assertFalse(flow.formPreview(admin,worker.employeeId(),"LEAVE_DAY").eligible());
        db.update("UPDATE tna.organization_head_assignment SET employee_id=? WHERE tenant_id=? AND organization_id=?",direct.employeeId(),tenant,organization);
        assertFalse(flow.formPreview(worker,null,"LEAVE_DAY").eligible());
    }

    @Test void pendingRequestInThePreFormShapeCompletesByItsOriginalAssignee() {
        legacy(direct.employeeId());UUID request=correction(worker,worker.employeeId(),false);
        // Remove only the new fields from this synthetic row to match a pending V18 request.
        db.update("UPDATE tna.approval_event SET step_number=NULL WHERE tenant_id=? AND request_id=?",tenant,request);
        db.update("DELETE FROM tna.request_approval_step WHERE tenant_id=? AND request_id=?",tenant,request);
        db.update("UPDATE tna.approval_request SET form_version_id=NULL WHERE tenant_id=? AND id=?",tenant,request);
        configureChain("A01");
        var pending=flow.get(worker,request);
        assertTrue(pending.request().legacyForm());
        assertEquals("A01",pending.request().formCode());
        assertEquals(direct.employeeId(),pending.request().currentApproverId());
        assertTrue(pending.steps().isEmpty());
        flow.decide(direct,request,new DecisionForm("APPROVE",0L,"기존 단일 결재",UUID.randomUUID()));
        assertEquals("EFFECTIVE",flow.get(worker,request).request().effectStatus());
        assertNull(flow.get(worker,request).history().getLast().stepNumber());
    }

    @Test void organizationRouteResolvesHeadAndPersistsAnImmutableBoundSnapshot() {
        UUID route=route("ORGANIZATION_HEAD",null);assignment(route,"ORGANIZATION",organization);UUID h=head(head.employeeId());
        UUID request=correction(worker,worker.employeeId(),false);
        var row=db.queryForMap("SELECT * FROM tna.approval_request WHERE tenant_id=? AND id=?",tenant,request);
        assertEquals(head.employeeId(),row.get("current_approver_id"));assertNull(row.get("approver_assignment_id"));assertNull(row.get("approver_assignment_version"));
        var snapshot=db.queryForMap("SELECT * FROM tna.approval_route_snapshot WHERE tenant_id=? AND request_id=?",tenant,request);
        assertEquals(row.get("route_snapshot_id"),snapshot.get("id"));assertEquals(route,snapshot.get("route_version_id"));
        assertEquals(membership,snapshot.get("organization_assignment_id"));assertEquals(h,snapshot.get("organization_head_assignment_id"));
        assertEquals(head.employeeId(),snapshot.get("resolved_approver_employee_id"));
    }
    @Test void explicitEmployeeRoutePrecedesLegacyAndOrganizationRoute() {
        legacy(head.employeeId());assignment(route("ORGANIZATION_HEAD",null),"ORGANIZATION",organization);head(head.employeeId());
        assignment(route("DIRECT_EMPLOYEE",direct.employeeId()),"EMPLOYEE",worker.employeeId());
        assertEquals(direct.employeeId(),flow.get(worker,correction(worker,worker.employeeId(),false)).request().currentApproverId());
    }
    @Test void legacyPrecedesOrganizationRouteAndRetainsItsOriginalReference() {
        UUID legacy=legacy(direct.employeeId());assignment(route("ORGANIZATION_HEAD",null),"ORGANIZATION",organization);
        UUID request=correction(worker,worker.employeeId(),false);
        assertEquals(direct.employeeId(),flow.get(worker,request).request().currentApproverId());
        assertEquals(legacy,db.queryForObject("SELECT approver_assignment_id FROM tna.approval_request WHERE tenant_id=? AND id=?",UUID.class,tenant,request));
        assertNull(db.queryForObject("SELECT route_snapshot_id FROM tna.approval_request WHERE tenant_id=? AND id=?",UUID.class,tenant,request));
    }
    @Test void inactiveExplicitRouteCannotFallBackToAValidLegacyApprover() {
        legacy(head.employeeId());UUID route=route("DIRECT_EMPLOYEE",direct.employeeId());assignment(route,"EMPLOYEE",worker.employeeId());
        db.update("UPDATE tna.approval_route SET active=false WHERE tenant_id=? AND id=(SELECT route_id FROM tna.approval_route_version WHERE tenant_id=? AND id=?)",tenant,tenant,route);
        invalid(()->correction(worker,worker.employeeId(),false));assertEquals(0,count("approval_request"));
    }
    @Test void vacantHeadBlocksSubmissionWithoutGuessingTheParentOrAdministrator() {
        assignment(route("ORGANIZATION_HEAD",null),"ORGANIZATION",organization);
        invalid(()->correction(worker,worker.employeeId(),false));assertEquals(0,count("approval_route_snapshot"));
    }
    @Test void revokedAndDepartedApproversCannotBeUsedByANewRoute() {
        assignment(route("DIRECT_EMPLOYEE",direct.employeeId()),"EMPLOYEE",worker.employeeId());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='APPROVER'",tenant,direct.accountId());
        invalid(()->correction(worker,worker.employeeId(),false));
        db.update("UPDATE tna.role_grant SET active=true WHERE tenant_id=? AND account_id=? AND role='APPROVER'",tenant,direct.accountId());
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?",yesterday,tenant,direct.employeeId());
        invalid(()->correction(worker,worker.employeeId(),false));
    }
    @Test void targetAndExceptionalAuthorCannotApproveTheirOwnRequests() {
        assignment(route("DIRECT_EMPLOYEE",admin.employeeId()),"EMPLOYEE",worker.employeeId());
        invalid(()->correction(admin,worker.employeeId(),true));
        db.update("UPDATE tna.role_grant SET active=true WHERE tenant_id=? AND account_id=?",tenant,worker.accountId());
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'APPROVER')",tenant,worker.accountId());
        db.update("UPDATE tna.approval_route_assignment SET superseded_at=CURRENT_TIMESTAMP WHERE tenant_id=?",tenant);
        assignment(route("DIRECT_EMPLOYEE",worker.employeeId()),"EMPLOYEE",worker.employeeId());
        invalid(()->correction(worker,worker.employeeId(),false));
    }
    @Test void changingTheHeadDoesNotMovePendingAndExplicitReassignmentPreservesSnapshot() {
        assignment(route("ORGANIZATION_HEAD",null),"ORGANIZATION",organization);head(head.employeeId());
        UUID request=correction(worker,worker.employeeId(),false);
        var before=db.queryForMap("SELECT * FROM tna.approval_route_snapshot WHERE tenant_id=? AND request_id=?",tenant,request);
        db.update("UPDATE tna.organization_head_assignment SET superseded_at=CURRENT_TIMESTAMP WHERE tenant_id=?",tenant);head(direct.employeeId());
        assertEquals(head.employeeId(),flow.get(worker,request).request().currentApproverId());
        flow.reassign(admin,request,new ReassignForm(direct.employeeId(),0L,"명시 재할당",UUID.randomUUID()));
        assertEquals(direct.employeeId(),flow.get(worker,request).request().currentApproverId());
        assertEquals(before,db.queryForMap("SELECT * FROM tna.approval_route_snapshot WHERE tenant_id=? AND request_id=?",tenant,request));
        assertEquals(403,assertThrows(OrgException.class,()->flow.decide(head,request,new DecisionForm("REJECT",1L,"이전 담당",UUID.randomUUID()))).status());
        flow.decide(direct,request,new DecisionForm("REJECT",1L,"새 담당 반려",UUID.randomUUID()));
        assertEquals("REJECTED",flow.get(worker,request).request().status());
    }

    @Test
    void aHeadChangeFromTheChartUpdatesOnlyNewRequestsForANewlyCodedOrganization() throws Exception {
        String originalCode = db.queryForObject("SELECT code FROM tna.organization WHERE tenant_id=? AND id=?",
                String.class, tenant, organization);
        var created = mvc.perform(post("/admin/organizations").with(as(admin)).with(csrf())
                        .param("name", "신규 합성 조직").param("active", "true")
                        .param("reason", "조직 자동 코드 검증").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther()).andReturn().getResponse().getRedirectedUrl();
        assertNotNull(created);
        String createdPath = URI.create(created).getPath();
        UUID createdOrganization = UUID.fromString(createdPath.substring(createdPath.lastIndexOf('/') + 1));
        assertEquals(today.getYear() + "0001", db.queryForObject(
                "SELECT code FROM tna.organization WHERE tenant_id=? AND id=?", String.class, tenant, createdOrganization));
        assertEquals(originalCode, db.queryForObject(
                "SELECT code FROM tna.organization WHERE tenant_id=? AND id=?", String.class, tenant, organization));

        db.update("UPDATE tna.employee_assignment SET organization_id=? WHERE tenant_id=? AND id=?",
                createdOrganization, tenant, membership);
        var anotherWorker = member("다른 신청자", Set.of("EMPLOYEE"));
        db.update("""
                INSERT INTO tna.employee_assignment
                  (tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'새 조직의 소속',?,?)
                """, tenant, UUID.randomUUID(), anotherWorker.employeeId(), createdOrganization,
                today.minusMonths(1), admin.accountId(), admin.accountId());
        assignment(route("ORGANIZATION_HEAD", null), "ORGANIZATION", createdOrganization);

        mvc.perform(post("/admin/organization-heads/commands").with(as(admin)).with(csrf())
                        .param("operation", "ASSIGN").param("organizationId", createdOrganization.toString())
                        .param("employeeId", head.employeeId().toString()).param("validFrom", today.toString())
                        .param("lastDay", today.plusDays(2).toString()).param("reason", "최초 조직장")
                        .param("expectedVersion", "0").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther());
        UUID originalHeadAssignment = db.queryForObject("""
                SELECT id FROM tna.organization_head_assignment WHERE tenant_id=? AND organization_id=?
                """, UUID.class, tenant, createdOrganization);
        UUID existingRequest = correction(worker, worker.employeeId(), false);
        var originalSnapshot = db.queryForMap("""
                SELECT * FROM tna.approval_route_snapshot WHERE tenant_id=? AND request_id=?
                """, tenant, existingRequest);
        assertEquals(originalHeadAssignment, originalSnapshot.get("organization_head_assignment_id"));

        mvc.perform(post("/admin/organization-heads/commands").with(as(admin)).with(csrf())
                        .param("operation", "REPLACE").param("organizationId", createdOrganization.toString())
                        .param("assignmentId", originalHeadAssignment.toString())
                        .param("employeeId", direct.employeeId().toString()).param("validFrom", today.toString())
                        .param("lastDay", today.plusDays(2).toString()).param("reason", "같은 날 후임 배정")
                        .param("expectedVersion", "1").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isSeeOther());

        var nextDay = clocks.day(anotherWorker, yesterday);
        UUID nextRequest = flow.submitCorrection(anotherWorker, form(anotherWorker.employeeId(),
                nextDay.inputRevision(), nextDay.sourceFingerprint(), false, UUID.randomUUID()));
        assertEquals(direct.employeeId(), flow.get(anotherWorker, nextRequest).request().currentApproverId());
        assertEquals("PENDING", flow.get(worker, existingRequest).request().status());
        assertEquals(head.employeeId(), flow.get(worker, existingRequest).request().currentApproverId());
        assertEquals(originalSnapshot, db.queryForMap("""
                SELECT * FROM tna.approval_route_snapshot WHERE tenant_id=? AND request_id=?
                """, tenant, existingRequest));
    }
    @Test void retriedCommandCreatesOnlyOneRequestAndSnapshot() {
        assignment(route("DIRECT_EMPLOYEE",direct.employeeId()),"EMPLOYEE",worker.employeeId());
        var s=clocks.day(worker,yesterday);var form=form(worker.employeeId(),s.inputRevision(),s.sourceFingerprint(),false,UUID.randomUUID());
        UUID id=flow.submitCorrection(worker,form);assertEquals(id,flow.submitCorrection(worker,form));
        assertEquals(1,count("approval_request"));assertEquals(1,count("approval_route_snapshot"));
    }
    @Test void snapshotFailureRollsBackRequestRevisionEventAndAudit() {
        assignment(route("DIRECT_EMPLOYEE",direct.employeeId()),"EMPLOYEE",worker.employeeId());
        int audits=count("org_audit_event");String constraint="synthetic_route_snapshot_"+tenant.toString().replace("-","");
        db.execute("ALTER TABLE tna.approval_route_snapshot ADD CONSTRAINT "+constraint+" CHECK (tenant_id<>'"+tenant+"'::uuid)");
        try {assertThrows(OrgException.class,()->correction(worker,worker.employeeId(),false));}
        finally {db.execute("ALTER TABLE tna.approval_route_snapshot DROP CONSTRAINT "+constraint);}
        assertEquals(0,count("approval_request"));assertEquals(0,count("approval_event"));assertEquals(0,count("day_input_revision"));assertEquals(audits,count("org_audit_event"));
    }
    @Test void profileShowsTheActualRouteAndVacancyReason() throws Exception {
        assignment(route("ORGANIZATION_HEAD",null),"ORGANIZATION",organization);
        mvc.perform(get("/me/profile").with(as(worker)).accept(ReactPageView()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/profile/approverEligible",false))
                .andExpect(ReactPageAssertions.data("/profile/approvalPreview/source","ORGANIZATION_ROUTE"));
        head(head.employeeId());
        mvc.perform(get("/me/profile").with(as(worker)).accept(ReactPageView()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/profile/approverName","조직장"))
                .andExpect(ReactPageAssertions.data("/profile/approverEligible",true));
    }
    @Test void completedInboxIncludesOnlyTheActorsDecisionsAndNotWithdrawals() throws Exception {
        legacy(direct.employeeId());UUID first=correction(worker,worker.employeeId(),false);
        flow.decide(direct,first,new DecisionForm("REJECT",0L,"본인 결정",UUID.randomUUID()));
        UUID withdrawn=correction(worker,worker.employeeId(),false);flow.withdraw(worker,withdrawn,new DecisionForm("WITHDRAW",0L,"철회",UUID.randomUUID()));
        mvc.perform(get("/approvals/completed").with(as(direct)).accept(ReactPageView())).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/requests/0/id",first.toString())).andExpect(ReactPageAssertions.data("/completed",true));
        mvc.perform(get("/approvals/completed").with(as(head)).accept(ReactPageView())).andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/requests",List.of()));
        assertTrue(flow.inbox(direct).isEmpty());
    }
    @Test void aRouteCannotUseAnUnacceptedAccountAndRepresentativeSelfApprovalIsBlocked() {
        UUID representative=worker.employeeId();
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'COMPANY_REPRESENTATIVE'),(?,?,'APPROVER')",tenant,worker.accountId(),tenant,worker.accountId());
        assignment(route("DIRECT_EMPLOYEE",representative),"EMPLOYEE",worker.employeeId());invalid(()->correction(worker,worker.employeeId(),false));
        db.update("UPDATE tna.approval_route_assignment SET superseded_at=CURRENT_TIMESTAMP WHERE tenant_id=?",tenant);
        assignment(route("DIRECT_EMPLOYEE",direct.employeeId()),"EMPLOYEE",worker.employeeId());
        db.update("UPDATE tna.tenant_account SET active=false,password_hash=NULL WHERE tenant_id=? AND id=?",tenant,direct.accountId());
        invalid(()->correction(worker,worker.employeeId(),false));
    }
    @Test void leaveAndCancellationResolveSeparatelyAndPreserveOriginalApprovalAndLedger() {
        assignment(route("DIRECT_EMPLOYEE",direct.employeeId()),"EMPLOYEE",worker.employeeId());
        var days=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(i->new DayForm(i,true,"09:00","18:00","12:00-13:00")).toList();
        UUID template=schedules.create(admin,new TemplateForm("S-"+UUID.randomUUID(),"합성 결재선 일정",today,days,null,"합성 일정",UUID.randomUUID()));
        schedules.assign(admin,worker.employeeId(),new AssignmentForm(schedules.versions(admin,template).getFirst().id(),today,0L,"합성 배정",UUID.randomUUID()));
        CalendarSeed.period(db, admin, today,today.plusDays(20), "합성 검토 근거", "");
        UUID bucket=flow.openBucket(admin,new BucketForm(worker.employeeId(),today,today,today.plusDays(20),2,"합성 기초 근거","합성 기초",UUID.randomUUID()));
        UUID leave=flow.submitLeave(worker,new LeaveForm(bucket,today.plusDays(2),today.plusDays(2),null,"합성 연차",UUID.randomUUID()));
        flow.decide(direct,leave,new DecisionForm("APPROVE",0L,"합성 연차 승인",UUID.randomUUID()));
        var originalSnapshot=db.queryForMap("SELECT * FROM tna.approval_route_snapshot WHERE tenant_id=? AND request_id=?",tenant,leave);
        db.update("UPDATE tna.approval_route_assignment SET superseded_at=CURRENT_TIMESTAMP WHERE tenant_id=?",tenant);
        assignment(route("DIRECT_EMPLOYEE",head.employeeId()),"EMPLOYEE",worker.employeeId());
        UUID cancel=flow.requestCancellation(worker,leave,new CancelForm(false,1L,"새 결재선 취소 요청",UUID.randomUUID()));
        assertEquals(head.employeeId(),flow.get(worker,cancel).request().currentApproverId());
        flow.decide(head,cancel,new DecisionForm("APPROVE",0L,"독립 취소 승인",UUID.randomUUID()));
        assertEquals("APPROVED",flow.get(worker,leave).request().status());assertEquals("CANCELED",flow.get(worker,leave).request().effectStatus());
        assertEquals(originalSnapshot,db.queryForMap("SELECT * FROM tna.approval_route_snapshot WHERE tenant_id=? AND request_id=?",tenant,leave));
        assertEquals(2,count("approval_route_snapshot"));assertEquals(3,count("leave_ledger"));
        assertEquals(2,flow.buckets(worker,worker.employeeId()).getFirst().balanceDays());
    }
    @Test void anotherRequestsSnapshotFailsOnlyWhenTheDeferredBindingIsCheckedAtCommit() throws Exception {
        assignment(route("DIRECT_EMPLOYEE",direct.employeeId()),"EMPLOYEE",worker.employeeId());
        UUID first=correction(worker,worker.employeeId(),false);
        flow.withdraw(worker,first,new DecisionForm("WITHDRAW",0L,"다음 신청 준비",UUID.randomUUID()));
        UUID second=correction(worker,worker.employeeId(),false);
        UUID firstSnapshot=db.queryForObject("SELECT route_snapshot_id FROM tna.approval_request WHERE tenant_id=? AND id=?",UUID.class,tenant,first);
        UUID secondSnapshot=db.queryForObject("SELECT route_snapshot_id FROM tna.approval_request WHERE tenant_id=? AND id=?",UUID.class,tenant,second);
        try(Connection connection=DatabaseFixture.start().adminDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(PreparedStatement statement=connection.prepareStatement("UPDATE tna.approval_request SET route_snapshot_id=? WHERE tenant_id=? AND id=?")) {
                statement.setObject(1,firstSnapshot);statement.setObject(2,tenant);statement.setObject(3,second);
                assertEquals(1,statement.executeUpdate());
            }
            assertEquals("23503",assertThrows(SQLException.class,connection::commit).getSQLState());
            connection.rollback();
        }
        assertEquals(secondSnapshot,db.queryForObject("SELECT route_snapshot_id FROM tna.approval_request WHERE tenant_id=? AND id=?",UUID.class,tenant,second));
    }
    @Test void legacyPairAndRouteResolutionXorAreEnforcedByTheDatabase() throws Exception {
        assignment(route("DIRECT_EMPLOYEE",direct.employeeId()),"EMPLOYEE",worker.employeeId());UUID request=correction(worker,worker.employeeId(),false);
        UUID old=legacy(head.employeeId());
        try(Connection connection=DatabaseFixture.start().adminDataSource().getConnection()) {
            sqlFailure(connection,"23514","UPDATE tna.approval_request SET approver_assignment_id=? WHERE tenant_id=? AND id=?",old,tenant,request);
            sqlFailure(connection,"23514","UPDATE tna.approval_request SET approver_assignment_id=?,approver_assignment_version=0 WHERE tenant_id=? AND id=?",old,tenant,request);
            sqlFailure(connection,"23514","UPDATE tna.approval_request SET route_snapshot_id=NULL WHERE tenant_id=? AND id=?",tenant,request);
        }
        assertEquals(1,count("approval_request"));assertNotNull(flow.get(worker,request).request().routeSnapshotId());
    }
    @Test void applicationRoleCannotRewriteOrDeleteTheSnapshotOrChangeOriginalRequestReferences() throws Exception {
        UUID version=route("DIRECT_EMPLOYEE",direct.employeeId());assignment(version,"EMPLOYEE",worker.employeeId());UUID request=correction(worker,worker.employeeId(),false);
        UUID snapshot=flow.get(worker,request).request().routeSnapshotId();
        runtimeDenied("UPDATE tna.approval_route_snapshot SET resolved_approver_employee_id=resolved_approver_employee_id WHERE tenant_id=? AND id=?",snapshot);
        runtimeDenied("DELETE FROM tna.approval_route_snapshot WHERE tenant_id=? AND id=?",snapshot);
        runtimeDenied("UPDATE tna.approval_route_version SET name=name WHERE tenant_id=? AND id=?",version);
        runtimeDenied("UPDATE tna.approval_request SET route_snapshot_id=route_snapshot_id WHERE tenant_id=? AND id=?",request);
        runtimeDenied("UPDATE tna.approval_request SET approver_assignment_id=approver_assignment_id WHERE tenant_id=? AND id=?",request);
    }
    @Test void populatedSnapshotsAreHiddenFromOtherTenantsAndContextDoesNotSurviveCommit() throws Exception {
        assignment(route("DIRECT_EMPLOYEE",direct.employeeId()),"EMPLOYEE",worker.employeeId());correction(worker,worker.employeeId(),false);
        try(Connection connection=DatabaseFixture.start().appDataSource().getConnection()) {
            connection.setAutoCommit(false);tenantContext(connection,tenant);assertEquals(1,snapshotCount(connection));connection.commit();
            assertEquals(0,snapshotCount(connection));connection.commit();
            tenantContext(connection,UUID.randomUUID());assertEquals(0,snapshotCount(connection));connection.rollback();
        }
    }
    @Test void revokingTheResolvedHeadsRoleBlocksExistingPendingDecisionsUntilExplicitReassignment() {
        assignment(route("ORGANIZATION_HEAD",null),"ORGANIZATION",organization);head(head.employeeId());UUID request=correction(worker,worker.employeeId(),false);
        var original=db.queryForMap("SELECT * FROM tna.approval_route_snapshot WHERE tenant_id=? AND request_id=?",tenant,request);
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='APPROVER'",tenant,head.accountId());
        assertEquals(403,assertThrows(OrgException.class,()->flow.decide(head,request,new DecisionForm("REJECT",0L,"회수된 조직장",UUID.randomUUID()))).status());
        assertEquals("PENDING",flow.get(worker,request).request().status());
        flow.reassign(admin,request,new ReassignForm(direct.employeeId(),0L,"권한 회수 후 명시 재할당",UUID.randomUUID()));
        flow.decide(direct,request,new DecisionForm("REJECT",1L,"새 담당 결정",UUID.randomUUID()));
        assertEquals(original,db.queryForMap("SELECT * FROM tna.approval_route_snapshot WHERE tenant_id=? AND request_id=?",tenant,request));
    }

    private static String ReactPageView(){return "application/vnd.worklog.page+json";}
    private static void sqlFailure(Connection connection,String state,String sql,Object... args) throws Exception {
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
            assertEquals(state,assertThrows(SQLException.class,statement::executeUpdate).getSQLState());
        }
    }
    private void runtimeDenied(String sql,UUID id) throws Exception {
        try(Connection connection=DatabaseFixture.start().appDataSource().getConnection()) {
            connection.setAutoCommit(false);tenantContext(connection,tenant);
            try {sqlFailure(connection,"42501",sql,tenant,id);}finally {connection.rollback();}
        }
    }
    private static void tenantContext(Connection connection,UUID tenant) throws Exception {
        try(PreparedStatement statement=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {statement.setString(1,tenant.toString());statement.execute();}
    }
    private static int snapshotCount(Connection connection) throws Exception {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM tna.approval_route_snapshot")){rows.next();return rows.getInt(1);}
    }
    private UUID route(String kind,UUID employee) {
        UUID route=UUID.randomUUID(),version=UUID.randomUUID();
        db.update("INSERT INTO tna.approval_route(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 결재선',?,?)",tenant,route,"R-"+route,admin.accountId(),admin.accountId());
        db.update("INSERT INTO tna.approval_route_version(tenant_id,id,route_id,revision,name,approver_kind,employee_id,reason,created_by) VALUES (?,?,?,1,'합성 결재선',?,?,'합성 정의',?)",tenant,version,route,kind,employee,admin.accountId());return version;
    }
    private UUID assignment(UUID version,String scope,UUID target) {
        UUID id=UUID.randomUUID();db.update("INSERT INTO tna.approval_route_assignment(tenant_id,id,route_version_id,scope_kind,employee_id,organization_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,?,?,'합성 배정',?,?)",tenant,id,version,scope,scope.equals("EMPLOYEE")?target:null,scope.equals("ORGANIZATION")?target:null,today,admin.accountId(),admin.accountId());return id;
    }
    private UUID legacy(UUID approver) {UUID id=UUID.randomUUID();db.update("INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 기존 지정',?,?)",tenant,id,worker.employeeId(),approver,today,admin.accountId(),admin.accountId());return id;}
    private UUID head(UUID employee) {UUID id=UUID.randomUUID();db.update("INSERT INTO tna.organization_head_assignment(tenant_id,id,organization_id,employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 조직장',?,?)",tenant,id,organization,employee,today,admin.accountId(),admin.accountId());return id;}
    private UUID correction(TnaPrincipal author,UUID target,boolean exceptional) {var s=clocks.day(worker,yesterday);return flow.submitCorrection(author,form(target,s.inputRevision(),s.sourceFingerprint(),exceptional,UUID.randomUUID()));}
    private CorrectionForm form(UUID target,long revision,String fingerprint,boolean exceptional,UUID command) {return new CorrectionForm(target,yesterday,revision,fingerprint,"SET",yesterday+"T09:00","SET",yesterday+"T18:00",true,true,yesterday+"T12:00/"+yesterday+"T13:00",null,null,exceptional,"합성 결재선 정정",command);}
    private int count(String table){return db.queryForObject("SELECT count(*) FROM tna."+table+" WHERE tenant_id=?",Integer.class,tenant);}
    private void invalid(Runnable action){assertEquals(422,assertThrows(OrgException.class,action::run).status());}
    private TnaPrincipal member(String name,Set<String> roles) {UUID employee=UUID.randomUUID(),account=UUID.randomUUID();db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",tenant,employee,"E-"+employee,name,today.minusYears(1));db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused-hash')",tenant,account,employee,account+"@example.test");for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);return new TnaPrincipal(tenant,account,employee,1,roles,Instant.now());}
    private RequestPostProcessor as(TnaPrincipal actor){return authentication(new UsernamePasswordAuthenticationToken(actor,null,actor.roles().stream().map(r->new SimpleGrantedAuthority("ROLE_"+r)).toList()));}
}
