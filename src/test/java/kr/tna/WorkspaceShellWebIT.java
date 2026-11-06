package kr.tna;

import java.util.concurrent.atomic.AtomicInteger;
import kr.tna.common.auth.PasswordPolicy;
import org.junit.jupiter.api.AfterEach;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceShellWebIT {
    private static final String PASSWORD = "Synthetic-Workspace-Password-2026!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);
    @Autowired MockMvc mvc;
    private JdbcTemplate jdbc;

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
    void identities() {
        DatabaseFixture.start().seedIdentity(HASH);
        jdbc = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        clearAdditionalRoles();
        restoreName();
    }

    @AfterEach
    void restoreFixture() {
        if (jdbc == null) return;
        clearAdditionalRoles();
        restoreName();
        DatabaseFixture.start().seedIdentity(HASH);
    }

    @Test
    void employeeNavigationReceivesOnlyActualEmployeeRole() throws Exception {
        mvc.perform(get("/me/profile").session(login()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page())
                .andExpect(ReactPageAssertions.roles("EMPLOYEE"))
                .andExpect(model().attributeExists("workspaceEmployee", "workspacePrincipal", "workspaceDemo"));
    }

    @Test
    void adminPagesShareMinimalIdentityCsrfAndSelfHostedAssets() throws Exception {
        grant("ATTENDANCE_ADMIN");
        var session = login();
        for (String path : new String[]{"/me/profile", "/admin/organizations", "/admin/networks"}) {
            mvc.perform(get(path).session(session)).andExpect(status().isOk())
                    .andExpect(ReactPageAssertions.page()).andExpect(ReactPageAssertions.roles("EMPLOYEE", "ATTENDANCE_ADMIN"))
                    .andExpect(ReactPageAssertions.data("/workspaceEmployee/employeeName", "직원 A"))
                    .andExpect(ReactPageAssertions.csrf())
                    .andExpect(content().string(containsString("/react/app.js")))
                    .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")));
        }
    }

    @Test
    void approverNavigationReceivesTheActualApproverRole() throws Exception {
        grant("APPROVER");
        mvc.perform(get("/me/profile").session(login())).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.roles("EMPLOYEE", "APPROVER"));
    }

    @Test
    void completedDecisionsKeepTheSharedWorkspaceIdentity() throws Exception {
        grant("APPROVER");
        var session = login();
        for (String accept : new String[]{"text/html", "application/vnd.worklog.page+json"}) {
            mvc.perform(get("/approvals/completed").session(session).accept(accept))
                    .andExpect(status().isOk())
                    .andExpect(ReactPageAssertions.roles("EMPLOYEE", "APPROVER"))
                    .andExpect(ReactPageAssertions.data("/workspaceEmployee/employeeName", "직원 A"))
                    .andExpect(ReactPageAssertions.csrf());
        }
    }

    @Test
    void adminWithoutEmployeeRoleCanStillUseExistingAdministrationPages() throws Exception {
        grant("ATTENDANCE_ADMIN");
        jdbc.update("DELETE FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND role='EMPLOYEE'",
                DatabaseFixture.TENANT_A, DatabaseFixture.ACCOUNT_A);
        mvc.perform(get("/admin/organizations").session(login())).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.roles("ATTENDANCE_ADMIN"))
                .andExpect(ReactPageAssertions.data("/workspaceEmployee/employeeName", "직원 A"));
    }

    @Test
    void headerIdentityIsEscapedAndCannotBeSelectedByRequestTenant() throws Exception {
        jdbc.update("UPDATE tna.employee SET display_name=? WHERE tenant_id=? AND id=?",
                "<script>workspace-name</script>", DatabaseFixture.TENANT_A, DatabaseFixture.EMPLOYEE_A);
        var session = login();
        mvc.perform(get("/me/profile").session(session)
                        .param("tenant_id", DatabaseFixture.TENANT_B.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\\u003cscript\\u003eworkspace-name\\u003c/script\\u003e")))
                .andExpect(content().string(not(containsString("<script>workspace-name</script>"))))
                .andExpect(content().string(not(containsString("직원 B"))));
    }

    private void grant(String role) {
        jdbc.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",
                DatabaseFixture.TENANT_A, DatabaseFixture.ACCOUNT_A, role);
    }

    private void clearAdditionalRoles() {
        jdbc.update("DELETE FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND role<>'EMPLOYEE'",
                DatabaseFixture.TENANT_A, DatabaseFixture.ACCOUNT_A);
    }

    private void restoreName() {
        jdbc.update("UPDATE tna.employee SET display_name=? WHERE tenant_id=? AND id=?",
                "직원 A", DatabaseFixture.TENANT_A, DatabaseFixture.EMPLOYEE_A);
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .with(request -> { request.setRemoteAddr("198.19.1." + ADDRESS.getAndIncrement()); return request; })
                        .param("companyCode", DatabaseFixture.COMPANY_A)
                        .param("email", DatabaseFixture.EMAIL).param("password", PASSWORD))
                .andExpect(redirectedUrl("/me/home"))
                .andReturn().getRequest().getSession(false);
    }
}
