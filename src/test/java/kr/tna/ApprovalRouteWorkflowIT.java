package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import kr.tna.clock.ClockService;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.clock.AttendanceSettingsData.CalendarForm;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
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
    @Autowired AttendanceSettingsService settings;
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
        settings.calendar(admin,new CalendarForm(today,today.plusDays(20),true,"합성 검토 근거","","합성 달력",UUID.randomUUID()));
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
