package kr.tna;

import jakarta.servlet.http.HttpSession;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.TnaPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityWebIT {
    private static final String PASSWORD = "테스트 암호 😀 ".repeat(8);
    private static final java.util.concurrent.atomic.AtomicInteger ADDRESS = new java.util.concurrent.atomic.AtomicInteger(1);
    private String remoteAddress;
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);

    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        DatabaseFixture.start();
        properties.add("spring.datasource.url", () -> DatabaseFixture.start().jdbcUrl());
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", () -> DatabaseFixture.start().jdbcUrl());
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void identities() {
        DatabaseFixture.start().seedIdentity(HASH);
        remoteAddress = "192.0.2." + ADDRESS.getAndIncrement();
    }

    @Test
    void loginHasCsrfAndLocalAssetsAndProtectedPagesRequireAuthentication() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"companyCode\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("/vendor/htmx.min.js")))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")));
        mvc.perform(get("/me/today")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/me/today/status").header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/login"));
        mvc.perform(post("/login").param("companyCode", DatabaseFixture.COMPANY_A)
                        .param("email", DatabaseFixture.EMAIL).param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void companyNamespaceAndAuthenticatedTenantControlTheResponse() throws Exception {
        MockHttpSession companyA = login(DatabaseFixture.COMPANY_A);
        MockHttpSession companyB = login(DatabaseFixture.COMPANY_B);
        mvc.perform(get("/me/today").session(companyA)
                        .param("tenant_id", DatabaseFixture.TENANT_B.toString())
                        .header("X-Tenant-Id", DatabaseFixture.TENANT_B.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("직원 A")))
                .andExpect(content().string(not(containsString("직원 B"))))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("historyCacheSize")))
                .andExpect(content().string(containsString("준비 중")));
        mvc.perform(get("/me/today").session(companyB))
                .andExpect(status().isOk()).andExpect(content().string(containsString("직원 B")));
        mvc.perform(get("/me/today/status").session(companyA).header("HX-Request", "true"))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("<!DOCTYPE"))));
        TnaPrincipal principal = principal(companyA);
        assertEquals(DatabaseFixture.TENANT_A, principal.tenantId());
        assertFalse(principal.toString().contains(HASH));
    }

    @Test
    void loginChangesSessionIdAndFailureDoesNotRevealCompanyOrAccount() throws Exception {
        MockHttpSession before = new MockHttpSession();
        String oldId = before.getId();
        mvc.perform(post("/login").session(before).with(csrf()).with(request -> { request.setRemoteAddr(remoteAddress); return request; })
                        .param("companyCode", DatabaseFixture.COMPANY_A)
                        .with(request -> { request.setRequestedSessionId(oldId); request.setRequestedSessionIdValid(true); return request; })
                        .param("email", DatabaseFixture.EMAIL).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/me/today"));
        assertNotEquals(oldId, before.getId());
        for (String company : new String[]{DatabaseFixture.COMPANY_A, "unknown-company"}) {
            mvc.perform(post("/login").with(csrf()).with(request -> { request.setRemoteAddr(remoteAddress); return request; }).param("companyCode", company)
                            .param("email", DatabaseFixture.EMAIL).param("password", "wrong password"))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?error"));
        }
    }

    @Test
    void revokedAccountAndRolesInvalidateSessions() throws Exception {
        MockHttpSession session = login(DatabaseFixture.COMPANY_A);
        var admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        admin.update("update tna.tenant_account set auth_version = auth_version + 1 where tenant_id = ? and id = ?",
                DatabaseFixture.TENANT_A, DatabaseFixture.ACCOUNT_A);
        mvc.perform(get("/me/today/status").session(session).header("HX-Request", "true"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("HX-Redirect", "/login"));
        assertTrue(session.isInvalid());

        MockHttpSession rolesSession = login(DatabaseFixture.COMPANY_A);
        admin.update("delete from tna.role_grant where tenant_id = ? and account_id = ?",
                DatabaseFixture.TENANT_A, DatabaseFixture.ACCOUNT_A);
        mvc.perform(get("/me/today").session(rolesSession)).andExpect(status().is3xxRedirection());
        assertTrue(rolesSession.isInvalid());
    }

    @Test
    void inactiveTenantAndDepartedEmployeeCannotKeepSessions() throws Exception {
        var admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        MockHttpSession tenantSession = login(DatabaseFixture.COMPANY_A);
        admin.update("update platform.tenant_registry set active = false where id = ?", DatabaseFixture.TENANT_A);
        mvc.perform(get("/me/today").session(tenantSession)).andExpect(status().is3xxRedirection());
        assertTrue(tenantSession.isInvalid());

        admin.update("update platform.tenant_registry set active = true where id = ?", DatabaseFixture.TENANT_A);
        MockHttpSession employeeSession = login(DatabaseFixture.COMPANY_A);
        admin.update("update tna.employee set left_on = ? where tenant_id = ? and id = ?",
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1),
                DatabaseFixture.TENANT_A, DatabaseFixture.EMPLOYEE_A);
        mvc.perform(get("/me/today").session(employeeSession)).andExpect(status().is3xxRedirection());
        assertTrue(employeeSession.isInvalid());
    }

    @Test
    void lastEmploymentDayIsInclusiveForLogin() throws Exception {
        var admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        admin.update("update tna.employee set left_on = ? where tenant_id = ? and id = ?",
                today, DatabaseFixture.TENANT_A, DatabaseFixture.EMPLOYEE_A);
        MockHttpSession lastDay = login(DatabaseFixture.COMPANY_A);
        mvc.perform(get("/me/today").session(lastDay)).andExpect(status().isOk());
        admin.update("update tna.employee set left_on = ? where tenant_id = ? and id = ?",
                today.minusDays(1), DatabaseFixture.TENANT_A, DatabaseFixture.EMPLOYEE_A);
        mvc.perform(get("/me/today").session(lastDay)).andExpect(status().is3xxRedirection());
        assertTrue(lastDay.isInvalid());
        mvc.perform(post("/login").with(csrf())
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; })
                        .param("companyCode", DatabaseFixture.COMPANY_A)
                        .param("email", DatabaseFixture.EMAIL).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void lastEmploymentDayKeepsAnExistingSessionValid() throws Exception {
        MockHttpSession session = login(DatabaseFixture.COMPANY_A);
        new JdbcTemplate(DatabaseFixture.start().adminDataSource()).update(
                "update tna.employee set left_on = ? where tenant_id = ? and id = ?",
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")),
                DatabaseFixture.TENANT_A, DatabaseFixture.EMPLOYEE_A);
        mvc.perform(get("/me/today").session(session)).andExpect(status().isOk());
    }

    @Test
    void companyCodesSupportTheFullSchemaLength() throws Exception {
        var admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        String company = "A".repeat(80);
        admin.update("update platform.tenant_registry set company_code = ? where id = ?",
                company, DatabaseFixture.TENANT_A);
        try {
            mvc.perform(get("/login")).andExpect(content().string(containsString("maxlength=\"80\"")));
            login(company);
        } finally {
            admin.update("update platform.tenant_registry set company_code = ? where id = ?",
                    DatabaseFixture.COMPANY_A, DatabaseFixture.TENANT_A);
        }
    }

    @Test
    void absoluteSessionLifetimeDoesNotExtendWithActivity() throws Exception {
        MockHttpSession session = login(DatabaseFixture.COMPANY_A);
        SecurityContext context = context(session);
        TnaPrincipal current = principal(session);
        TnaPrincipal expired = new TnaPrincipal(current.tenantId(), current.accountId(), current.employeeId(),
                current.authVersion(), current.roles(), Instant.now().minus(13, ChronoUnit.HOURS));
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(expired, null,
                context.getAuthentication().getAuthorities()));
        mvc.perform(get("/me/today/status").session(session).header("HX-Request", "true"))
                .andExpect(status().isUnauthorized());
        assertTrue(session.isInvalid());
    }

    @Test
    void operatorAndSensitiveActuatorEndpointsAreDeniedAndLogoutNeedsCsrf() throws Exception {
        MockHttpSession session = login(DatabaseFixture.COMPANY_A);
        mvc.perform(get("/operator/tenants").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("db"))));
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?logout"));
        assertTrue(session.isInvalid());
    }

    @Test
    void repeatedLoginAttemptsAreBoundedByObservedAddressAndAccount() throws Exception {
        for (int index = 0; index < 20; index++) {
            mvc.perform(post("/login").with(csrf())
                            .with(request -> { request.setRemoteAddr(remoteAddress); return request; })
                            .param("companyCode", DatabaseFixture.COMPANY_A)
                            .param("email", DatabaseFixture.EMAIL).param("password", "wrong password"))
                    .andExpect(status().is3xxRedirection());
        }
        mvc.perform(post("/login").with(csrf())
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; })
                        .header("X-Forwarded-For", "203.0.113.50")
                        .param("companyCode", DatabaseFixture.COMPANY_A)
                        .param("email", DatabaseFixture.EMAIL).param("password", PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    private MockHttpSession login(String companyCode) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).with(request -> { request.setRemoteAddr(remoteAddress); return request; })
                        .param("companyCode", companyCode).param("email", DatabaseFixture.EMAIL)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/me/today"))
                .andReturn().getRequest().getSession(false);
    }

    private TnaPrincipal principal(HttpSession session) {
        return (TnaPrincipal) context(session).getAuthentication().getPrincipal();
    }

    private SecurityContext context(HttpSession session) {
        return (SecurityContext) session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    }
}
