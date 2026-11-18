package kr.tna;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LeaveOperationsWebIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String PAGE_JSON = "application/vnd.worklog.page+json";
    private static final String PATH = "/admin/requests";
    private static final String PRIVATE_REASON = "출력 금지 합성 휴가 사유";

    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private UUID tenant, organization, assignment, bucket, schedule, calendar, originalVersion;
    private LocalDate today, firstDay;
    private Instant baseTime;
    private TnaPrincipal admin, worker, approver, actualDecider, representative;

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
    void syntheticCompany() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = LocalDate.now(SEOUL);
        firstDay = today.minusDays(10);
        baseTime = today.minusDays(5).atStartOfDay(SEOUL).toInstant();
        tenant = company();
        admin = member(tenant, "ADMIN", "ATTENDANCE_ADMIN");
        worker = member(tenant, "LV-001", null);
        approver = member(tenant, "APPROVER", "APPROVER");
        actualDecider = member(tenant, "HISTORICAL", "APPROVER");
        representative = member(tenant, "REP", "COMPANY_REPRESENTATIVE");
        organization = organization(tenant, admin.accountId());
        db.update("""
                INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by)
                VALUES (?,gen_random_uuid(),?,?,?,'합성 소속',?,?)
                """, tenant, worker.employeeId(), organization, firstDay, admin.accountId(), admin.accountId());
        assignment = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'합성 승인자',?,?)
                """, tenant, assignment, worker.employeeId(), approver.employeeId(), firstDay, admin.accountId(), admin.accountId());
        bucket = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,source_reference,created_by)
                VALUES (?,?,?,?,?,'합성 연차',?)
                """, tenant, bucket, worker.employeeId(), firstDay, firstDay, admin.accountId());
        originalVersion = typeVersion("ANNUAL_LEAVE");
        calendar = UUID.randomUUID();
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,?,?,'합성 달력',?)",
                tenant, calendar, firstDay, today.plusDays(15), admin.accountId());
        UUID template = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 일정',?,?)",
                tenant, template, template.toString(), admin.accountId(), admin.accountId());
        schedule = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 일정',?)",
                tenant, schedule, template, firstDay, admin.accountId());
    }

    @Test
    void redirectsLegacyAdminListAndKeepsCorrectionAndOtherRolesSeparated() throws Exception {
        UUID correction = request("ATTENDANCE_CORRECTION", "PENDING", today, today, 0, null);
        mvc.perform(get(PATH).with(as(admin)).accept(PAGE_JSON))
                .andExpect(status().isFound()).andExpect(redirectedUrl(PATH + "?scope=LEAVE"));
        mvc.perform(get(PATH).with(as(admin)).accept(PAGE_JSON).param("scope", "CORRECTION"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/requests/0/id", correction))
                .andExpect(jsonPath("$.data.leaveOperations").doesNotExist());
        mvc.perform(view(admin)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/leaveOperations/status", "PENDING"))
                .andExpect(ReactPageAssertions.data("/leaveOperations/asOf", today.toString()))
                .andExpect(jsonPath("$.data.leaveOperations.rows").isEmpty());
        mvc.perform(view(worker)).andExpect(status().isForbidden());
        mvc.perform(view(representative)).andExpect(status().isForbidden());
        mvc.perform(get("/company/requests").with(as(representative)).accept(PAGE_JSON).param("scope", "LEAVE"))
                .andExpect(status().isOk());
        mvc.perform(get(PATH).with(as(admin)).accept(PAGE_JSON).param("scope", "UNKNOWN")).andExpect(status().isBadRequest());
    }

    @Test
    void filtersAndCountsBeforePagingAndShowsPinnedNameWholeQuantityAndActualDecisionActor() throws Exception {
        UUID approved = request("LEAVE_DAY", "APPROVED", firstDay, firstDay.plusDays(1), 0, null);
        quantity(approved, firstDay);
        quantity(approved, firstDay.plusDays(1));
        UUID rejected = request("LEAVE_DAY", "REJECTED", firstDay.plusDays(1), firstDay.plusDays(2), 4, null);
        UUID olderPending = request("LEAVE_DAY", "PENDING", firstDay.plusDays(1), firstDay.plusDays(1), 1, null);
        request("LEAVE_DAY", "PENDING", firstDay.plusDays(1), firstDay.plusDays(1), 2, null);
        request("LEAVE_DAY", "WITHDRAWN", firstDay.plusDays(1), firstDay.plusDays(1), 3, null);
        request("LEAVE_DAY", "PENDING", today.plusDays(1), today.plusDays(1), 5, null);
        UUID newerDecision = request("LEAVE_DAY", "APPROVED", firstDay.plusDays(1), firstDay.plusDays(1), -1, null);
        request("LEAVE_DAY", "APPROVED", firstDay.plusDays(1), firstDay.plusDays(1), 6, null);
        decision(approved, "APPROVED", 30);
        decision(rejected, "REJECTED", 31);
        decision(newerDecision, "APPROVED", 32);
        inactivateAndRename();
        var pending = filters(view(admin)).param("status", "PENDING");
        mvc.perform(pending).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/leaveOperations/counts/PENDING", 2))
                .andExpect(ReactPageAssertions.data("/leaveOperations/counts/APPROVED", 3))
                .andExpect(ReactPageAssertions.data("/leaveOperations/counts/REJECTED", 1))
                .andExpect(ReactPageAssertions.data("/leaveOperations/counts/ALL", 7))
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/0/id", olderPending))
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/0/approverName", "합성 APPROVER"))
                .andExpect(ReactPageAssertions.data("/leaveOperations/leaveTypes/0/code", "ANNUAL_LEAVE"));
        var approvedResponse = mvc.perform(filters(view(admin)).param("status", "APPROVED")).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/0/id", newerDecision))
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/1/id", approved))
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/1/quantityDays", 2))
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/1/leaveTypeName", pinnedName()))
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/1/decidedByName", "합성 HISTORICAL"))
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/1/decidedAt", baseTime.plusSeconds(30).toString()))
                .andReturn();
        var approvedRows = ReactPageAssertions.envelope(approvedResponse).at("/data/leaveOperations/rows");
        assertTrue(approvedRows.get(2).get("decidedAt").isNull());
        assertFalse(approvedResponse.getResponse().getContentAsString().contains(PRIVATE_REASON));
        mvc.perform(filters(view(admin)).param("status", "REJECTED")).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/0/id", rejected));
        mvc.perform(filters(view(admin)).param("status", "ALL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.leaveOperations.rows[?(@.status == 'WITHDRAWN')]").isNotEmpty());
        String returnTo = PATH + "?scope=LEAVE&status=APPROVED&leaveTypeCode=ANNUAL_LEAVE&q=%ED%95%A9%EC%84%B1+LV&page=1";
        mvc.perform(get("/requests/{id}", approved).with(as(admin)).accept(PAGE_JSON).param("returnTo", returnTo))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/leaveReturnTo", returnTo))
                .andExpect(ReactPageAssertions.data("/leaveDecidedByName", "합성 HISTORICAL"));
        for (String unsafe : new String[]{"//external.example/admin/requests?scope=LEAVE",
                PATH + "?scope=CORRECTION", PATH + "?scope=LEAVE&status=APPROVED&page=-1",
                PATH + "?scope=LEAVE&q=one&q=two", PATH + "?scope=LEAVE&unknown=value"}) {
            mvc.perform(get("/requests/{id}", approved).with(as(admin)).accept(PAGE_JSON).param("returnTo", unsafe))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.leaveReturnTo").doesNotExist());
        }
        mvc.perform(get("/requests/{id}", approved).with(as(worker)).accept(PAGE_JSON).param("returnTo", returnTo))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.leaveDecidedByName").doesNotExist())
                .andExpect(jsonPath("$.data.leaveReturnTo").doesNotExist());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=?", Integer.class, tenant));
    }

    @Test
    void preservesOriginalLeaveEffectAndUsesOriginalQuantityForCancellation() throws Exception {
        UUID original = request("LEAVE_DAY", "APPROVED", firstDay, firstDay.plusDays(1), 0, null);
        quantity(original, firstDay);
        quantity(original, firstDay.plusDays(1));
        UUID effect = effect(original, "LEAVE", null);
        UUID cancellation = request("CANCEL_LEAVE", "PENDING", firstDay, firstDay.plusDays(1), 1, original);
        mvc.perform(view(admin).param("status", "APPROVED").param("requestKind", "LEAVE_DAY"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/leaveOperations/rows/0/effectStatus", "EFFECTIVE"));
        mvc.perform(view(admin).param("requestKind", "CANCEL_LEAVE"))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/0/originalRequestId", original))
                .andExpect(ReactPageAssertions.data("/leaveOperations/rows/0/quantityDays", 2));
        db.update("UPDATE tna.approval_request SET status='APPROVED' WHERE tenant_id=? AND id=?", tenant, cancellation);
        db.update("UPDATE tna.request_effect SET effect_status='CANCELED' WHERE tenant_id=? AND id=?", tenant, effect);
        effect(cancellation, "CANCEL", effect);
        mvc.perform(view(admin).param("status", "APPROVED").param("requestKind", "LEAVE_DAY"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/leaveOperations/rows/0/effectStatus", "CANCELED"));
        mvc.perform(view(admin).param("status", "APPROVED").param("requestKind", "CANCEL_LEAVE"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/leaveOperations/rows/0/effectStatus", "NONE"));
    }

    @Test
    void rejectsInvalidFiltersAndForeignOrganizationAndSupportsFiftyOneMatches() throws Exception {
        UUID foreign = company();
        var other = member(foreign, "OTHER", "ATTENDANCE_ADMIN");
        mvc.perform(view(admin).param("organizationId", organization(foreign, other.accountId()).toString()))
                .andExpect(status().isNotFound());
        for (String[] filter : new String[][]{{"from", "2026-02-30"}, {"from", firstDay.toString()},
                {"leaveTypeCode", "ATTENDANCE_CORRECTION"}, {"requestKind", "ATTENDANCE_CORRECTION"},
                {"status", "WITHDRAWN"}, {"page", "-1"}}) {
            mvc.perform(view(admin).param(filter[0], filter[1]))
                    .andExpect(status().isUnprocessableContent()).andExpect(ReactPageAssertions.dataPresent("/leaveOperations/error"));
        }
        for (int i = 0; i < 51; i++) request("LEAVE_DAY", "PENDING", today, today, i, null);
        mvc.perform(view(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.leaveOperations.rows.length()").value(50))
                .andExpect(ReactPageAssertions.data("/leaveOperations/counts/PENDING", 51))
                .andExpect(ReactPageAssertions.data("/leaveOperations/hasNext", true));
        mvc.perform(view(admin).param("page", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.leaveOperations.rows.length()").value(1))
                .andExpect(ReactPageAssertions.data("/leaveOperations/hasNext", false));
    }

    private MockHttpServletRequestBuilder filters(MockHttpServletRequestBuilder request) {
        return request.param("leaveTypeCode", "ANNUAL_LEAVE").param("requestKind", "LEAVE_DAY")
                .param("from", firstDay.plusDays(1).toString()).param("to", firstDay.plusDays(1).toString())
                .param("organizationId", organization.toString()).param("q", "lv-001");
    }

    private MockHttpServletRequestBuilder view(TnaPrincipal actor) {
        return get(PATH).with(as(actor)).accept(PAGE_JSON).param("scope", "LEAVE");
    }

    private UUID request(String kind, String status, LocalDate from, LocalDate to, long seconds, UUID original) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,
                  current_approver_id,approver_assignment_id,approver_assignment_version,submitted_at,reason,original_request_id,
                  leave_bucket_id,from_date,to_date,client_command_id,attendance_type_version_id)
                VALUES (?,?,?,?,?,?,?,?,0,?,?,?,?,?,?,gen_random_uuid(),?)
                """, tenant, id, worker.employeeId(), worker.accountId(), kind, status, approver.employeeId(), assignment,
                Timestamp.from(baseTime.plusSeconds(seconds)), PRIVATE_REASON, original,
                kind.equals("ATTENDANCE_CORRECTION") ? null : bucket, from, to,
                kind.equals("ATTENDANCE_CORRECTION") ? typeVersion(kind) : originalVersion);
        return id;
    }

    private UUID typeVersion(String kind) {
        return db.queryForObject("""
                SELECT v.id FROM tna.attendance_type_version v JOIN tna.attendance_type t
                ON t.tenant_id=v.tenant_id AND t.id=v.type_id WHERE t.tenant_id=? AND t.system_kind=? AND v.revision=1
                """, UUID.class, tenant, kind);
    }

    private void inactivateAndRename() {
        db.update("""
                INSERT INTO tna.attendance_type_version(tenant_id,id,type_id,revision,effective_from,enabled,name,unit,pay_kind,
                  deduction_rule,recognition_rule,basis,reason,created_by)
                SELECT tenant_id,gen_random_uuid(),type_id,2,?,false,'현재 변경 이름','DAY',pay_kind,
                  deduction_rule,recognition_rule,'합성 근거','합성 변경',? FROM tna.attendance_type_version WHERE tenant_id=? AND id=?
                """, today, admin.accountId(), tenant, originalVersion);
        db.update("UPDATE tna.attendance_type SET active=false WHERE tenant_id=? AND system_kind='ANNUAL_LEAVE'", tenant);
    }

    private String pinnedName() {
        return db.queryForObject("SELECT name FROM tna.attendance_type_version WHERE tenant_id=? AND id=?",
                String.class, tenant, originalVersion);
    }

    private void quantity(UUID id, LocalDate date) {
        db.update("""
                INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,schedule_version_id,calendar_version_id)
                VALUES (?,gen_random_uuid(),?,?,1,?,?)
                """, tenant, id, date, schedule, calendar);
    }

    private void decision(UUID id, String status, long seconds) {
        db.update("""
                INSERT INTO tna.approval_event(tenant_id,id,request_id,event_kind,actor_account_id,from_status,to_status,reason,created_at)
                VALUES (?,gen_random_uuid(),?,?,?,'PENDING',?,?,?)
                """, tenant, id, status, actualDecider.accountId(), status, PRIVATE_REASON, Timestamp.from(baseTime.plusSeconds(seconds)));
    }

    private UUID effect(UUID request, String kind, UUID reverses) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,reverses_effect_id,created_by)
                VALUES (?,?,?,?,?,?,?)
                """, tenant, id, request, worker.employeeId(), kind, reverses, actualDecider.accountId());
        return id;
    }

    private UUID company() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 휴가 회사')", id, "LV-OPS-" + id);
        return id;
    }

    private UUID organization(UUID company, UUID actor) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'HQ','합성 소속',?,?)",
                company, id, actor, actor);
        return id;
    }

    private TnaPrincipal member(UUID company, String number, String role) {
        UUID employee = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                company, employee, number, "합성 " + number, today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused')",
                company, account, employee, account + "@example.test");
        var roles = role == null ? Set.of("EMPLOYEE") : Set.of("EMPLOYEE", role);
        for (String value : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", company, account, value);
        return new TnaPrincipal(company, account, employee, 1, roles, Instant.now());
    }

    private static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
}
