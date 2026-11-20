package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class CompanyAccessWebIT {
    private static final String PASSWORD = "Synthetic company access password 43!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);
    @Autowired MockMvc mvc;
    private JdbcTemplate database;
    private UUID tenant;
    private String company, address;
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
    void syntheticCompany() {
        database = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        company = ("COMPANY-ACCESS-" + tenant).toUpperCase(Locale.ROOT);
        address = "198.19.1." + ADDRESS.getAndIncrement();
        database.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 권한 회사')",
                tenant, company);
        manager = member("ADMIN", "합성 관리자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    }

    @Test
    void representativeGrantAndRemovalRevokeSessionsWhileRetainingExplicitApprover() throws Exception {
        Member target = member("REP", "합성 대표", Set.of("EMPLOYEE", "APPROVER"));
        var administrator = login(manager);
        var old = login(target);
        mvc.perform(roles(administrator, target, 0, "EMPLOYEE", "APPROVER", "COMPANY_REPRESENTATIVE"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/admin/employees/" + target.employee() + "/access"));
        mvc.perform(get("/me/profile").session(old)).andExpect(redirectedUrl("/login"));
        var representative = login(target);
        mvc.perform(get("/me/profile").session(representative)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.roles("EMPLOYEE", "APPROVER", "COMPANY_REPRESENTATIVE"));
        mvc.perform(get("/admin/access").session(representative)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/employees/{id}/access", target.employee()).session(representative))
                .andExpect(status().isForbidden());
        mvc.perform(roles(representative, target, 1, "EMPLOYEE", "ATTENDANCE_ADMIN"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/approvals").session(representative)).andExpect(status().isOk());
        mvc.perform(roles(administrator, target, 1, "EMPLOYEE", "APPROVER"))
                .andExpect(status().isSeeOther());
        mvc.perform(get("/me/profile").session(representative)).andExpect(redirectedUrl("/login"));
        var fresh = login(target);
        mvc.perform(get("/me/profile").session(fresh)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.roles("EMPLOYEE", "APPROVER"));
        assertEquals(3, database.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=?",
                Long.class, tenant, target.account()));
    }

    @Test
    void representativeHasNoImplicitApprovalOrAdministrativeWrites() throws Exception {
        Member target = member("REP", "결재 권한 없는 대표", Set.of("EMPLOYEE"));
        var administrator = login(manager);
        mvc.perform(roles(administrator, target, 0, "EMPLOYEE", "COMPANY_REPRESENTATIVE"))
                .andExpect(status().isSeeOther());
        var representative = login(target);
        for (String path : List.of("/approvals", "/admin/access", "/admin/audit", "/admin/attendance")) {
            mvc.perform(get(path).session(representative)).andExpect(status().isForbidden());
        }
        for (String path : List.of("/company/attendance", "/company/requests", "/admin/calculations",
                "/admin/leave-balances", "/admin/work-policies", "/admin/approver-assignments")) {
            mvc.perform(post(path).session(representative).with(csrf())).andExpect(status().isForbidden());
        }
        var employee = login(member("COMMON", "공통 사원", Set.of("EMPLOYEE")));
        mvc.perform(get("/admin/access").session(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/company/attendance").session(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/company/requests").session(employee)).andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder roles(MockHttpSession session, Member target, long version, String... roles) {
        return post("/admin/employees/{id}/roles", target.employee()).session(session).with(csrf())
                .param("roles", roles).param("expectedVersion", Long.toString(version))
                .param("reason", "합성 대표 권한 변경").param("commandId", UUID.randomUUID().toString());
    }

    private MockHttpSession login(Member member) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).with(request -> {
                    request.setRemoteAddr(address); return request;
                }).param("companyCode", company).param("email", member.email()).param("password", PASSWORD))
                .andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);
    }

    private Member member(String number, String name, Set<String> roles) {
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
