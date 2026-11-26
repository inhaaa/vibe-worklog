package kr.tna;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 담당자 '주 52시간' 탭: 조회 월과 겹치는 주의 실근로 합계 48시간 이상만, 회사 안에서만, 근태 담당자만 본다. */
@SpringBootTest
@AutoConfigureMockMvc
class WeeklyHoursWebIT {
    private static final String PAGE_JSON = "application/vnd.worklog.page+json";
    // 고정 월은 실행일(2026-11-25 이후) 기준 항상 과거 달이다.
    private static final String MONTH = "2026-10";

    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal admin, worker, other, third;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry props) {
        var fixture = DatabaseFixture.start();
        props.add("spring.datasource.url", fixture::jdbcUrl);
        props.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        props.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        props.add("tna.auth.url", fixture::jdbcUrl);
        props.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        props.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    private static final long H = 3_600_000L;

    @BeforeEach
    void results() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = company();
        admin = member(tenant, "ADMIN", "ATTENDANCE_ADMIN");
        worker = member(tenant, "E01", null);
        var b = member(tenant, "E02", null);
        var c = member(tenant, "E03", null);
        var d = member(tenant, "E04", null);
        var e = member(tenant, "E05", null);
        var f = member(tenant, "E06", null);
        week(worker, "2026-10-05", 5, 53 * H / 5, 9 * H);      // 53시간: 초과 (인정 연장 9시간은 첫날)
        week(b, "2026-10-05", 5, 49 * H / 5, 0L);              // 49시간: 임박
        week(c, "2026-10-05", 5, 47 * H / 5, 0L);              // 47시간: 행 없음
        week(d, "2026-10-12", 4, 50 * H / 4, 0L);              // 50시간 + 확인 전 하루
        result(d, "2026-10-16", 1, null, null);
        week(e, "2026-09-28", 5, 49 * H / 5, 0L);              // 월 경계: 9-28~10-04 주도 포함
        week(f, "2026-11-02", 1, 60 * H, 0L);                  // 조회 월과 겹치지 않는 주
        result(b, "2026-10-05", 2, 0L, 0L);                    // 최신 결과가 0이면 B의 그날 합계가 줄어든다
        UUID foreign = company();
        week(member(foreign, "E01", "ATTENDANCE_ADMIN"), "2026-10-05", 5, 60 * H / 5, 0L);
    }

    @Test
    void listsWeeksOfThe48HourThresholdWithLatestResultsInsideTheCompany() throws Exception {
        // B의 10-05 최신 결과가 0이라 B는 39.2시간으로 내려가 빠진다.
        mvc.perform(weekly(admin))
                .andExpect(status().isOk()).andExpect(view().name("company/attendance"))
                .andExpect(ReactPageAssertions.data("/weeklyHours/month", MONTH))
                .andExpect(ReactPageAssertions.data("/weeklyHours/truncated", false))
                .andExpect(jsonPath("$.data.weeklyHours.rows.length()").value(3))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/0/employeeNo", "E01"))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/0/employeeName", "합성 E01"))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/0/employeeId", worker.employeeId()))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/0/status", "OVER"))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/0/weekStart", "2026-10-05"))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/0/weekEnd", "2026-10-11"))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/0/actualMillis", (int) (53 * H)))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/0/approvedOvertimeMillis", (int) (9 * H)))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/0/unknownDays", 0))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/1/employeeNo", "E04"))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/1/status", "NEAR"))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/1/unknownDays", 1))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/2/employeeNo", "E05"))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/2/weekStart", "2026-09-28"))
                .andExpect(ReactPageAssertions.data("/weeklyHours/rows/2/status", "NEAR"))
                .andExpect(jsonPath("$.data.weeklyHours.error").doesNotExist());
    }

    @Test
    void onlyAttendanceAdministratorsAndValidMonthsAreServed() throws Exception {
        mvc.perform(weekly(worker)).andExpect(status().isForbidden());
        for (String month : new String[]{"2026-13", "2026-1", "nope", "2999-01"}) {
            mvc.perform(weekly(admin).param("month", month))
                    .andExpect(status().isUnprocessableContent()).andExpect(view().name("company/attendance"))
                    .andExpect(ReactPageAssertions.dataPresent("/weeklyHours/error"))
                    .andExpect(jsonPath("$.data.weeklyHours.rows").isEmpty());
        }
    }

    private MockHttpServletRequestBuilder weekly(TnaPrincipal actor) {
        return get("/company/attendance").with(as(actor)).accept(PAGE_JSON).param("view", "weekly-hours").param("month", MONTH);
    }

    /** monday부터 days일 동안 날마다 perDay 실근로. 첫날에 인정 연장. */
    private void week(TnaPrincipal person, String monday, int days, long perDay, long overtimeOnFirst) {
        for (int i = 0; i < days; i++)
            result(person, LocalDate.parse(monday).plusDays(i).toString(), 1, perDay, i == 0 ? overtimeOnFirst : 0L);
    }

    private void result(TnaPrincipal person, String date, int revision, Long actual, Long overtime) {
        UUID run = UUID.randomUUID();
        LocalDate day = LocalDate.parse(date);
        db.update("""
                INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required)
                VALUES (?,?,?,1,0,false) ON CONFLICT DO NOTHING""", person.tenantId(), person.employeeId(), day);
        db.update("""
                INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version)
                VALUES (?,?,?,NULL,?,?,?,?,'CALC-3')""", person.tenantId(), run, person.employeeId(), day, day, day, day);
        db.update("""
                INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
                  source_fingerprint,rule_version,status,leave_days,reason_codes,input_refs,actual_work_millis,approved_overtime_millis)
                VALUES (?,?,?,?,?,?,1,0,?,'CALC-3','CALCULATED',0,'[]'::jsonb,'[]'::jsonb,?,?)""",
                person.tenantId(), UUID.randomUUID(), run, person.employeeId(), day, revision, "b".repeat(64), actual, actual == null ? null : overtime);
    }

    private UUID company() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 근태예외 회사')", id, "EXC-" + id);
        return id;
    }

    private TnaPrincipal member(UUID company, String number, String role) {
        UUID employee = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,DATE '2025-01-01')",
                company, employee, number, "합성 " + number);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused')",
                company, account, employee, account + "@example.test");
        var roles = role == null ? Set.of("EMPLOYEE") : Set.of("EMPLOYEE", role);
        for (String value : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", company, account, value);
        return new TnaPrincipal(company, account, employee, 1, roles, Instant.now());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
}
