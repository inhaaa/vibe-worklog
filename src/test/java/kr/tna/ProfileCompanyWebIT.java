package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.workspace.ProfileCompanyMapper;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class ProfileCompanyWebIT {
    private static final String PAGE_JSON = "application/vnd.worklog.page+json";
    private static final String PRIVATE_REASON = "비공개 자유 사유 synthetic-sensitive-reason";
    @Autowired MockMvc mvc;
    @Autowired TenantTransactions transactions;
    @Autowired ProfileCompanyMapper mapper;
    private JdbcTemplate db;
    private UUID tenant, organization;
    private LocalDate today;
    private TnaPrincipal representative, employee, approver;

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
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        tenant = company("합성 회사 조회");
        representative = member(tenant, "REP", Set.of("EMPLOYEE"));
        employee = member(tenant, "E001", Set.of("EMPLOYEE"));
        approver = member(tenant, "APR", Set.of("EMPLOYEE", "APPROVER"));
        organization = organization(tenant, "CURRENT", representative.accountId());
        assign(employee, organization);
    }

    @Test
    void profileMapperResolvesSelfWithinTenantTransactionAndRejectsOtherEmployeeId() {
        transactions.execute(tenant, () -> {
            mapper.lock(tenant);
            assertEquals("E001", mapper.profile(tenant, employee.accountId(), employee.employeeId(), today).employeeNo());
            assertNull(mapper.profile(tenant, employee.accountId(), representative.employeeId(), today));
            assertEquals(Set.of("EMPLOYEE"), Set.copyOf(mapper.roles(tenant, employee.accountId())));
            return null;
        });
    }

    @Test
    void profileAlwaysUsesTheAuthenticatedEmployeeAndOnlySafeFields() throws Exception {
        var result = mvc.perform(get("/me/profile").with(as(employee)).accept(PAGE_JSON)
                        .param("employeeId", representative.employeeId().toString())
                        .param("tenantId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page())
                .andExpect(view().name("workspace/profile"))
                .andExpect(ReactPageAssertions.data("/profile/employeeNo", "E001"))
                .andExpect(ReactPageAssertions.data("/profile/companyName", "합성 회사 조회"))
                .andExpect(ReactPageAssertions.data("/profile/email", employee.accountId() + "@example.test"))
                .andExpect(ReactPageAssertions.data("/profile/organizationName", "조직 CURRENT"))
                .andExpect(ReactPageAssertions.data("/profile/approverEligible", false))
                .andReturn();
        var profile = ReactPageAssertions.envelope(result).at("/data/profile");
        assertTrue(profile.get("scheduleName").isNull());
        assertTrue(profile.get("approverName").isNull());
        var names = new HashSet<String>();
        profile.properties().forEach(property -> names.add(property.getKey()));
        assertEquals(Set.of("employeeNo", "employeeName", "companyName", "email", "joinedOn", "leftOn",
                "organizationName", "scheduleName", "approverName", "approverEligible", "roles", "asOf",
                "organizationHeadName", "organizationHeadEligible", "organizationHeadApproverEligible", "approvalPreview"), names);
    }

    @Test
    void profileShowsCurrentAssignmentsAndAnIneligibleApproverWithoutInventingAReplacement() throws Exception {
        UUID template = UUID.randomUUID(), version = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'현재 적용 근무',?,?)",
                tenant, template, template.toString(), representative.accountId(), representative.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,?,?)",
                tenant, version, template, today, PRIVATE_REASON, representative.accountId());
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?)",
                tenant, UUID.randomUUID(), employee.employeeId(), version, today, PRIVATE_REASON,
                representative.accountId(), representative.accountId());
        binding(employee);
        mvc.perform(get("/me/profile").with(as(employee)).accept(PAGE_JSON))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/profile/scheduleName", "현재 적용 근무"))
                .andExpect(ReactPageAssertions.data("/profile/approverName", "합성 APR"))
                .andExpect(ReactPageAssertions.data("/profile/approverEligible", true));
        db.update("UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?", tenant, approver.employeeId());
        var result = mvc.perform(get("/me/profile").with(as(employee)).accept(PAGE_JSON))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/profile/approverName", "합성 APR"))
                .andExpect(ReactPageAssertions.data("/profile/approverEligible", false)).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(PRIVATE_REASON));
    }

    @Test
    void ordinaryEmployeeCannotReadCompanyAttendanceOrRequests() throws Exception {
        for (String path : new String[]{"/company/attendance", "/company/requests"}) {
            mvc.perform(get(path).with(as(employee)).accept(PAGE_JSON)).andExpect(status().isForbidden());
        }
    }

    @Test
    void representativeReadsOnlySafeSelectedEmployeeDaysAndDoesNotWriteOrExposeStaleValues() throws Exception {
        grantRepresentative();
        staleResult();
        var result = mvc.perform(get("/company/attendance").with(as(representative)).accept(PAGE_JSON)
                        .param("employeeId", employee.employeeId().toString()).param("month", YearMonth.from(today).toString()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page())
                .andExpect(view().name("company/attendance"))
                .andExpect(ReactPageAssertions.data("/companyAttendance/selectedEmployee/employeeNo", "E001"))
                .andReturn();
        var attendance = ReactPageAssertions.envelope(result).at("/data/companyAttendance");
        assertEquals(today.lengthOfMonth(), attendance.get("days").size());
        for (var day : attendance.get("days")) {
            assertFalse(day.get("current").asBoolean());
            assertEquals("REVIEW_REQUIRED", day.get("status").asString());
            assertTrue(day.get("actualWorkMillis").isNull());
            assertTrue(day.get("scheduledMillis").isNull());
            assertTrue(day.get("leaveDays").isNull());
            assertEquals(6, day.size());
        }
        String body = result.getResponse().getContentAsString();
        for (String secret : new String[]{PRIVATE_REASON, "sourceFingerprint", "validatedIp", "reasonCodes", "inputRefs", "@example.test"}) {
            assertFalse(body.contains(secret), secret);
        }
        assertEquals(1, count("day_result"));
        assertEquals(1, count("calculation_run"));
        assertEquals(0, count("org_audit_event"));
        assertEquals(0, count("report_snapshot"));
    }

    @Test
    void foreignEmployeeAndOrganizationOrEmployeeOutsideTheOrganizationAreNotFound() throws Exception {
        grantRepresentative();
        UUID foreignTenant = company("다른 합성 회사");
        var foreign = member(foreignTenant, "OTHER", Set.of("EMPLOYEE"));
        UUID foreignOrganization = organization(foreignTenant, "FOREIGN", foreign.accountId());
        UUID anotherOrganization = organization(tenant, "ANOTHER", representative.accountId());
        mvc.perform(get("/company/attendance").with(as(representative)).accept(PAGE_JSON)
                        .param("employeeId", foreign.employeeId().toString())).andExpect(status().isNotFound());
        mvc.perform(get("/company/attendance").with(as(representative)).accept(PAGE_JSON)
                        .param("employeeId", employee.employeeId().toString())
                        .param("organizationId", anotherOrganization.toString())).andExpect(status().isNotFound());
        for (String path : new String[]{"/company/attendance", "/company/requests"}) {
            mvc.perform(get(path).with(as(representative)).accept(PAGE_JSON)
                            .param("organizationId", foreignOrganization.toString())).andExpect(status().isNotFound());
        }
    }

    @Test
    void employeeListUsesFiftyRowPagesAndTheCurrentOrganization() throws Exception {
        grantRepresentative();
        for (int index = 0; index < 51; index++) {
            var row = member(tenant, "PAGE-" + String.format("%03d", index), Set.of("EMPLOYEE"));
            assign(row, organization);
        }
        var first = mvc.perform(get("/company/attendance").with(as(representative)).accept(PAGE_JSON)
                        .param("q", "PAGE-").param("organizationId", organization.toString()))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/companyAttendance/hasNext", true)).andReturn();
        var data = ReactPageAssertions.envelope(first).at("/data/companyAttendance");
        assertEquals(50, data.get("employees").size());
        assertEquals("PAGE-000", data.at("/selectedEmployee/employeeNo").asString());
        var second = mvc.perform(get("/company/attendance").with(as(representative)).accept(PAGE_JSON)
                        .param("q", "PAGE-").param("organizationId", organization.toString()).param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/companyAttendance/hasNext", false)).andReturn();
        assertEquals(1, ReactPageAssertions.envelope(second).at("/data/companyAttendance/employees").size());
    }

    @Test
    void companyRequestsUseTargetYearStatusAndCurrentOrganizationWithoutSensitiveDetails() throws Exception {
        grantRepresentative();
        int year = today.getYear();
        UUID assignment = binding(employee);
        UUID overlapping = request(assignment, LocalDate.of(year - 1, 12, 31), LocalDate.of(year, 1, 2), "PENDING");
        request(assignment, LocalDate.of(year - 1, 5, 1), LocalDate.of(year - 1, 5, 2), "PENDING");
        request(assignment, LocalDate.of(year, 2, 1), LocalDate.of(year, 2, 2), "REJECTED");
        var result = mvc.perform(get("/company/requests").with(as(representative)).accept(PAGE_JSON)
                        .param("year", Integer.toString(year)).param("status", "PENDING")
                        .param("organizationId", organization.toString()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page())
                .andExpect(view().name("company/requests"))
                .andExpect(ReactPageAssertions.data("/companyRequests/requests/0/id", overlapping.toString())).andReturn();
        var requests = ReactPageAssertions.envelope(result).at("/data/companyRequests/requests");
        assertEquals(1, requests.size());
        assertEquals(11, requests.get(0).size());
        String body = result.getResponse().getContentAsString();
        for (String secret : new String[]{PRIVATE_REASON, "authorAccountId", "approverAssignmentId", "beforeSnapshot", "@example.test"}) {
            assertFalse(body.contains(secret), secret);
        }
        assertEquals(0, count("org_audit_event"));
    }

    @Test
    void companyRequestScopeFiltersBeforePaginationAndRejectsUnknownScope() throws Exception {
        grantRepresentative();
        UUID assignment = binding(employee);
        UUID leave = request(assignment, today, today, "PENDING");
        mvc.perform(get("/company/requests").with(as(representative)).accept(PAGE_JSON)
                        .param("scope", "LEAVE"))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/companyRequests/requests/0/id", leave.toString()));
        var correction = mvc.perform(get("/company/requests").with(as(representative)).accept(PAGE_JSON)
                        .param("scope", "CORRECTION")).andExpect(status().isOk()).andReturn();
        assertEquals(0, ReactPageAssertions.envelope(correction).at("/data/companyRequests/requests").size());
        mvc.perform(get("/company/requests").with(as(representative)).accept(PAGE_JSON)
                        .param("scope", "OTHER")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/requests").with(as(representative)).accept(PAGE_JSON)
                        .param("scope", "LEAVE")).andExpect(status().isForbidden());
    }

    @Test
    void invalidCompanyFiltersAreRejected() throws Exception {
        grantRepresentative();
        mvc.perform(get("/company/attendance").with(as(representative)).accept(PAGE_JSON)
                        .param("page", "-1")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/company/attendance").with(as(representative)).accept(PAGE_JSON)
                        .param("q", "x".repeat(101))).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/company/requests").with(as(representative)).accept(PAGE_JSON)
                        .param("year", "0")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/company/requests").with(as(representative)).accept(PAGE_JSON)
                        .param("status", "OTHER")).andExpect(status().isUnprocessableEntity());
    }

    private void grantRepresentative() {
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'COMPANY_REPRESENTATIVE')",
                tenant, representative.accountId());
        representative = new TnaPrincipal(tenant, representative.accountId(), representative.employeeId(), 1,
                Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE"), Instant.now());
    }

    private UUID company(String name) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,?)", id, "PROFILE-" + id, name);
        return id;
    }

    private TnaPrincipal member(UUID company, String number, Set<String> roles) {
        UUID person = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                company, person, number, "합성 " + number, today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                company, account, person, account + "@example.test", "synthetic-profile-hash");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", company, account, role);
        return new TnaPrincipal(company, account, person, 1, roles, Instant.now());
    }

    private UUID organization(UUID company, String code, UUID actor) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,?,?,?)",
                company, id, code, "조직 " + code, actor, actor);
        return id;
    }

    private void assign(TnaPrincipal person, UUID target) {
        db.update("INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?)",
                tenant, UUID.randomUUID(), person.employeeId(), target, today, PRIVATE_REASON,
                representative.accountId(), representative.accountId());
    }

    private UUID binding(TnaPrincipal person) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?)",
                tenant, id, person.employeeId(), approver.employeeId(), today, PRIVATE_REASON,
                representative.accountId(), representative.accountId());
        return id;
    }

    private UUID request(UUID assignment, LocalDate from, LocalDate to, String status) {
        UUID bucket = UUID.randomUUID(), id = UUID.randomUUID();
        db.update("INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,source_reference,created_by) VALUES (?,?,?,?,?,?,?)",
                tenant, bucket, employee.employeeId(), today.minusYears(2), today.minusYears(2), PRIVATE_REASON, representative.accountId());
        db.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,
                  approver_assignment_id,approver_assignment_version,reason,leave_bucket_id,from_date,to_date,client_command_id,attendance_type_version_id)
                VALUES (?,?,?,?,'LEAVE_DAY',?,?,?,0,?,?,?,?,?,
                  (SELECT v.id FROM tna.attendance_type_version v JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
                   WHERE t.tenant_id=? AND t.system_kind='ANNUAL_LEAVE' AND v.revision=1))
                """, tenant, id, employee.employeeId(), employee.accountId(), status, approver.employeeId(), assignment,
                PRIVATE_REASON, bucket, from, to, UUID.randomUUID(), tenant);
        return id;
    }

    private void staleResult() {
        UUID run = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version)
                VALUES (?,?,?,?,?,?,?,?,'CALC-1')
                """, tenant, run, employee.employeeId(), representative.accountId(), today, today, today, today);
        db.update("""
                INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
                  source_fingerprint,rule_version,status,scheduled_millis,actual_work_millis,leave_days,reason_codes,input_refs)
                VALUES (?,?,?,?,?,1,0,0,?,'CALC-1','CALCULATED',28800000,28800000,1,'[]','[]')
                """, tenant, UUID.randomUUID(), run, employee.employeeId(), today, "0".repeat(64));
    }

    private int count(String table) {
        return db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Integer.class, tenant);
    }

    private static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
}
