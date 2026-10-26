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
import java.util.regex.Pattern;
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
    void homeFlowKeepsUnknownFactsAndLinksEveryActualWeekDate() throws Exception {
        var result = mvc.perform(get("/me/today").session(login()))
                .andExpect(status().isOk()).andReturn();
        String html = result.getResponse().getContentAsString();
        LocalDate today = (LocalDate) result.getModelAndView().getModel().get("today");
        assertTrue(html.contains("t-home-flow"));
        assertTrue(html.contains("t-flow-date"));
        assertTrue(html.contains("datetime=\"" + today + "\""));
        assertTrue(html.contains("근무 일정 미배정"));
        assertTrue(html.contains("실제 근로 미확인"));
        String tasks = elementWithClass(html, "section", "t-flow-tasks");
        assertTrue(tasks.contains("기초 연차 미등록"));
        assertFalse(tasks.contains("실제 근로·휴게 확인"), "기록이 없는 날짜를 확인할 일로 만들지 않는다");
        String week = elementWithClass(html, "section", "t-flow-week");
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = monday.plusDays(offset);
            assertTrue(week.contains("/me/schedule?month=" + java.time.YearMonth.from(date)
                    + "&amp;date=" + date), "실제 조회 주의 각 날짜로 이동한다: " + date);
        }
        assertEquals(7, Pattern.compile("class=\"[^\"]*\\bt-flow-day\\b[^\"]*\"")
                .matcher(week).results().count());
        assertTrue(nativeComparison(html).contains("원천 출퇴근"));
        assertFalse(html.contains("32시간"));
    }

    @Test
    void homeFlowShowsActualRecordedTimesAndComparisonWithoutConfirmingWork() throws Exception {
        var session = login();
        var initial = mvc.perform(get("/me/today").session(session))
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
                var partial = mvc.perform(get("/me/today").session(session))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertFalse(elementWithClass(partial, "section", "t-flow-tasks")
                        .contains("실제 근로·휴게 확인"), "출퇴근 양쪽 기록이 있어야 확인 연결을 제공한다");
            }
        }
        var result = mvc.perform(get("/me/today").session(session))
                .andExpect(status().isOk()).andReturn();
        String html = result.getResponse().getContentAsString();
        String timeline = elementWithClass(html, "ol", "t-flow-timeline");
        assertTrue(timeline.contains("09:17:00.000"));
        assertTrue(timeline.contains("18:41:00.000"));
        assertTrue(timeline.indexOf("09:17:00.000") < timeline.indexOf("18:41:00.000"),
                "저장 순서와 무관하게 서울 시각의 시간순으로 기록을 표시한다");
        String comparison = nativeComparison(html);
        assertTrue(comparison.contains("원천 출퇴근"));
        assertTrue(comparison.contains("현재 반영된 기록"));
        assertEquals(2, Pattern.compile("09:17:00\\.000").matcher(comparison).results().count());
        assertEquals(2, Pattern.compile("18:41:00\\.000").matcher(comparison).results().count());
        assertTrue(elementWithClass(html, "section", "t-flow-tasks").contains("실제 근로·휴게 확인"));
        assertTrue(html.contains("실제 근로 미확인"));
        var day = (DaySnapshot) result.getModelAndView().getModel().get("day");
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

    private static String elementWithClass(String html, String tag, String cssClass) {
        var match = Pattern.compile("(?s)<" + tag + "\\b[^>]*class=\"[^\"]*\\b"
                + Pattern.quote(cssClass) + "\\b[^\"]*\"[^>]*>(.*?)</" + tag + ">")
                .matcher(html);
        assertTrue(match.find(), "홈 영역이 존재한다: " + cssClass);
        return match.group(1);
    }

    private static String nativeComparison(String html) {
        var match = Pattern.compile("(?s)<details\\b(?=[^>]*\\bid=\"home-record-comparison\")[^>]*>(.*?)</details>")
                .matcher(html);
        assertTrue(match.find(), "기록 비교는 native details로 제공한다");
        return match.group(1);
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
