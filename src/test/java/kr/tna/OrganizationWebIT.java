package kr.tna;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.org.OrgData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OrganizationWebIT {
    private static final String PASSWORD = "Synthetic org password 43!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);
    private static final LocalDate JOINED_ON = LocalDate.of(2026, 1, 1);

    @Autowired MockMvc mvc;
    private JdbcTemplate admin;
    private String remoteAddress;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var db = DatabaseFixture.start();
        properties.add("spring.datasource.url", db::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", db::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void identities() {
        DatabaseFixture.start().seedIdentity(HASH);
        admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        grantAdmin(DatabaseFixture.TENANT_A, DatabaseFixture.ACCOUNT_A);
        remoteAddress = "198.51.100." + ADDRESS.getAndIncrement();
    }

    @Test
    void attendanceAdminCanOpenOrganizationManagement() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        mvc.perform(get("/admin/organizations").session(session))
                .andExpect(status().isOk()).andExpect(view().name("org/organizations"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(ReactPageAssertions.csrf())
                .andExpect(inputAttribute("active", "checked", null));
        mvc.perform(get("/admin/employees/bulk-edit").session(session))
                .andExpect(status().isOk()).andExpect(view().name("org/employees"))
                .andExpect(inputAttribute("active", "checked", null))
                .andExpect(ReactPageAssertions.page());
        mvc.perform(get("/me/profile").session(session))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.page());
    }

    @Test
    void approvalUnitCanBeSetClearedOrPreservedByOlderClients() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        UUID id = createOrganization(session, "결재 단위 조직");
        String code = admin.queryForObject("SELECT code FROM tna.organization WHERE tenant_id=? AND id=?",
                String.class, DatabaseFixture.TENANT_A, id);
        mvc.perform(organizationChange(session, id, code, "팀", 0).param("approvalUnit", "TEAM"))
                .andExpect(status().isSeeOther());
        mvc.perform(organizationChange(session, id, code, "새 팀 이름", 1))
                .andExpect(status().isSeeOther());
        mvc.perform(get("/admin/organizations/{id}", id).session(session))
                .andExpect(ReactPageAssertions.data("/form/approvalUnit", "TEAM"));
        mvc.perform(organizationChange(session, id, code, "팀", 2).param("approvalUnit", "INVALID"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(organizationChange(session, id, code, "팀", 2).param("approvalUnit", "UNASSIGNED"))
                .andExpect(status().isSeeOther());
        assertNull(admin.queryForObject("SELECT approval_unit FROM tna.organization WHERE tenant_id=? AND id=?",
                String.class, DatabaseFixture.TENANT_A, id));
    }

    @Test
    void organizationCreateAndChangeRedirectToDetailAndRejectStaleVersion() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        UUID id = createOrganization(session, "처음 조직");
        var detail = mvc.perform(get("/admin/organizations/{id}", id).session(session))
                .andExpect(status().isOk()).andExpect(view().name("org/organization"))
                .andExpect(content().string(containsString("처음 조직")))
                .andExpect(inputAttribute("code", "readonly", null))
                .andReturn();
        var original = (OrgData.Organization) detail.getModelAndView().getModel().get("organization");
        assertEquals(0, original.version());
        String code = original.code();
        assertTrue(code.matches(today().getYear() + "[0-9]{4}"));

        mvc.perform(organizationChange(session, id, code, "변경 조직", original.version()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/admin/organizations/" + id));
        mvc.perform(organizationChange(session, id, code, "뒤늦은 변경", original.version()))
                .andExpect(status().isConflict()).andExpect(view().name("org/organization"))
                .andExpect(ReactPageAssertions.dataPresent("/error"))
                .andExpect(inputAttribute("name", "value", "뒤늦은 변경"));
        mvc.perform(get("/admin/organizations/{id}", id).session(session))
                .andExpect(status().isOk())
                .andExpect(inputAttribute("name", "value", "변경 조직"))
                .andExpect(inputAttribute("expectedVersion", "value", "1"));
    }

    @Test
    void employeeCreateAndChangeRenderDatesAndCreateAPendingAccountAndRejectStaleVersion() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        String employeeNo = uniqueCode();
        UUID id = createEmployee(session, employeeNo, "등록 사원");
        mvc.perform(get("/admin/employees/{id}", id).session(session))
                .andExpect(status().isOk()).andExpect(view().name("org/employee"))
                .andExpect(inputAttribute("employeeNo", "readonly", null))
                .andExpect(inputAttribute("joinedOn", "value", JOINED_ON.toString()));
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?",
                Integer.class, DatabaseFixture.TENANT_A, id).intValue());
        LocalDate lastDay = today().plusDays(30);
        mvc.perform(employeeChange(session, id, employeeNo, "이름 변경", 0).param("leftOn", lastDay.toString()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/admin/employees/" + id));
        mvc.perform(employeeChange(session, id, employeeNo, "뒤늦은 이름", 0))
                .andExpect(status().isConflict()).andExpect(view().name("org/employee"))
                .andExpect(inputAttribute("displayName", "value", "뒤늦은 이름"));
        mvc.perform(get("/admin/employees/{id}", id).session(session))
                .andExpect(status().isOk())
                .andExpect(inputAttribute("displayName", "value", "이름 변경"))
                .andExpect(inputAttribute("leftOn", "value", lastDay.toString()));
    }

    @Test
    void organizationValidationPreservesInputAndStoredNameIsEscaped() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        String name = "<script>alert(1)</script>";
        UUID command = UUID.randomUUID();
        var invalid = mvc.perform(post("/admin/organizations").session(session).with(csrf())
                        .param("name", name).param("active", "true")
                        .param("reason", " ").param("commandId", command.toString()))
                .andExpect(status().is(422)).andExpect(view().name("org/organizations"))
                .andExpect(ReactPageAssertions.dataPresent("/error"))
                .andExpect(content().string(containsString("\\u003cscript\\u003ealert(1)\\u003c/script\\u003e")))
                .andExpect(content().string(not(containsString(name)))).andReturn();
        var preserved = (OrgData.OrganizationForm) invalid.getModelAndView().getModel().get("form");
        assertEquals(name, preserved.name());
        assertEquals(command, preserved.commandId());
        UUID id = createOrganization(session, name);
        String code = organizationCode(id);
        for (String path : List.of("/admin/organizations/" + id, "/admin/organizations")) {
            mvc.perform(get(path).session(session).param("q", code))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\\u003cscript\\u003ealert(1)\\u003c/script\\u003e")))
                    .andExpect(content().string(not(containsString(name))));
        }
    }

    @Test
    void employeeValidationPreservesNameAndDatesInFullForm() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        String name = "<img src=x onerror=alert(1)>";
        UUID command = UUID.randomUUID();
        var invalid = mvc.perform(employeePost(session, uniqueCode(), name, command)
                        .param("leftOn", JOINED_ON.minusDays(1).toString()))
                .andExpect(status().is(422)).andExpect(view().name("org/employees"))
                .andExpect(ReactPageAssertions.dataPresent("/error"))
                .andExpect(inputAttribute("joinedOn", "value", JOINED_ON.toString()))
                .andExpect(inputAttribute("leftOn", "value", JOINED_ON.minusDays(1).toString()))
                .andExpect(content().string(containsString("\\u003cimg src=x onerror=alert(1)\\u003e")))
                .andExpect(content().string(not(containsString(name)))).andReturn();
        var form = (OrgData.EmployeeForm) invalid.getModelAndView().getModel().get("form");
        assertEquals(name, form.displayName());
        assertEquals(command, form.commandId());
        assertTrue(form.active());
    }

    @Test
    void everyMutationRequiresCsrf() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        UUID employee = createEmployee(session, uniqueCode(), "CSRF 확인");
        UUID organization = createOrganization(session, "CSRF 조직");
        for (String path : List.of("/admin/organizations", "/admin/organizations/" + organization + "/changes",
                "/admin/employees", "/admin/employees/" + employee + "/changes",
                "/admin/employees/" + employee + "/assignments")) {
            mvc.perform(post(path).session(session)).andExpect(status().isForbidden());
        }
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM tna.employee_assignment WHERE tenant_id=? AND employee_id=?",
                Integer.class, DatabaseFixture.TENANT_A, employee).intValue());
    }

    @Test
    void ordinaryEmployeeCannotAccessManagementOrSeeManagementNavigation() throws Exception {
        var session = login(DatabaseFixture.COMPANY_B);
        for (String path : List.of("/admin/organizations", "/admin/employees",
                "/admin/organizations/" + UUID.randomUUID(), "/admin/employees/" + DatabaseFixture.EMPLOYEE_B)) {
            mvc.perform(get(path).session(session)).andExpect(status().isForbidden());
        }
        mvc.perform(organizationPost(session, "권한 없는 등록", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        mvc.perform(employeePost(session, uniqueCode(), "권한 없는 사원", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/me/profile").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"/admin/organizations\""))))
                .andExpect(content().string(not(containsString("href=\"/admin/employees\""))));
    }

    @Test
    void authenticatedTenantControlsIdsParentAndAssignmentDespiteForgedTenantValues() throws Exception {
        grantAdmin(DatabaseFixture.TENANT_B, DatabaseFixture.ACCOUNT_B);
        var companyA = login(DatabaseFixture.COMPANY_A);
        var companyB = login(DatabaseFixture.COMPANY_B);
        UUID foreignOrganization = createOrganization(companyB, "다른 고객사 전용 조직");
        UUID employee = createEmployee(companyA, uniqueCode(), "소속 검증 사원");
        mvc.perform(get("/admin/organizations/{id}", foreignOrganization).session(companyA)
                        .param("tenant_id", DatabaseFixture.TENANT_B.toString())
                        .header("X-Tenant-Id", DatabaseFixture.TENANT_B.toString()))
                .andExpect(status().isNotFound()).andExpect(view().name("org/problem"))
                .andExpect(content().string(not(containsString("다른 고객사 전용 조직"))));
        mvc.perform(get("/admin/employees/{id}", DatabaseFixture.EMPLOYEE_B).session(companyA))
                .andExpect(status().isNotFound());
        mvc.perform(organizationPost(companyA, "외부 부모", UUID.randomUUID())
                        .param("parentId", foreignOrganization.toString())
                        .param("tenantId", DatabaseFixture.TENANT_B.toString()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/employees/{id}/assignments", employee).session(companyA).with(csrf())
                        .param("organizationId", foreignOrganization.toString()).param("validFrom", today().toString())
                        .param("expectedVersion", "0").param("commandId", UUID.randomUUID().toString()).param("reason", "외부 소속"))
                .andExpect(status().isNotFound());

        var forgedCreate = mvc.perform(organizationPost(companyA, "인증 고객사 조직", UUID.randomUUID())
                        .param("tenant_id", DatabaseFixture.TENANT_B.toString())
                        .param("tenantId", DatabaseFixture.TENANT_B.toString())
                        .header("X-Tenant-Id", DatabaseFixture.TENANT_B.toString()))
                .andExpect(status().isSeeOther()).andReturn();
        UUID ownId = redirectedId(forgedCreate);
        assertEquals(DatabaseFixture.TENANT_A, admin.queryForObject("SELECT tenant_id FROM tna.organization WHERE id=?", UUID.class, ownId));
        mvc.perform(get("/admin/organizations").session(companyA).param("q", "다른 고객사 전용 조직"))
                .andExpect(status().isOk()).andExpect(model().attribute("organizations", hasSize(0)));
    }

    @Test
    void malformedIdsAndDatesReturnSafeBadRequestPage() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        for (String path : List.of("/admin/organizations/not-a-uuid", "/admin/employees/not-a-uuid")) {
            mvc.perform(get(path).session(session))
                    .andExpect(status().isBadRequest()).andExpect(view().name("org/problem"))
                    .andExpect(model().attributeExists("traceId"))
                    .andExpect(content().string(not(containsString("java.lang"))))
                    .andExpect(content().string(not(containsString("jdbc:"))));
        }
        mvc.perform(organizationPost(session, "형식 확인", UUID.randomUUID()).param("parentId", "bad-id"))
                .andExpect(status().isBadRequest()).andExpect(view().name("org/problem"));
        mvc.perform(post("/admin/employees").session(session).with(csrf())
                        .param("employeeNo", uniqueCode()).param("displayName", "날짜 확인")
                        .param("joinedOn", "2026-99-99").param("active", "true")
                        .param("commandId", UUID.randomUUID().toString()).param("reason", "형식 검증"))
                .andExpect(status().isBadRequest()).andExpect(view().name("org/problem"));
        mvc.perform(post("/admin/organizations").session(session).with(csrf())
                        .param("code", uniqueCode()).param("name", "명령 확인").param("active", "true")
                        .param("commandId", "bad-command").param("reason", "형식 검증"))
                .andExpect(status().isBadRequest()).andExpect(view().name("org/problem"));
    }

    @Test
    void assignmentRedirectPreservesAdjacentHistoryAndRejectsSameStartWithInput() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        UUID first = createOrganization(session, "첫 소속");
        UUID next = createOrganization(session, "다음 소속");
        UUID employee = createEmployee(session, uniqueCode(), "이동 사원");
        LocalDate from = today();
        LocalDate moved = from.plusDays(10);
        mvc.perform(assignmentPost(session, employee, first, from, 0, "첫 등록"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/admin/employees/" + employee));
        mvc.perform(assignmentPost(session, employee, next, moved, 1, "정상 이동"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/admin/employees/" + employee));
        var detail = mvc.perform(get("/admin/employees/{id}", employee).session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("assignments", hasSize(2)))
                .andExpect(ReactPageAssertions.page())
                .andExpect(content().string(containsString("첫 등록")))
                .andExpect(content().string(containsString("정상 이동"))).andReturn();
        @SuppressWarnings("unchecked")
        var history = (List<OrgData.Assignment>) detail.getModelAndView().getModel().get("assignments");
        assertEquals(moved, history.get(0).validFrom());
        assertNull(history.get(0).validTo());
        assertEquals(from, history.get(1).validFrom());
        assertEquals(moved, history.get(1).validTo());
        var invalid = mvc.perform(assignmentPost(session, employee, first, moved, 2, "같은 날 재배정"))
                .andExpect(status().is(422)).andExpect(view().name("org/employee"))
                .andExpect(model().attribute("assignments", hasSize(2)))
                .andExpect(ReactPageAssertions.dataPresent("/error"))
                .andExpect(inputAttribute("validFrom", "value", moved.toString()))
                .andExpect(ReactPageAssertions.data("/assignmentForm/reason", "같은 날 재배정")).andReturn();
        var form = (OrgData.AssignmentForm) invalid.getModelAndView().getModel().get("assignmentForm");
        assertEquals(first, form.organizationId());
        assertEquals(2, form.expectedVersion().longValue());
    }

    @Test
    void successfulCommandRetryRedirectsToSameTargetAndChangedInputConflicts() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        UUID command = UUID.randomUUID();
        var first = mvc.perform(organizationPost(session, "재시도 조직", command))
                .andExpect(status().isSeeOther()).andReturn();
        String code = organizationCode(redirectedId(first));
        String target = first.getResponse().getRedirectedUrl();
        mvc.perform(organizationPost(session, "재시도 조직", command))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl(target));
        mvc.perform(organizationPost(session, "다른 요청", command))
                .andExpect(status().isConflict()).andExpect(view().name("org/organizations"))
                .andExpect(inputAttribute("name", "value", "다른 요청"));
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM tna.organization WHERE tenant_id=? AND code=?",
                Integer.class, DatabaseFixture.TENANT_A, code).intValue());
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                Integer.class, DatabaseFixture.TENANT_A, command).intValue());
    }

    @Test
    void newOrganizationRejectsClientCodeAndPreservesSingleCreateForm() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        var command = UUID.randomUUID();
        mvc.perform(organizationPost(session, "조직명 보존", command).param("code", "MANUAL"))
                .andExpect(status().is(422))
                .andExpect(ReactPageAssertions.data("/organizationCreateFailed", true))
                .andExpect(ReactPageAssertions.data("/form/name", "조직명 보존"));
        assertEquals(0, admin.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                Integer.class, DatabaseFixture.TENANT_A, command).intValue());
        mvc.perform(organizationPost(session, "조직명 보존", command).param("code", " "))
                .andExpect(status().isSeeOther());
    }

    @Test
    void searchAndPaginationKeepSearchTermAndLimitToFiftyRows() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        String query = "검색 " + UUID.randomUUID();
        for (int index = 0; index < 51; index++) {
            admin.update("""
                    INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by)
                    VALUES (?,?,?,?,?,?)
                    """, DatabaseFixture.TENANT_A, UUID.randomUUID(), uniqueCode(), query,
                    DatabaseFixture.ACCOUNT_A, DatabaseFixture.ACCOUNT_A);
        }
        mvc.perform(get("/admin/organizations").session(session).param("q", query))
                .andExpect(status().isOk()).andExpect(model().attribute("organizations", hasSize(50)))
                .andExpect(model().attribute("hasNext", true))
                .andExpect(ReactPageAssertions.data("/hasNext", true));
        mvc.perform(get("/admin/organizations").session(session).param("q", query).param("page", "1"))
                .andExpect(status().isOk()).andExpect(model().attribute("organizations", hasSize(1)))
                .andExpect(model().attribute("hasNext", false))
                .andExpect(inputAttribute("q", "value", query))
                .andExpect(ReactPageAssertions.data("/page", 1));
        String employeeNo = uniqueCode();
        createEmployee(session, employeeNo, "검색할 사원");
        mvc.perform(get("/admin/employees").session(session).param("q", employeeNo))
                .andExpect(status().isOk()).andExpect(model().attribute("employees", hasSize(1)))
                .andExpect(content().string(containsString("검색할 사원")));
    }

    @Test
    void inactiveChildKeepsSelectedInactiveParentDuringRenameAndCannotReactivate() throws Exception {
        var session = login(DatabaseFixture.COMPANY_A);
        UUID parent = createOrganization(session, "보관 부모 조직");
        UUID child = redirectedId(mvc.perform(organizationPost(session, "보관 하위 조직", UUID.randomUUID())
                        .param("parentId", parent.toString()))
                .andExpect(status().isSeeOther()).andReturn());
        String parentCode = organizationCode(parent), childCode = organizationCode(child);
        mvc.perform(organizationChange(session, child, childCode, "보관 하위 조직", 0, false)
                        .param("parentId", parent.toString()))
                .andExpect(status().isSeeOther());
        mvc.perform(organizationChange(session, parent, parentCode, "보관 부모 조직", 0, false))
                .andExpect(status().isSeeOther());
        mvc.perform(get("/admin/organizations/{id}", child).session(session))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.page())
                .andExpect(ReactPageAssertions.data("/form/parentId", parent));
        mvc.perform(organizationChange(session, child, childCode, "보관 이름 변경", 1, false)
                        .param("parentId", parent.toString()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/admin/organizations/" + child));
        var detail = mvc.perform(get("/admin/organizations/{id}", child).session(session))
                .andExpect(status().isOk()).andExpect(inputAttribute("name", "value", "보관 이름 변경")).andReturn();
        var organization = (OrgData.Organization) detail.getModelAndView().getModel().get("organization");
        assertEquals(parent, organization.parentId());
        assertFalse(organization.active());
        mvc.perform(organizationChange(session, child, childCode, "재활성화 시도", organization.version())
                        .param("parentId", parent.toString()))
                .andExpect(status().is(422)).andExpect(view().name("org/organization"))
                .andExpect(ReactPageAssertions.dataPresent("/error"));
    }

    private void grantAdmin(UUID tenant, UUID account) {
        admin.update("""
                INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')
                ON CONFLICT DO NOTHING
                """, tenant, account);
    }

    private MockHttpSession login(String company) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; })
                        .param("companyCode", company).param("email", DatabaseFixture.EMAIL).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/me/home"))
                .andReturn().getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder organizationPost(MockHttpSession session, String name, UUID command) {
        return post("/admin/organizations").session(session).with(csrf())
                .param("name", name).param("active", "true")
                .param("reason", "합성 자료 등록").param("commandId", command.toString());
    }

    private MockHttpServletRequestBuilder organizationChange(MockHttpSession session, UUID id, String code, String name, long version) {
        return organizationChange(session, id, code, name, version, true);
    }

    private MockHttpServletRequestBuilder organizationChange(MockHttpSession session, UUID id, String code,
            String name, long version, boolean active) {
        return post("/admin/organizations/{id}/changes", id).session(session).with(csrf())
                .param("code", code).param("name", name).param("active", Boolean.toString(active))
                .param("reason", "합성 자료 변경").param("expectedVersion", Long.toString(version))
                .param("commandId", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder employeePost(MockHttpSession session, String employeeNo, String name, UUID command) {
        return post("/admin/employees").session(session).with(csrf())
                .param("employeeNo", employeeNo).param("displayName", name).param("joinedOn", JOINED_ON.toString())
                .param("active", "true").param("reason", "합성 자료 등록").param("commandId", command.toString());
    }

    private MockHttpServletRequestBuilder employeeChange(MockHttpSession session, UUID id, String employeeNo, String name, long version) {
        return post("/admin/employees/{id}/changes", id).session(session).with(csrf())
                .param("employeeNo", employeeNo).param("displayName", name).param("joinedOn", JOINED_ON.toString())
                .param("active", "true").param("reason", "합성 자료 변경").param("expectedVersion", Long.toString(version))
                .param("commandId", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder assignmentPost(MockHttpSession session, UUID employee, UUID organization,
            LocalDate validFrom, long version, String reason) {
        return post("/admin/employees/{id}/assignments", employee).session(session).with(csrf())
                .param("organizationId", organization.toString()).param("validFrom", validFrom.toString())
                .param("expectedVersion", Long.toString(version)).param("reason", reason)
                .param("commandId", UUID.randomUUID().toString());
    }

    private UUID createOrganization(MockHttpSession session, String name) throws Exception {
        return redirectedId(mvc.perform(organizationPost(session, name, UUID.randomUUID()))
                .andExpect(status().isSeeOther()).andReturn());
    }

    private String organizationCode(UUID id) {
        return admin.queryForObject("SELECT code FROM tna.organization WHERE id=?", String.class, id);
    }

    private UUID createEmployee(MockHttpSession session, String employeeNo, String name) throws Exception {
        return redirectedId(mvc.perform(employeePost(session, employeeNo, name, UUID.randomUUID()))
                .andExpect(status().isSeeOther()).andReturn());
    }

    private UUID redirectedId(MvcResult result) {
        String target = result.getResponse().getRedirectedUrl();
        assertNotNull(target);
        return UUID.fromString(target.substring(target.lastIndexOf('/') + 1));
    }

    private ResultMatcher inputAttribute(String name, String attribute, String value) {
        return result -> {
            String pointer = name.equals("q") ? "/data/q"
                    : "/data/" + (name.equals("validFrom") ? "assignmentForm/" : "form/") + name;
            var actual = ReactPageAssertions.envelope(result).at(pointer);
            assertFalse(actual.isMissingNode(), name);
            if (attribute.equals("checked")) assertTrue(actual.asBoolean());
            else if (attribute.equals("value")) assertEquals(value, actual.asString());
        };
    }

    private String uniqueCode() { return UUID.randomUUID().toString(); }
    private LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Seoul")); }
}
