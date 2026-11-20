package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.tna.common.auth.PasswordPolicy;
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

@SpringBootTest
@AutoConfigureMockMvc
class RoleDirectoryWebIT {
    private static final String PASSWORD = "Synthetic role directory password 43!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    private static final String DIRECTORY = "/admin/access";
    private static final String CANDIDATES = "/admin/access/roles/{role}/candidates";
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);
    @Autowired MockMvc mvc;
    private JdbcTemplate database;
    private UUID tenant;
    private String company;
    private Member manager;

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
    void company() {
        database = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        company = ("ROLE-DIRECTORY-" + tenant).toUpperCase(Locale.ROOT);
        database.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 권한 회사')",
                tenant, company);
        manager = member("ADMIN", "합성 담당자", "EMPLOYEE", "ATTENDANCE_ADMIN");
    }

    @Test
    void defaultSelectionAndCountsIncludeOnlyGrantedRowsFromTheAuthenticatedCompany() throws Exception {
        var approver = member("APPROVER-1", "결재 권한 사원", "EMPLOYEE", "APPROVER");
        var retired = member("APPROVER-2", "퇴직 결재 사원", "EMPLOYEE", "APPROVER");
        database.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?",
                today().minusDays(1), tenant, retired.employee());
        var old = member("APPROVER-3", "회수한 결재 사원", "EMPLOYEE", "APPROVER");
        database.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='APPROVER'",
                tenant, old.account());
        var session = login(manager);

        var defaultResult = mvc.perform(get(DIRECTORY).session(session)
                        .param("tenantId", DatabaseFixture.TENANT_B.toString())
                        .header("X-Tenant-Id", DatabaseFixture.TENANT_B.toString())
                        .accept("application/vnd.worklog.page+json"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page()).andExpect(ReactPageAssertions.csrf())
                .andExpect(ReactPageAssertions.data("/roleDirectory/role", "ATTENDANCE_ADMIN"))
                .andExpect(ReactPageAssertions.data("/roleDirectory/actorEmployeeId", manager.employee().toString()))
                .andExpect(ReactPageAssertions.data("/roleDirectory/total", 1))
                .andExpect(ReactPageAssertions.data("/roleDirectory/members/0/employeeId", manager.employee().toString()))
                .andReturn();
        var roles = ReactPageAssertions.envelope(defaultResult).at("/data/roleDirectory/roles");
        assertEquals(4, roles.size());
        var employeeRole = findRole(roles, "EMPLOYEE");
        assertFalse(employeeRole.get("editable").asBoolean());
        assertEquals(4, employeeRole.get("memberCount").asInt());
        assertEquals(2, findRole(roles, "APPROVER").get("memberCount").asInt());

        var result = mvc.perform(get(DIRECTORY).session(session).param("role", "APPROVER"))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/roleDirectory/total", 2))
                .andExpect(ReactPageAssertions.data("/roleDirectory/members/0/employeeId", approver.employee().toString()))
                .andExpect(ReactPageAssertions.data("/roleDirectory/members/0/available", true))
                .andExpect(ReactPageAssertions.data("/roleDirectory/members/0/editable", true))
                .andExpect(ReactPageAssertions.data("/roleDirectory/members/1/available", false))
                .andExpect(ReactPageAssertions.data("/roleDirectory/members/1/editable", false))
                .andExpect(ReactPageAssertions.data("/roleDirectory/members/1/restriction", "퇴직"))
                .andReturn();
        var row = ReactPageAssertions.envelope(result).at("/data/roleDirectory/members/0");
        assertEquals(8, row.size());
        assertFalse(result.getResponse().getContentAsString().contains(approver.email()));
        assertFalse(result.getResponse().getContentAsString().contains(approver.account().toString()));
    }

    @Test
    void searchesCountsAndPagesTheWholeSelectedRoleAndUsesTodaysOrganization() throws Exception {
        for (int index = 0; index < 51; index++) member("P%03d".formatted(index), "페이지 사원", "EMPLOYEE", "APPROVER");
        var literal = member("LITERAL_%", "문자 검색 사원", "EMPLOYEE", "APPROVER");
        assignment(literal, "이전 조직", today().minusDays(5), today());
        assignment(literal, "오늘 조직", today(), today().plusDays(1));
        assignment(literal, "미래 조직", today().plusDays(1), null);

        var session = login(manager);
        var first = mvc.perform(get(DIRECTORY).session(session).param("role", "APPROVER").param("q", "  페이지  "))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/roleDirectory/q", "페이지"))
                .andExpect(ReactPageAssertions.data("/roleDirectory/hasNext", true))
                .andExpect(ReactPageAssertions.data("/roleDirectory/total", 51))
                .andReturn();
        assertEquals(50, ReactPageAssertions.envelope(first).at("/data/roleDirectory/members").size());
        var second = mvc.perform(get(DIRECTORY).session(session).param("role", "APPROVER")
                        .param("q", "페이지").param("page", "1"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/roleDirectory/hasNext", false))
                .andExpect(ReactPageAssertions.data("/roleDirectory/page", 1))
                .andExpect(ReactPageAssertions.data("/roleDirectory/members/0/employeeNo", "P050")).andReturn();
        assertEquals(1, ReactPageAssertions.envelope(second).at("/data/roleDirectory/members").size());
        mvc.perform(get(DIRECTORY).session(session).param("role", "APPROVER").param("q", "_%"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/roleDirectory/total", 1))
                .andExpect(ReactPageAssertions.data("/roleDirectory/members/0/organizationName", "오늘 조직"));
        mvc.perform(get(DIRECTORY).session(session).param("role", "EMPLOYEE").param("q", "_%"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/roleDirectory/members/0/editable", false))
                .andExpect(ReactPageAssertions.data("/roleDirectory/members/0/available", true));
        mvc.perform(get(DIRECTORY).session(session).param("page", "-1")).andExpect(status().is(422));
        mvc.perform(get(DIRECTORY).session(session).param("role", "UNKNOWN")).andExpect(status().is(422));
    }

    @Test
    void candidatesAreAuthorizedCurrentlyEligibleAndDoNotAlreadyHoldTheRole() throws Exception {
        var eligible = member("C-ELIGIBLE", "검색 가능", "EMPLOYEE");
        var pending = member("C-PENDING", "검색 대기", "EMPLOYEE");
        database.update("UPDATE tna.tenant_account SET active=false,password_hash=NULL WHERE tenant_id=? AND id=?",
                tenant, pending.account());
        var future = member("C-FUTURE", "검색 입사 전", "EMPLOYEE");
        database.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?",
                today().plusDays(1), tenant, future.employee());
        var holder = member("C-HOLDER", "검색 보유", "EMPLOYEE", "APPROVER");
        var rep = member("C-REP", "대표 단독", "EMPLOYEE", "COMPANY_REPRESENTATIVE");
        var session = login(manager);

        mvc.perform(get(CANDIDATES, "APPROVER").session(session).param("q", "검색")
                        .param("tenantId", DatabaseFixture.TENANT_B.toString()).accept("application/json"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].employeeId").value(eligible.employee().toString()))
                .andExpect(jsonPath("$.rows[0].editable").value(true))
                .andExpect(jsonPath("$.rows[0].version").value(0))
                .andExpect(jsonPath("$.hasNext").value(false)).andExpect(jsonPath("$.page").value(0));
        mvc.perform(get(CANDIDATES, "APPROVER").session(session).param("q", "C-ELIGIBLE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].employeeId").value(eligible.employee().toString()));
        mvc.perform(get(CANDIDATES, "EMPLOYEE").session(session)).andExpect(status().is(422));
        mvc.perform(get(CANDIDATES, "UNKNOWN").session(session)).andExpect(status().is(422));
        for (var unauthorized : new Member[]{eligible, holder, rep}) {
            var other = login(unauthorized);
            mvc.perform(get(DIRECTORY).session(other)).andExpect(status().isForbidden());
            mvc.perform(get(CANDIDATES, "APPROVER").session(other)).andExpect(status().isForbidden());
        }
        mvc.perform(get(CANDIDATES, "APPROVER").session(session).param("page", "-1"))
                .andExpect(status().is(422));
    }

    @Test
    void candidateFiltersApplyBeforeThePageLimit() throws Exception {
        var inactive = member("P-000-A", "후보 페이지", "EMPLOYEE");
        database.update("UPDATE tna.tenant_account SET active=false WHERE tenant_id=? AND id=?",
                tenant, inactive.account());
        member("P-000-B", "후보 페이지", "EMPLOYEE", "APPROVER");
        database.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on)
                SELECT ?,gen_random_uuid(),'P-' || lpad(n::text,3,'0'),'후보 페이지',?
                FROM generate_series(1,51) n
                """, tenant, today().minusYears(1));
        database.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash)
                SELECT tenant_id,gen_random_uuid(),id,id::text || '@example.test',?
                FROM tna.employee WHERE tenant_id=? AND employee_no BETWEEN 'P-001' AND 'P-051'
                """, HASH, tenant);

        var session = login(manager);
        mvc.perform(get(CANDIDATES, "APPROVER").session(session).param("q", "후보 페이지"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(50))
                .andExpect(jsonPath("$.rows[0].employeeNo").value("P-001"))
                .andExpect(jsonPath("$.rows[49].employeeNo").value("P-050"))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get(CANDIDATES, "APPROVER").session(session).param("q", "후보 페이지").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].employeeNo").value("P-051"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    private tools.jackson.databind.JsonNode findRole(tools.jackson.databind.JsonNode roles, String code) {
        for (var role : roles) if (code.equals(role.get("code").asString())) return role;
        return fail("권한이 없습니다 " + code);
    }

    private void assignment(Member member, String name, LocalDate from, LocalDate to) {
        var organization = UUID.randomUUID();
        database.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,?,?,?)",
                tenant, organization, organization.toString(), name, manager.account(), manager.account());
        database.update("""
                INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,valid_to,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,?,'합성 발령',?,?)
                """, tenant, UUID.randomUUID(), member.employee(), organization, from, to, manager.account(), manager.account());
    }

    private MockHttpSession login(Member member) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).with(request -> {
                    request.setRemoteAddr("198.19.2." + ADDRESS.getAndIncrement()); return request;
                }).param("companyCode", company).param("email", member.email()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);
    }

    private Member member(String number, String name, String... roles) {
        UUID employee = UUID.randomUUID(), account = UUID.randomUUID();
        String email = account + "@example.test";
        database.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                tenant, employee, number, name, today().minusYears(1));
        database.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, employee, email, HASH);
        for (String role : roles) database.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",
                tenant, account, role);
        return new Member(employee, account, email);
    }

    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Seoul")); }
    private record Member(UUID employee, UUID account, String email) { }
}
