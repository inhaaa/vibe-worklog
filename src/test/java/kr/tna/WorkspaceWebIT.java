package kr.tna;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.common.auth.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceWebIT {
    private static final String PASSWORD = "Synthetic workspace web 43!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    private static final AtomicInteger ADDRESS = new AtomicInteger();
    private String remoteAddress;
    private UUID tenantId, employeeId, accountId;
    private String companyCode;
    @Autowired MockMvc mvc;

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
    void identities() {
        var db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenantId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        companyCode = ("WORKSPACE-" + tenantId).toUpperCase(java.util.Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,?)",
                tenantId, companyCode, "합성 워크스페이스 회사");
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,DATE '2026-01-01')",
                tenantId, employeeId, "WORKSPACE-1", "합성 워크스페이스 직원");
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenantId, accountId, employeeId, "workspace@example.test", HASH);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')",
                tenantId, accountId);
        remoteAddress = "198.18.1." + ADDRESS.incrementAndGet();
    }

    @Test
    void homeUsesWorkspaceShellAndKeepsExistingAttendanceForms() throws Exception {
        mvc.perform(get("/me/today").session(login()))
                .andExpect(status().isOk()).andExpect(view().name("today"))
                .andExpect(model().attributeExists("employee", "day", "eventTimes", "confirmationForm",
                        "inCommand", "outCommand", "workspaceHome"))
                .andExpect(content().string(containsString("workspace-body")))
                .andExpect(content().string(containsString("t-app-shell")))
                .andExpect(content().string(containsString("/me/attendance/events")))
                .andExpect(content().string(containsString("name=\"eventKind\" value=\"IN\"")))
                .andExpect(content().string(containsString("name=\"eventKind\" value=\"OUT\"")))
                .andExpect(content().string(containsString("/me/work-confirmations")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("/me/schedule")));
    }

    @Test
    void homeDoesNotReplaceUnregisteredFactsWithMockupNumbers() throws Exception {
        mvc.perform(get("/me/today").session(login()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("기초 연차 미등록")))
                .andExpect(content().string(containsString("근무 일정 미배정")))
                .andExpect(content().string(containsString("실제 근로")))
                .andExpect(content().string(not(containsString("32시간"))))
                .andExpect(content().string(not(containsString("남은 연차 8일"))));
    }

    @Test
    void personalCalendarShowsSelectedDateAndCrossesTheYearInNavigation() throws Exception {
        mvc.perform(get("/me/schedule").session(login())
                        .param("month", "2026-12").param("date", "2026-12-31"))
                .andExpect(status().isOk()).andExpect(view().name("workspace/schedule"))
                .andExpect(model().attributeExists("workspaceCalendar", "selectedDay"))
                .andExpect(content().string(containsString("workspace-body")))
                .andExpect(content().string(containsString("2026-12-31")))
                .andExpect(content().string(containsString("2027-01")))
                .andExpect(content().string(containsString("근무 일정 미배정")));
    }

    @Test
    void forgedTenantAndEmployeeParametersCannotChangeTheCalendarSubject() throws Exception {
        var result = mvc.perform(get("/me/schedule").session(login())
                        .param("month", "2026-10").param("date", "2026-10-26")
                        .param("tenantId", DatabaseFixture.TENANT_B.toString())
                        .param("employeeId", DatabaseFixture.EMPLOYEE_B.toString()))
                .andExpect(status().isOk()).andReturn();
        var selected = (DaySnapshot) result.getModelAndView().getModel().get("selectedDay");
        assertEquals(employeeId, selected.employeeId());
        assertEquals(LocalDate.of(2026, 10, 26), selected.workDate());
    }

    @Test
    void selectedDateMustBelongToTheRequestedMonth() throws Exception {
        var session = login();
        mvc.perform(get("/me/schedule").session(session)
                        .param("month", "2026-02").param("date", "2026-03-01"))
                .andExpect(status().is(422));
        mvc.perform(get("/me/schedule").session(session).param("month", "not-a-month"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void calendarRequiresAnAuthenticatedCustomerSession() throws Exception {
        mvc.perform(get("/me/schedule")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .with(request -> { request.setRemoteAddr(remoteAddress); return request; })
                        .param("companyCode", companyCode)
                        .param("email", "workspace@example.test").param("password", PASSWORD))
                .andExpect(redirectedUrl("/me/today"))
                .andReturn().getRequest().getSession(false);
    }
}
