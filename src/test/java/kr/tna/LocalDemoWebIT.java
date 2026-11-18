package kr.tna;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.TnaPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "local-demo"})
class LocalDemoWebIT {
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(LocalDemoLogin.PASSWORD);
    @Autowired MockMvc mvc;
    private JdbcTemplate admin;
    private String remoteAddress;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DatabaseFixture.start().jdbcUrl());
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", () -> DatabaseFixture.start().jdbcUrl());
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void syntheticDemoIdentity() {
        admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        remoteAddress = "198.51.100." + ADDRESS.getAndIncrement();
        admin.update("""
                INSERT INTO platform.tenant_registry (id, company_code, display_name, active)
                VALUES (?, ?, '로컬 데모', true)
                ON CONFLICT (id) DO UPDATE SET company_code = excluded.company_code, active = true
                """, LocalDemoLogin.TENANT_ID, LocalDemoLogin.COMPANY_CODE);
        admin.update("""
                INSERT INTO tna.employee (tenant_id, id, employee_no, display_name, joined_on, active)
                VALUES (?, ?, '99999', '로컬 데모 담당자', DATE '2026-01-01', true)
                ON CONFLICT (tenant_id, id) DO UPDATE SET employee_no=excluded.employee_no, active = true, left_on = NULL
                """, LocalDemoLogin.TENANT_ID, LocalDemoLogin.EMPLOYEE_ID);
        admin.update("""
                INSERT INTO tna.tenant_account
                    (tenant_id, id, employee_id, email, password_hash, active, auth_version)
                VALUES (?, ?, ?, ?, ?, true, 1)
                ON CONFLICT (tenant_id, id) DO UPDATE SET email = excluded.email,
                    password_hash = excluded.password_hash, active = true, auth_version = 1
                """, LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID, LocalDemoLogin.EMPLOYEE_ID,
                LocalDemoLogin.EMAIL, HASH);
        admin.update("DELETE FROM tna.role_grant WHERE tenant_id = ? AND account_id = ?",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID);
        for (String role : Set.of("EMPLOYEE", "ATTENDANCE_ADMIN")) {
            admin.update("INSERT INTO tna.role_grant (tenant_id, account_id, role) VALUES (?, ?, ?)",
                    LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID, role);
        }
        admin.update("""
                INSERT INTO tna.employee (tenant_id, id, employee_no, display_name, joined_on, active)
                VALUES (?, ?, '26001', '가상 승인자', DATE '2026-01-01', true)
                """, LocalDemoLogin.TENANT_ID, LocalDemoLogin.APPROVER_EMPLOYEE_ID);
        admin.update("""
                INSERT INTO tna.tenant_account
                    (tenant_id, id, employee_id, email, password_hash, active, auth_version)
                VALUES (?, ?, ?, ?, ?, true, 1)
                """, LocalDemoLogin.TENANT_ID, LocalDemoLogin.APPROVER_ACCOUNT_ID,
                LocalDemoLogin.APPROVER_EMPLOYEE_ID, LocalDemoLogin.APPROVER_EMAIL,
                PasswordPolicy.passwordEncoder().encode(LocalDemoLogin.APPROVER_PASSWORD));
        for (String role : Set.of("EMPLOYEE", "APPROVER")) {
            admin.update("INSERT INTO tna.role_grant (tenant_id, account_id, role) VALUES (?, ?, ?)",
                    LocalDemoLogin.TENANT_ID, LocalDemoLogin.APPROVER_ACCOUNT_ID, role);
        }
    }

    @AfterEach
    void removeOnlyThisSyntheticDemoIdentity() {
        if (admin == null) return;
        admin.update("UPDATE tna.employee SET created_by=NULL,updated_by=NULL WHERE tenant_id=?", LocalDemoLogin.TENANT_ID);
        for (String table : new String[] {"tna.request_form_version", "tna.request_form", "tna.attendance_type_version", "tna.attendance_type", "tna.common_code", "tna.common_code_group",
                "tna.org_audit_event", "tna.account_token", "tna.role_grant", "tna.tenant_account", "tna.employee"}) {
            admin.update("DELETE FROM " + table + " WHERE tenant_id = ?", LocalDemoLogin.TENANT_ID);
        }
        admin.update("DELETE FROM platform.tenant_registry WHERE id = ?", LocalDemoLogin.TENANT_ID);
    }

    @Test
    void landingPostsFixedDemoCredentialsWithCsrfWithoutEditableLoginFields() throws Exception {
        mvc.perform(get("/login")).andExpect(redirectedUrl("/demo"));
        mvc.perform(get("/demo")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"companyCode\""))))
                .andExpect(ReactPageAssertions.page())
                .andExpect(ReactPageAssertions.data("/demoLogin", "admin"))
                .andExpect(ReactPageAssertions.data("/demoPassword", "admin"))
                .andExpect(ReactPageAssertions.data("/demoApproverLogin", "approver"))
                .andExpect(ReactPageAssertions.data("/demoApproverPassword", "approver"))
                .andExpect(ReactPageAssertions.data("/demoAccounts/0/employeeNo", "99999"))
                .andExpect(ReactPageAssertions.data("/demoAccounts/1/employeeNo", "26001"))
                .andExpect(content().string(not(containsString("type=\"password\""))))
                .andExpect(ReactPageAssertions.csrf())
                .andExpect(content().string(not(anyOf(containsString("회사 코드와 이메일"),
                        containsString("href=\"/password-reset\""), containsString("초대 수락 후 로그인")))))
                .andExpect(ReactPageAssertions.page());
    }

    @Test
    void storedEmployeeNumbersAuthenticateWithoutChangingTheAccountAndMustMatchCurrentNumber() throws Exception {
        var session = (MockHttpSession) mvc.perform(credentials("99999", "admin").with(csrf())
                        .param("companyCode", DatabaseFixture.COMPANY_B))
                .andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);
        var principal = (TnaPrincipal) context(session).getAuthentication().getPrincipal();
        assertEquals(LocalDemoLogin.ACCOUNT_ID, principal.accountId());
        assertEquals(LocalDemoLogin.EMPLOYEE_ID, principal.employeeId());
        mvc.perform(credentials("26001", "approver").with(csrf())).andExpect(redirectedUrl("/approvals"));
        admin.update("UPDATE tna.employee SET employee_no='26009' WHERE tenant_id=? AND id=?",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.APPROVER_EMPLOYEE_ID);
        mvc.perform(credentials("26001", "approver").with(csrf())).andExpect(redirectedUrl("/login?error"));
        mvc.perform(credentials("26009", "approver").with(csrf())).andExpect(redirectedUrl("/approvals"));
    }

    @Test
    void credentialsUseActualDbPrincipalAndIgnoreClientTenantSelection() throws Exception {
        var before = new MockHttpSession();
        String oldId = before.getId();
        var session = (MockHttpSession) mvc.perform(credentials("admin", "admin").with(csrf()).session(before)
                        .with(request -> { request.setRequestedSessionId(oldId); request.setRequestedSessionIdValid(true); return request; })
                        .param("companyCode", DatabaseFixture.COMPANY_B)
                        .param("tenant_id", DatabaseFixture.TENANT_B.toString())
                        .header("X-Tenant-Id", DatabaseFixture.TENANT_B.toString()))
                .andExpect(redirectedUrl("/me/home"))
                .andReturn().getRequest().getSession(false);
        assertNotEquals(oldId, session.getId());
        var context = context(session);
        var principal = (TnaPrincipal) context.getAuthentication().getPrincipal();
        assertEquals(LocalDemoLogin.TENANT_ID, principal.tenantId());
        assertEquals(LocalDemoLogin.ACCOUNT_ID, principal.accountId());
        assertEquals(LocalDemoLogin.EMPLOYEE_ID, principal.employeeId());
        assertEquals(Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), principal.roles());
        assertNull(context.getAuthentication().getCredentials());
        mvc.perform(get("/me/profile").session(session).param("tenant_id", DatabaseFixture.TENANT_B.toString()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("로컬 데모 담당자")));
        mvc.perform(get("/operator/tenants").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/approvals").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void onlyExactPublicDemoCredentialsAreAccepted() throws Exception {
        for (String[] pair : new String[][]{{"other", "admin"}, {"ADMIN", "admin"},
                {LocalDemoLogin.EMAIL, "admin"}, {"admin", "wrong"}, {"admin", " admin "}}) {
            mvc.perform(credentials(pair[0], pair[1]).with(csrf()))
                    .andExpect(redirectedUrl("/login?error"));
        }
    }

    @Test
    void approverUsesNormalLoginAndCannotEnterAdministratorOrOtherTenantPages() throws Exception {
        var session = (MockHttpSession) mvc.perform(credentials("approver", "approver").with(csrf())
                        .param("companyCode", DatabaseFixture.COMPANY_B))
                .andExpect(redirectedUrl("/approvals")).andReturn().getRequest().getSession(false);
        var principal = (TnaPrincipal) context(session).getAuthentication().getPrincipal();
        assertEquals(LocalDemoLogin.TENANT_ID, principal.tenantId());
        assertEquals(LocalDemoLogin.APPROVER_ACCOUNT_ID, principal.accountId());
        assertEquals(LocalDemoLogin.APPROVER_EMPLOYEE_ID, principal.employeeId());
        assertEquals(Set.of("EMPLOYEE", "APPROVER"), principal.roles());
        mvc.perform(get("/approvals").session(session).param("tenant_id", DatabaseFixture.TENANT_B.toString()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/completed", false));
        mvc.perform(get("/approvals/completed").session(session)).andExpect(status().isOk());
        mvc.perform(get("/admin/employees").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/company/requests").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/operator/tenants").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void approverSwitchUsesCsrfLoginAndRotatesTheExistingApplicantSession() throws Exception {
        var before = login();
        String oldId = before.getId();
        mvc.perform(get("/demo").session(before)).andExpect(status().isOk());
        var switched = (MockHttpSession) mvc.perform(credentials("approver", "approver").session(before).with(csrf())
                        .with(request -> { request.setRequestedSessionId(oldId); request.setRequestedSessionIdValid(true); return request; }))
                .andExpect(redirectedUrl("/approvals")).andReturn().getRequest().getSession(false);
        assertNotEquals(oldId, switched.getId());
        var principal = (TnaPrincipal) context(switched).getAuthentication().getPrincipal();
        assertEquals(LocalDemoLogin.APPROVER_ACCOUNT_ID, principal.accountId());
        assertEquals(Set.of("EMPLOYEE", "APPROVER"), principal.roles());
        mvc.perform(get("/approvals").session(switched)).andExpect(status().isOk());
        mvc.perform(get("/admin/employees").session(switched)).andExpect(status().isForbidden());
        var applicant = (MockHttpSession) mvc.perform(credentials("admin", "admin").session(switched).with(csrf()))
                .andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);
        assertEquals(LocalDemoLogin.ACCOUNT_ID, ((TnaPrincipal) context(applicant).getAuthentication().getPrincipal()).accountId());
        mvc.perform(get("/approvals").session(applicant)).andExpect(status().isForbidden());
    }

    @Test
    void approverCredentialsStillRequireCsrfStoredHashAndCurrentExactRoles() throws Exception {
        mvc.perform(credentials("approver", "approver")).andExpect(status().isForbidden());
        var session = (MockHttpSession) mvc.perform(credentials("approver", "approver").with(csrf()))
                .andExpect(redirectedUrl("/approvals")).andReturn().getRequest().getSession(false);
        admin.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='APPROVER'",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.APPROVER_ACCOUNT_ID);
        mvc.perform(get("/approvals").session(session).header("HX-Request", "true"))
                .andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
        mvc.perform(credentials("approver", "approver").with(csrf())).andExpect(redirectedUrl("/login?error"));
        admin.update("UPDATE tna.role_grant SET active=true WHERE tenant_id=? AND account_id=? AND role='APPROVER'",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.APPROVER_ACCOUNT_ID);
        admin.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES(?,?,'ATTENDANCE_ADMIN')",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.APPROVER_ACCOUNT_ID);
        mvc.perform(credentials("approver", "approver").with(csrf())).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void storedHashAndAccountPresenceAreRequired() throws Exception {
        admin.update("UPDATE tna.tenant_account SET password_hash = ? WHERE tenant_id = ? AND id = ?",
                PasswordPolicy.passwordEncoder().encode("different synthetic password"),
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID);
        mvc.perform(credentials("admin", "admin").with(csrf())).andExpect(redirectedUrl("/login?error"));
        admin.update("UPDATE tna.tenant_account SET password_hash = ?, email = 'unmapped@local.example.test' WHERE tenant_id = ? AND id = ?",
                HASH, LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID);
        mvc.perform(credentials("admin", "admin").with(csrf())).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void inactiveAccountAndTenantCannotAuthenticate() throws Exception {
        admin.update("UPDATE tna.tenant_account SET active = false WHERE tenant_id = ? AND id = ?",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID);
        mvc.perform(credentials("admin", "admin").with(csrf())).andExpect(redirectedUrl("/login?error"));
        admin.update("UPDATE tna.tenant_account SET active = true WHERE tenant_id = ? AND id = ?",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID);
        admin.update("UPDATE platform.tenant_registry SET active = false WHERE id = ?", LocalDemoLogin.TENANT_ID);
        mvc.perform(credentials("admin", "admin").with(csrf())).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void roleRevocationInvalidatesExistingSessionAndRejectsNewLogin() throws Exception {
        var session = login();
        admin.update("UPDATE tna.role_grant SET active = false WHERE tenant_id = ? AND account_id = ?",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID);
        mvc.perform(get("/me/today/status").session(session).header("HX-Request", "true"))
                .andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
        mvc.perform(credentials("admin", "admin").with(csrf())).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void partialAdminRevocationInvalidatesDemoSessionAndRejectsNewLogin() throws Exception {
        var session = login();
        admin.update("UPDATE tna.role_grant SET active = false WHERE tenant_id = ? AND account_id = ? AND role = 'ATTENDANCE_ADMIN'",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID);
        mvc.perform(get("/me/today/status").session(session).header("HX-Request", "true"))
                .andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
        mvc.perform(credentials("admin", "admin").with(csrf())).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void addedApproverRoleInvalidatesDemoSessionAndRejectsNewLogin() throws Exception {
        var session = login();
        admin.update("INSERT INTO tna.role_grant (tenant_id, account_id, role) VALUES (?, ?, 'APPROVER')",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID);
        mvc.perform(get("/me/today/status").session(session).header("HX-Request", "true"))
                .andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
        mvc.perform(credentials("admin", "admin").with(csrf())).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void loginAndLogoutStillRequireCsrf() throws Exception {
        mvc.perform(credentials("admin", "admin")).andExpect(status().isForbidden());
        var session = login();
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/login?logout"));
        assertTrue(session.isInvalid());
    }

    @Test
    void changingClientCompanyAndIdCannotEvadeDemoRateLimit() throws Exception {
        for (int index = 0; index < 20; index++) {
            mvc.perform(credentials("unknown-" + index, "wrong").with(csrf())
                            .param("companyCode", "company-" + index))
                    .andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(credentials("admin", "admin").with(csrf())
                        .header("X-Forwarded-For", "203.0.113.50"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    private MockHttpServletRequestBuilder credentials(String login, String password) {
        return post("/login").with(request -> { request.setRemoteAddr(remoteAddress); return request; })
                .param("email", login).param("password", password);
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(credentials("admin", "admin").with(csrf()))
                .andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);
    }

    private SecurityContext context(MockHttpSession session) {
        return (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    }
}
