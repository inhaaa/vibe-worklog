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

/** 담당자 '지각·조퇴·결근' 탭: 월의 최신 결과만, 회사 안에서만, 근태 담당자만 본다. */
@SpringBootTest
@AutoConfigureMockMvc
class AttendanceExceptionsWebIT {
    private static final String PAGE_JSON = "application/vnd.worklog.page+json";
    // 고정 월은 실행일(2026-11-25 이후) 기준 항상 과거 달이다.
    private static final String MONTH = "2026-10";

    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal admin, worker, other;

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

    @BeforeEach
    void results() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = company();
        admin = member(tenant, "ADMIN", "ATTENDANCE_ADMIN");
        worker = member(tenant, "E01", null);
        other = member(tenant, "E02", null);
        result(worker, "2026-10-05", 1, 420_000L, 0L, null);         // 지각 7분
        result(worker, "2026-10-08", 1, 0L, 0L, null);               // 정상
        result(worker, "2026-10-09", 1, 0L, 1_800_000L, null);       // 조퇴 30분
        result(worker, "2026-11-03", 1, 60_000L, 0L, null);          // 다른 달
        result(other, "2026-10-06", 1, null, null, "FULL");          // 결근
        result(other, "2026-10-07", 1, 300_000L, 0L, null);          // 지각이었으나
        result(other, "2026-10-07", 2, 0L, 0L, null);                // 최신 결과는 정상
        UUID foreign = company();
        result(member(foreign, "E01", "ATTENDANCE_ADMIN"), "2026-10-05", 1, 600_000L, 0L, null);
    }

    @Test
    void listsLatestLateEarlyLeaveAndAbsenceOfTheMonthInsideTheCompany() throws Exception {
        mvc.perform(exceptions(admin).param("kind", "ALL"))
                .andExpect(status().isOk()).andExpect(view().name("company/attendance"))
                .andExpect(ReactPageAssertions.data("/canViewCheckout", true))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/month", MONTH))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/kind", "ALL"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/counts/late", 1))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/counts/earlyLeave", 1))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/counts/absent", 1))
                .andExpect(jsonPath("$.data.attendanceExceptions.rows.length()").value(3))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/0/workDate", "2026-10-09"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/0/label", "조퇴 30분"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/1/employeeId", other.employeeId()))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/1/employeeNo", "E02"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/1/employeeName", "합성 E02"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/1/workDate", "2026-10-06"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/1/label", "결근"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/2/employeeId", worker.employeeId()))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/2/label", "지각 7분"))
                .andExpect(jsonPath("$.data.attendanceExceptions.error").doesNotExist());
        mvc.perform(exceptions(admin).param("kind", "ABSENT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.attendanceExceptions.rows.length()").value(1))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/0/label", "결근"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/counts/late", 1));
        mvc.perform(exceptions(admin).param("kind", "LATE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.attendanceExceptions.rows.length()").value(1))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/0/workDate", "2026-10-05"));
        // 날짜 링크가 가리키는 월별 근무현황도 그 사원·월을 연다.
        mvc.perform(get("/company/attendance").with(as(admin)).accept(PAGE_JSON)
                        .param("employeeId", other.employeeId().toString()).param("month", MONTH))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/companyAttendance/employeeId", other.employeeId()));
    }

    @Test
    void onlyAttendanceAdministratorsAndValidMonthsAreServed() throws Exception {
        mvc.perform(exceptions(worker)).andExpect(status().isForbidden());
        for (String month : new String[]{"2026-13", "2026-1", "nope", "2999-01"}) {
            mvc.perform(exceptions(admin).param("month", month))
                    .andExpect(status().isUnprocessableContent()).andExpect(view().name("company/attendance"))
                    .andExpect(ReactPageAssertions.dataPresent("/attendanceExceptions/error"))
                    .andExpect(jsonPath("$.data.attendanceExceptions.rows").isEmpty());
        }
        mvc.perform(exceptions(admin).param("kind", "UNKNOWN"))
                .andExpect(status().isUnprocessableContent()).andExpect(ReactPageAssertions.dataPresent("/attendanceExceptions/error"));
    }

    private MockHttpServletRequestBuilder exceptions(TnaPrincipal actor) {
        return get("/company/attendance").with(as(actor)).accept(PAGE_JSON).param("view", "exceptions").param("month", MONTH);
    }

    private void result(TnaPrincipal person, String date, int revision, Long late, Long early, String absence) {
        UUID run = UUID.randomUUID();
        LocalDate day = LocalDate.parse(date);
        db.update("""
                INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version)
                VALUES (?,?,?,NULL,?,?,?,?,'CALC-2')""", person.tenantId(), run, person.employeeId(), day, day, day, day);
        db.update("""
                INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
                  source_fingerprint,rule_version,status,leave_days,reason_codes,input_refs,late_millis,early_leave_millis,absence)
                VALUES (?,?,?,?,?,?,1,0,?,'CALC-2','CALCULATED',0,'[]'::jsonb,'[]'::jsonb,?,?,?)""",
                person.tenantId(), UUID.randomUUID(), run, person.employeeId(), day, revision, "b".repeat(64), late, early, absence);
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
