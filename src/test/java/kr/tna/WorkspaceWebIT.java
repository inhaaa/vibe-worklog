package kr.tna;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
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
    void attendanceUsesWorkspaceShellAndKeepsExistingAttendanceForms() throws Exception {
        mvc.perform(get("/me/attendance").session(login()))
                .andExpect(status().isOk()).andExpect(view().name("clock/history"))
                .andExpect(model().attributeExists("day", "eventTimes", "inCommand", "outCommand", "today", "isToday"))
                .andExpect(model().attributeDoesNotExist("confirmationForm"))
                .andExpect(ReactPageAssertions.page())
                .andExpect(ReactPageAssertions.dataPresent("/inCommand"))
                .andExpect(ReactPageAssertions.dataPresent("/inCommand"))
                .andExpect(ReactPageAssertions.dataPresent("/outCommand"))
                .andExpect(ReactPageAssertions.csrf())
                .andExpect(ReactPageAssertions.page());
    }

    @Test
    void attendanceDoesNotReplaceUnregisteredFactsWithMockupNumbers() throws Exception {
        mvc.perform(get("/me/attendance").session(login()))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.page())
                .andExpect(content().string(not(containsString("32시간"))))
                .andExpect(content().string(not(containsString("남은 연차 8일"))));
    }

    @Test
    void attendanceKeepsUnknownFactsAndSelectedWorkDate() throws Exception {
        var result = mvc.perform(get("/me/attendance").session(login()))
                .andExpect(status().isOk()).andReturn();
        var boot = ReactPageAssertions.envelope(result);
        var day = boot.at("/data/day");
        LocalDate today = (LocalDate) result.getModelAndView().getModel().get("today");
        assertEquals(today.toString(), day.get("workDate").asString());
        assertTrue(day.get("schedule").isNull());
        assertTrue(day.get("confirmation").isNull());
        assertTrue(day.get("events").isEmpty());
        assertTrue(boot.at("/data/isToday").asBoolean());
    }

    @Test
    void attendanceShowsActualRecordedTimesWithoutConfirmingWork() throws Exception {
        var session = login();
        var initial = mvc.perform(get("/me/attendance").session(session))
                .andExpect(status().isOk()).andReturn();
        LocalDate today = (LocalDate) initial.getModelAndView().getModel().get("today");
        var db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        UUID network = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by)
                VALUES (?,?,'127.0.0.1/32'::cidr,'합성 홈 기록 허용망',now(),'합성 회귀 검증',?)
                """, tenantId, network, accountId);
        for (var entry : java.util.List.of(new String[]{"OUT", "18:41"}, new String[]{"IN", "09:17"})) {
            var at = java.sql.Timestamp.from(today.atTime(java.time.LocalTime.parse(entry[1]))
                    .atZone(ZoneId.of("Asia/Seoul")).toInstant());
            db.update("""
                    INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,
                        client_command_id,source,validated_ip,network_id,assigned_work_date)
                    VALUES (?,?,?,?,?,?,?,'IP','127.0.0.1'::inet,?,?)
                    """, tenantId, UUID.randomUUID(), employeeId, entry[0], at, at, UUID.randomUUID(), network, today);
            if (entry[0].equals("OUT")) {
                var partial = mvc.perform(get("/me/attendance").session(session))
                        .andExpect(status().isOk()).andReturn();
                var snapshot = ReactPageAssertions.envelope(partial).at("/data/day");
                assertEquals(1, snapshot.get("effectiveEvents").size());
                assertTrue(snapshot.get("confirmation").isNull());
            }
        }
        var result = mvc.perform(get("/me/attendance").session(session))
                .andExpect(status().isOk()).andReturn();
        var data = ReactPageAssertions.envelope(result).get("data");
        assertTrue(data.at("/inCommand").isMissingNode() && data.at("/outCommand").isMissingNode(), "이미 기록한 구분은 다시 누를 수 없어야 합니다.");
        var home = ReactPageAssertions.envelope(mvc.perform(get("/me/home").session(session)).andExpect(status().isOk()).andReturn()).get("data");
        assertTrue(home.at("/clockInCommand").isMissingNode() && home.at("/clockOutCommand").isMissingNode());
        assertEquals(today.atTime(9, 17).atZone(ZoneId.of("Asia/Seoul")).toInstant().toString(), home.at("/workspaceHome/today/inRecordedAt").asString());
        assertTrue(data.at("/day/confirmation").isNull());
        assertEquals(2, data.at("/day/events").size());
        assertEquals(2, data.at("/day/effectiveEvents").size());
        var day = (DaySnapshot) result.getModelAndView().getModel().get("day");
        for (var event : day.events()) {
            String expected = today + (event.kind().equals("IN") ? " 09:17:00" : " 18:41:00");
            assertEquals(expected, data.at("/eventTimes/" + event.id()).asString());
        }
        assertEquals(day.events(), day.effectiveEvents());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=? AND employee_id=?",
                Integer.class, tenantId, employeeId));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.work_confirmation WHERE tenant_id=? AND employee_id=?",
                Integer.class, tenantId, employeeId));
    }

    @Test
    void personalCalendarShowsSelectedDateAndCrossesTheYearInNavigation() throws Exception {
        mvc.perform(get("/me/schedule").session(login())
                        .param("month", "2026-12").param("date", "2026-12-31"))
                .andExpect(status().isOk()).andExpect(view().name("workspace/schedule"))
                .andExpect(model().attributeExists("workspaceCalendar", "selectedDay"))
                .andExpect(ReactPageAssertions.page())
                .andExpect(content().string(containsString("2026-12-31")))
                .andExpect(content().string(containsString("2027-01")))
                .andExpect(ReactPageAssertions.page());
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
                .andExpect(redirectedUrl("/me/home"))
                .andReturn().getRequest().getSession(false);
    }
}
