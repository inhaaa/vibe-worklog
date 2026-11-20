package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import kr.tna.clock.AttendanceSettingsData.CalendarForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.*;
import kr.tna.schedule.ScheduleData.*;
import kr.tna.schedule.ScheduleService;
import org.junit.jupiter.api.*;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@SpringBootTest @AutoConfigureMockMvc
class AnnualRequestsWebIT {
    @Autowired MockMvc mvc;
    @Autowired LeaveWorkflowService flow;
    @Autowired kr.tna.worktime.WorktimeService reports;
    @Autowired ScheduleService schedules;
    @Autowired AttendanceSettingsService settings;
    @Autowired ApproverService approvers;
    JdbcTemplate db;
    UUID tenant,bucket,schedule,calendar,binding;
    TnaPrincipal admin,worker,approver,other;
    LocalDate today;
    static final String PAGE="application/vnd.worklog.page+json";
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){
        var f=DatabaseFixture.start();p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void seed(){
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());today=LocalDate.now(ZoneId.of("Asia/Seoul"));tenant=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 연간')",tenant,"YEAR-"+tenant);
        admin=member(Set.of("EMPLOYEE","ATTENDANCE_ADMIN"));worker=member(Set.of("EMPLOYEE"));approver=member(Set.of("EMPLOYEE","APPROVER"));other=member(Set.of("EMPLOYEE"));
        approvers.set(admin,new ApproverForm(worker.employeeId(),approver.employeeId(),today,0L,"합성 지정",UUID.randomUUID()));
        binding=db.queryForObject("SELECT id FROM tna.approver_assignment WHERE tenant_id=? AND employee_id=? AND superseded_at IS NULL",UUID.class,tenant,worker.employeeId());
        var days=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(i->new DayForm(i,true,"09:00","18:00","12:00-13:00")).toList();
        UUID template=schedules.create(admin,new TemplateForm("Y-"+UUID.randomUUID(),"합성 일정",today,days,null,"합성 일정",UUID.randomUUID()));
        schedule=schedules.versions(admin,template).getFirst().id();
        long employeeVersion=db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?",Long.class,tenant,worker.employeeId());
        schedules.assign(admin,worker.employeeId(),new AssignmentForm(schedule,today,employeeVersion,"합성 배정",UUID.randomUUID()));
        calendar=settings.calendar(admin,new CalendarForm(today,today.plusDays(300),true,"합성 달력","","합성 달력",UUID.randomUUID()));
        bucket=flow.openBucket(admin,new BucketForm(worker.employeeId(),today.minusYears(2),today.minusYears(2),today.plusDays(400),20,"합성 기초","합성 등록",UUID.randomUUID()));
    }

    @Test void defaultLandingIsOwnCurrentYearAndSeparatesUnknownAttributionFromAvailableBalance() throws Exception {
        var annual=annual(get("/me/requests"),worker);
        assertEquals(today.getYear(),annual.path("year").asInt());assertEquals(20,annual.path("currentAvailableDays").asLong());
        assertEquals(1,annual.path("unknownBucketCount").asInt());assertTrue(annual.path("registeredDays").isNull());
        assertEquals("NO_REGISTERED_DATA",annual.path("coverage").asString());assertTrue(annual.path("requests").isEmpty());
    }
    @Test void yearClipsLeaveDatesAndKeepsCurrentStatusAndApprovalEffectiveRules() throws Exception {
        LocalDate from=LocalDate.of(today.getYear()-1,12,31),to=from.plusDays(1);
        UUID effective=raw("LEAVE_DAY","APPROVED",from,to,worker,worker,bucket,null,"EFFECTIVE");day(effective,from,1);day(effective,to,1);
        UUID canceled=raw("LEAVE_DAY","APPROVED",to.plusDays(1),to.plusDays(1),worker,worker,bucket,null,"CANCELED");day(canceled,to.plusDays(1),1);
        var previous=annual(get("/me/requests").param("year",String.valueOf(from.getYear())),worker);
        var current=annual(get("/me/requests").param("year",String.valueOf(to.getYear())),worker);
        assertEquals(1,previous.path("usedDays").asLong());assertEquals(1,current.path("usedDays").asLong());
        assertEquals(1,previous.path("requests").size());assertEquals(2,current.path("requests").size());
    }
    @Test void pendingCancellationDoesNotRemoveOriginalApprovedFutureDayAndPendingLeaveDoesNotReserve() throws Exception {
        LocalDate date=today.plusDays(2);UUID original=raw("LEAVE_DAY","APPROVED",date,date,worker,worker,bucket,null,"EFFECTIVE");day(original,date,1);
        raw("CANCEL_LEAVE","PENDING",date,date,worker,worker,bucket,original,null);
        UUID pending=raw("LEAVE_DAY","PENDING",date.plusDays(1),date.plusDays(2),worker,worker,bucket,null,null);day(pending,date.plusDays(1),0);day(pending,date.plusDays(2),1);
        var annual=annual(get("/me/requests"),worker);assertEquals(1,annual.path("plannedDays").asLong());assertEquals(1,annual.path("pendingDays").asLong());assertEquals(20,annual.path("currentAvailableDays").asLong());
    }
    @Test void filtersOnlyChangePaginatedListAndAuthoredOthersNeverEnterOwnMetrics() throws Exception {
        UUID r=raw("LEAVE_DAY","PENDING",today,today,worker,worker,bucket,null,null);day(r,today,1);
        raw("ATTENDANCE_CORRECTION","REJECTED",today,today,worker,worker,null,null,null);
        raw("ATTENDANCE_CORRECTION","PENDING",today,today,other,worker,null,null,null);
        var annual=annual(get("/me/requests").param("status","REJECTED"),worker);
        assertEquals(1,annual.path("requests").size());assertEquals(1,annual.path("authoredRequests").size());assertEquals(1,annual.path("pendingDays").asLong());
        assertEquals(2,annual.path("summaries").size());
    }
    @Test void fiftyRowsHaveASeparateNextPageAndStatisticsUseTheWholeYear() throws Exception {
        for(int i=0;i<51;i++){UUID r=raw("LEAVE_DAY","PENDING",today,today,worker,worker,bucket,null,null);day(r,today,1);}
        var first=annual(get("/me/requests"),worker);var second=annual(get("/me/requests").param("page","1"),worker);
        assertEquals(50,first.path("requests").size());assertTrue(first.path("hasNext").asBoolean());assertEquals(51,first.path("pendingDays").asLong());
        assertEquals(1,second.path("requests").size());assertFalse(second.path("hasNext").asBoolean());assertEquals(51,second.path("pendingDays").asLong());
    }
    @Test void confirmedAttributionUsesLatestRevisionAndNeverSubtractsYearUsageAgain() throws Exception {
        attribution(1,"CONFIRMED",today.getYear());var annual=annual(get("/me/requests"),worker);
        assertEquals(20,annual.path("registeredDays").asLong());assertEquals(20,annual.path("attributedBalanceDays").asLong());assertEquals(0,annual.path("unknownBucketCount").asInt());
        attribution(2,"UNCLASSIFIED",null);var latest=annual(get("/me/requests"),worker);
        assertTrue(latest.path("registeredDays").isNull());assertEquals(1,latest.path("unknownBucketCount").asInt());
    }
    @Test void registeredQuantityAndBucketContributionRetainHalfDayAdjustments() throws Exception {
        db.update("""
                INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,source_reference,reason,created_by)
                VALUES (?,?,?,?,'ADJUSTMENT',-0.5,?,'대조 원장','반일 조정',?)
                """,tenant,UUID.randomUUID(),bucket,worker.employeeId(),today,admin.accountId());
        attribution(1,"CONFIRMED",today.getYear());
        var annual=annual(get("/me/requests"),worker);
        assertEquals(19.5,annual.path("registeredDays").asDouble());
        assertEquals(19.5,annual.at("/attributionBuckets/0/openingAdjustmentDays").asDouble());
        assertEquals(19.5,annual.path("attributedBalanceDays").asDouble());
    }
    @Test void historicalSelectionDoesNotTurnCurrentBalanceIntoYearEndBalanceAndLeapDayIsIncluded() throws Exception {
        LocalDate leap=LocalDate.of(2024,2,29);UUID r=raw("LEAVE_DAY","APPROVED",leap,leap,worker,worker,bucket,null,"EFFECTIVE");day(r,leap,1);
        var annual=annual(get("/me/requests").param("year","2024"),worker);assertEquals(1,annual.path("usedDays").asLong());assertEquals(20,annual.path("currentAvailableDays").asLong());
    }
    @Test void invalidYearStatusAndForeignTypeAreRejectedAndEmployeeCannotSelectAnotherPerson() throws Exception {
        mvc.perform(as(get("/me/requests").param("year","9999"),worker)).andExpect(status().isUnprocessableEntity());
        mvc.perform(as(get("/me/requests").param("status","UNKNOWN"),worker)).andExpect(status().isUnprocessableEntity());
        mvc.perform(as(get("/me/requests").param("typeId",UUID.randomUUID().toString()),worker)).andExpect(status().isNotFound());
        mvc.perform(as(get("/me/requests").param("employeeId",other.employeeId().toString()),worker)).andExpect(status().isBadRequest());
    }
    @Test void composeGetAndSuccessfulPreviewCreateNoRowsOrAuditAndKeepTheCommand() throws Exception {
        var before=snapshot();var result=mvc.perform(as(get("/me/requests/new").param("year",String.valueOf(today.getYear())),worker)).andExpect(status().isOk()).andReturn();
        var compose=ReactPageAssertions.envelope(result).at("/data/compose");assertEquals("MODAL",compose.path("mode").asString());String command=compose.at("/leaveForm/commandId").asString();
        var preview=mvc.perform(as(post("/me/requests/preview").param("year",String.valueOf(today.getYear())).param("kind","LEAVE_DAY").param("bucketId",bucket.toString()).param("from",today.plusDays(1).toString()).param("to",today.plusDays(2).toString()).param("reason","합성 미리보기").param("commandId",command).with(SecurityMockMvcRequestPostProcessors.csrf()),worker)).andExpect(status().isOk()).andReturn();
        var data=ReactPageAssertions.envelope(preview).at("/data/compose");assertEquals(command,data.at("/leaveForm/commandId").asString());assertEquals(2,data.at("/preview/quantityDays").asInt());assertTrue(data.at("/preview/eligible").asBoolean());assertEquals(before,snapshot());
    }
    @Test void correctionContextSuppliesServerReferencesAndGeneralPageFallbackWithoutWriting() throws Exception {
        var before=snapshot();var result=mvc.perform(as(get("/me/requests/new").param("year",String.valueOf(today.getYear())).param("kind","ATTENDANCE_CORRECTION").param("mode","PAGE").param("date",today.toString()),worker)).andExpect(status().isOk()).andReturn();
        var compose=ReactPageAssertions.envelope(result).at("/data/compose");assertEquals("PAGE",compose.path("mode").asString());assertEquals(64,compose.at("/correctionForm/sourceFingerprint").asString().length());assertEquals(today.toString(),compose.at("/day/workDate").asString());assertEquals(before,snapshot());
    }
    @Test void yearContextPostReturnsToAnnualViewWhileLegacyPostStillReturnsToDetail() throws Exception {
        String command=UUID.randomUUID().toString();var first=mvc.perform(as(leavePost(today.plusDays(3),command).param("kind","LEAVE_DAY").param("year",String.valueOf(today.getYear())),worker)).andExpect(status().isSeeOther()).andReturn();
        assertTrue(first.getResponse().getHeader("Location").startsWith("/me/requests?year="));
        assertTrue(first.getResponse().getHeader("Location").contains("createdId="));
        mvc.perform(as(leavePost(today.plusDays(4),UUID.randomUUID().toString()),worker)).andExpect(status().isSeeOther()).andExpect(header().string("Location",org.hamcrest.Matchers.startsWith("/requests/")));
    }
    @Test void validationAndConflictKeepYearInputAndCommandForTheOpenModal() throws Exception {
        String command=UUID.randomUUID().toString();var invalid=mvc.perform(as(leavePost(today.minusDays(1),command).param("year",String.valueOf(today.getYear()-1)),worker)).andExpect(status().isUnprocessableEntity()).andReturn();
        var model=ReactPageAssertions.envelope(invalid).at("/data");assertEquals(today.getYear()-1,model.at("/annual/year").asInt());assertEquals(command,model.at("/compose/leaveForm/commandId").asString());assertEquals(today.minusDays(1).toString(),model.at("/compose/leaveForm/from").asString());
        flow.submitLeave(worker,new LeaveForm(bucket,today.plusDays(1),today.plusDays(1),null,"합성 원신청",UUID.randomUUID()));
        var conflict=mvc.perform(as(leavePost(today.plusDays(1),command).param("year",String.valueOf(today.getYear())),worker)).andExpect(status().isConflict()).andReturn();
        assertTrue(ReactPageAssertions.envelope(conflict).at("/data/compose/conflict").asBoolean());
    }
    @Test void previewAndFinalSubmissionRequireCsrfAndRejectUnsupportedOrForeignTargets() throws Exception {
        mvc.perform(as(post("/me/requests/preview").param("year",String.valueOf(today.getYear())).param("kind","LEAVE_DAY"),worker)).andExpect(status().isForbidden());
        mvc.perform(as(get("/me/requests/new").param("kind","SICK"),worker)).andExpect(status().isUnprocessableEntity());
        mvc.perform(as(leavePost(today.plusDays(1),UUID.randomUUID().toString()).param("year",String.valueOf(today.getYear())).param("targetEmployeeId",other.employeeId().toString()),worker)).andExpect(status().isBadRequest());
    }
    @Test void disablingPolicyAfterPreviewStillBlocksFinalSubmissionAndPreservesItsCommand() throws Exception {
        String command=UUID.randomUUID().toString();
        mvc.perform(as(post("/me/requests/preview").param("year",String.valueOf(today.getYear())).param("kind","LEAVE_DAY").param("bucketId",bucket.toString()).param("from",today.plusDays(1).toString()).param("to",today.plusDays(1).toString()).param("reason","합성 미리보기").param("commandId",command).with(SecurityMockMvcRequestPostProcessors.csrf()),worker)).andExpect(status().isOk());
        db.update("""
          INSERT INTO tna.attendance_type_version(tenant_id,id,type_id,revision,effective_from,enabled,name,unit,pay_kind,deduction_rule,recognition_rule,basis,reason,created_by)
          SELECT tenant_id,?,type_id,2,?,false,name,unit,pay_kind,deduction_rule,recognition_rule,'합성 중지 근거','합성 중지',?
          FROM tna.attendance_type_version WHERE tenant_id=? AND type_id=? AND revision=1
          """,UUID.randomUUID(),today,admin.accountId(),tenant,type());
        var result=mvc.perform(as(leavePost(today.plusDays(1),command).param("year",String.valueOf(today.getYear())),worker)).andExpect(status().isUnprocessableEntity()).andReturn();
        assertEquals(command,ReactPageAssertions.envelope(result).at("/data/compose/leaveForm/commandId").asString());assertEquals(0,snapshot().getFirst());
    }
    @Test void correctionConflictReturnsNewDayFactsButKeepsTheOriginalProposalAndCommand() throws Exception {
        var result=mvc.perform(as(get("/me/requests/new").param("kind","ATTENDANCE_CORRECTION").param("date",today.toString()),worker)).andExpect(status().isOk()).andReturn();
        var form=ReactPageAssertions.envelope(result).at("/data/compose/correctionForm");String command=form.path("commandId").asString();
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required) VALUES (?,?,?,1,1,true)",tenant,worker.employeeId(),today);
        var conflict=mvc.perform(as(post("/me/requests/correction").param("year",String.valueOf(today.getYear())).param("workDate",today.toString()).param("baseInputRevision","0").param("sourceFingerprint",form.path("sourceFingerprint").asString()).param("inAction","CLEAR").param("outAction","CLEAR").param("reason","합성 정정 제안").param("commandId",command).with(SecurityMockMvcRequestPostProcessors.csrf()),worker)).andExpect(status().isConflict()).andReturn();
        var compose=ReactPageAssertions.envelope(conflict).at("/data/compose");assertEquals(1,compose.at("/day/inputRevision").asLong());assertEquals(0,compose.at("/correctionForm/baseInputRevision").asLong());assertEquals(command,compose.at("/correctionForm/commandId").asString());assertEquals("CLEAR",compose.at("/correctionForm/inAction").asString());
    }
    @Test void blankCorrectionDatePreservesItsDraftAndCommandInTheOpenModal() throws Exception {
        String command=UUID.randomUUID().toString();
        var response=mvc.perform(as(post("/me/requests/preview").param("year",String.valueOf(today.getYear())).param("kind","ATTENDANCE_CORRECTION")
                .param("inAction","CLEAR").param("outAction","CLEAR").param("reason","날짜를 다시 선택할 합성 입력").param("commandId",command)
                .with(SecurityMockMvcRequestPostProcessors.csrf()),worker)).andExpect(status().isUnprocessableEntity()).andReturn();
        var compose=ReactPageAssertions.envelope(response).at("/data/compose");assertEquals(command,compose.at("/correctionForm/commandId").asString());assertTrue(compose.at("/correctionForm/workDate").isNull());
        assertEquals("날짜를 다시 선택할 합성 입력",compose.at("/correctionForm/reason").asString());assertEquals("ATTENDANCE_CORRECTION",compose.path("kind").asString());
    }
    @Test void invalidComposeModeIsRejectedBeforeOtherwiseValidSubmissionCommits() throws Exception {
        var before=snapshot();
        mvc.perform(as(leavePost(today.plusDays(1),UUID.randomUUID().toString()).param("year",String.valueOf(today.getYear())).param("mode","INVALID"),worker)).andExpect(status().isUnprocessableEntity());
        assertEquals(before,snapshot());
    }
    @Test void resubmitEligibilityIncludesOnlyOwnAuthoredRejectedOrWithdrawnSupportedRequests() throws Exception {
        UUID own=raw("ATTENDANCE_CORRECTION","REJECTED",today,today,worker,worker,null,null,null);
        UUID delegated=raw("ATTENDANCE_CORRECTION","WITHDRAWN",today,today,worker,admin,null,null,null);
        var annual=annual(get("/me/requests"),worker);var ids=annual.path("canResubmitRequestIds");
        assertEquals(1,ids.size());assertEquals(own.toString(),ids.get(0).asString());assertFalse(ids.toString().contains(delegated.toString()));
    }
    @Test void finalEndpointRejectsAMismatchedKindWithoutCommittingARequest() throws Exception {
        var before=snapshot();mvc.perform(as(leavePost(today.plusDays(1),UUID.randomUUID().toString()).param("year",String.valueOf(today.getYear())).param("kind","ATTENDANCE_CORRECTION"),worker)).andExpect(status().isBadRequest());assertEquals(before,snapshot());
    }

    @Test void legacyLeaveWithoutDayRowsIsVisibleWithMissingDailyEvidence() throws Exception {
        raw("LEAVE_DAY", "PENDING", today, today, worker, worker, bucket, null, null);
        var home = mvc.perform(as(get("/me/home"), worker)).andExpect(status().isOk()).andReturn();
        assertEquals("종일 연차", ReactPageAssertions.envelope(home).at("/data/workspaceHome/recentRequests/0/kindLabel").asString());
        var response = mvc.perform(as(get("/me/schedule").param("date", today.toString()), worker))
                .andExpect(status().isOk()).andReturn();
        var item = ReactPageAssertions.envelope(response).at("/data/workspaceCalendar/selectedDay/requests/0");
        assertTrue(item.path("kindLabel").asString().contains("일별 근거 미확인"));
        assertFalse(item.path("effectiveLeave").asBoolean());
    }

    @Test void employeeAndAdministratorReadTheSameApprovedHalfDay() throws Exception {
        LocalDate date = today.plusDays(2);
        String year = String.valueOf(date.getYear());
        var command = UUID.randomUUID().toString();
        var preview = mvc.perform(as(post("/me/requests/preview").param("year", year)
                .param("kind", "LEAVE_DAY").param("leavePart", "PM").param("bucketId", bucket.toString())
                .param("from", date.toString()).param("to", date.toString()).param("reason", "합성 오후 반차")
                .param("commandId", command).with(SecurityMockMvcRequestPostProcessors.csrf()), worker))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0.5, ReactPageAssertions.envelope(preview).at("/data/compose/preview/quantityDays").asDouble());

        mvc.perform(as(leavePost(date, command).param("year", year).param("leavePart", "PM"), worker))
                .andExpect(status().isSeeOther());
        var pending = annual(get("/me/requests").param("year", year), worker);
        assertEquals(0.5, pending.path("pendingDays").asDouble());
        var request = pending.at("/requests/0");
        UUID id = UUID.fromString(request.path("id").asString());
        flow.decide(approver, id, new DecisionForm("APPROVE", request.path("version").asLong(), "합성 승인", UUID.randomUUID()));
        var approved = annual(get("/me/requests").param("year", year), worker);
        assertEquals(0.5, approved.path("plannedDays").asDouble());
        assertEquals(19.5, approved.path("currentAvailableDays").asDouble());
        assertEquals("PM", approved.at("/requests/0/leavePart").asString());
        var operations = mvc.perform(as(get("/admin/requests").param("scope", "LEAVE").param("status", "APPROVED"), admin))
                .andExpect(status().isOk()).andReturn();
        var row = ReactPageAssertions.envelope(operations).at("/data/leaveOperations/rows/0");
        assertEquals(0.5, row.path("quantityDays").asDouble());
        assertEquals("PM", row.path("leavePart").asString());
        reports.recalculate(admin, worker.employeeId(), date, date, UUID.randomUUID(), "합성 반차 집계");
        var day = reports.days(worker, worker.employeeId(), date, date).getFirst();
        assertTrue(day.current());
        assertEquals(0.5, day.approvedLeaveDays());
        assertEquals(0.5, day.result().leaveDays());
        assertNull(day.result().actualWorkMillis());
        var month = java.time.YearMonth.from(date);
        var calendarView = mvc.perform(as(get("/me/schedule").param("month", month.toString()).param("date", date.toString()), worker))
                .andExpect(status().isOk()).andReturn();
        assertTrue(ReactPageAssertions.envelope(calendarView).toString().contains("오후 반차"));
    }

    private JsonNode annual(MockHttpServletRequestBuilder r,TnaPrincipal actor)throws Exception{return ReactPageAssertions.envelope(mvc.perform(as(r,actor)).andExpect(status().isOk()).andReturn()).at("/data/annual");}
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r,TnaPrincipal a){return r.accept(PAGE).with(SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(a,null,a.roles().stream().map(x->new SimpleGrantedAuthority("ROLE_"+x)).toList())));}
    private MockHttpServletRequestBuilder leavePost(LocalDate date,String command){return post("/me/requests/leave").param("bucketId",bucket.toString()).param("from",date.toString()).param("to",date.toString()).param("reason","합성 신청").param("commandId",command).with(SecurityMockMvcRequestPostProcessors.csrf());}
    private List<Integer> snapshot(){return List.of("approval_request","approval_event","leave_ledger","org_audit_event","request_effect","day_input_revision").stream().map(t->db.queryForObject("SELECT count(*) FROM tna."+t+" WHERE tenant_id=?",Integer.class,tenant)).toList();}
    private UUID type(){return db.queryForObject("SELECT id FROM tna.attendance_type WHERE tenant_id=? AND system_kind='ANNUAL_LEAVE'",UUID.class,tenant);}
    private void attribution(long revision,String status,Integer year){db.update("INSERT INTO tna.leave_bucket_attribution(tenant_id,id,bucket_id,revision,attendance_type_id,classification_status,entitlement_year,basis,reason,created_by) VALUES (?,?,?,?,?,?,?,'합성 단일연도 근거','합성 분류',?)",tenant,UUID.randomUUID(),bucket,revision,type(),status,year,admin.accountId());}
    private void day(UUID request,LocalDate date,int quantity){db.update("INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,schedule_version_id,calendar_version_id,zero_reason) VALUES (?,?,?,?,?,?,?,?)",tenant,UUID.randomUUID(),request,date,quantity,schedule,calendar,quantity==0?"CONFIRMED_HOLIDAY":null);}
    private UUID raw(String kind,String status,LocalDate from,LocalDate to,TnaPrincipal target,TnaPrincipal author,UUID leaveBucket,UUID original,String effectStatus){
        UUID id=UUID.randomUUID();db.update("""
          INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,approver_assignment_id,approver_assignment_version,reason,original_request_id,leave_bucket_id,from_date,to_date,client_command_id,attendance_type_version_id)
          VALUES (?,?,?,?,?,?,?,?,0,'합성 과거 신청',?,?,?,?,?,(SELECT v.id FROM tna.attendance_type_version v JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id WHERE t.tenant_id=? AND t.system_kind=? AND v.revision=1))
          """,tenant,id,target.employeeId(),author.accountId(),kind,status,approver.employeeId(),binding,original,leaveBucket,from,to,UUID.randomUUID(),tenant,kind.equals("ATTENDANCE_CORRECTION")?"ATTENDANCE_CORRECTION":"ANNUAL_LEAVE");
        if(effectStatus!=null)db.update("INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,effect_status,created_by) VALUES (?,?,?,?,'LEAVE',?,?)",tenant,UUID.randomUUID(),id,target.employeeId(),effectStatus,approver.accountId());return id;
    }
    private TnaPrincipal member(Set<String> roles){UUID e=UUID.randomUUID(),a=UUID.randomUUID();db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 사원',?)",tenant,e,"E-"+e,today.minusYears(10));db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused-password-hash')",tenant,a,e,a+"@example.test");for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,a,role);return new TnaPrincipal(tenant,a,e,1,roles,Instant.now());}
}
