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
        third = member(tenant, "E03", null);
        result(third, "2026-10-12", 1, null, null, "FULL");          // 결근이었으나 입력이 바뀌어 오래됨
        db.update("UPDATE tna.day_input_revision SET input_revision=2 WHERE tenant_id=? AND employee_id=? AND work_date=DATE '2026-10-12'",
                tenant, third.employeeId());
        result(third, "2026-10-13", 1, null, null, "HALF");          // 결근(반일)
        result(third, "2026-10-14", 1, 180_000L, 600_000L, null);    // 지각 3분 · 조퇴 10분
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
                // 지각·조퇴가 함께인 날은 한 행이고 두 건수에 모두 들어간다. 오래된 결근은 세지 않는다.
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/counts/late", 2))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/counts/earlyLeave", 2))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/counts/absent", 2))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/truncated", false))
                .andExpect(jsonPath("$.data.attendanceExceptions.rows.length()").value(5))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/0/workDate", "2026-10-14"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/0/label", "지각 3분 · 조퇴 10분"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/1/workDate", "2026-10-13"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/1/label", "결근(반일)"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/2/workDate", "2026-10-09"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/2/label", "조퇴 30분"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/3/employeeId", other.employeeId()))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/3/employeeNo", "E02"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/3/employeeName", "합성 E02"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/3/workDate", "2026-10-06"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/3/label", "결근"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/4/employeeId", worker.employeeId()))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/4/label", "지각 7분"))
                .andExpect(jsonPath("$.data.attendanceExceptions.error").doesNotExist());
        mvc.perform(exceptions(admin).param("kind", "ABSENT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.attendanceExceptions.rows.length()").value(2))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/0/label", "결근(반일)"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/1/label", "결근"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/counts/late", 2));
        mvc.perform(exceptions(admin).param("kind", "LATE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.attendanceExceptions.rows.length()").value(2))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/0/workDate", "2026-10-14"))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/1/workDate", "2026-10-05"));
        mvc.perform(exceptions(admin).param("kind", "EARLY_LEAVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.attendanceExceptions.rows.length()").value(2))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/1/workDate", "2026-10-09"));
        // 날짜 링크가 가리키는 월별 근무현황도 그 사원·월을 연다.
        mvc.perform(get("/company/attendance").with(as(admin)).accept(PAGE_JSON)
                        .param("employeeId", other.employeeId().toString()).param("month", MONTH))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/companyAttendance/employeeId", other.employeeId()));
    }

    @Test
    void countsCoverTheWholeMonthAndTheKindFilterRunsBeforeTheRowCap() throws Exception {
        UUID big = company();
        var bigAdmin = member(big, "ADMIN", "ATTENDANCE_ADMIN");
        db.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on)
                SELECT ?, gen_random_uuid(), 'T' || lpad(g::text, 3, '0'), '합성 다수', DATE '2025-01-01' FROM generate_series(1, 34) g""", big);
        db.update("""
                INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version)
                SELECT tenant_id, gen_random_uuid(), id, NULL, DATE '2026-10-01', DATE '2026-10-31', DATE '2026-10-01', DATE '2026-10-31', 'CALC-2'
                FROM tna.employee WHERE tenant_id=? AND employee_no LIKE 'T%'""", big);
        db.update("""
                INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required)
                SELECT run.tenant_id, run.employee_id, d::date, 1, 0, false
                FROM tna.calculation_run run CROSS JOIN generate_series(DATE '2026-10-01', DATE '2026-10-31', INTERVAL '1 day') d
                WHERE run.tenant_id=?""", big);
        db.update("""
                INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
                  source_fingerprint,rule_version,status,leave_days,reason_codes,input_refs,late_millis,early_leave_millis,absence)
                SELECT run.tenant_id, gen_random_uuid(), run.id, run.employee_id, d::date, 1, 1, 0, repeat('b', 64), 'CALC-2', 'CALCULATED', 0,
                  '[]'::jsonb, '[]'::jsonb, 60000, 0, NULL
                FROM tna.calculation_run run CROSS JOIN generate_series(DATE '2026-10-01', DATE '2026-10-31', INTERVAL '1 day') d
                WHERE run.tenant_id=?""", big);
        var single = member(big, "ABSENT-1", null);
        result(single, "2026-10-01", 1, null, null, "FULL");

        mvc.perform(exceptions(bigAdmin).param("kind", "ALL"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/attendanceExceptions/counts/late", 34 * 31))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/counts/absent", 1))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/truncated", true))
                .andExpect(jsonPath("$.data.attendanceExceptions.rows.length()").value(1000));
        // 결근 한 건은 지각 1000행 뒤로 밀리지 않는다.
        mvc.perform(exceptions(bigAdmin).param("kind", "ABSENT"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/attendanceExceptions/truncated", false))
                .andExpect(jsonPath("$.data.attendanceExceptions.rows.length()").value(1))
                .andExpect(ReactPageAssertions.data("/attendanceExceptions/rows/0/employeeId", single.employeeId()));
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
                INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required)
                VALUES (?,?,?,1,0,false) ON CONFLICT DO NOTHING""", person.tenantId(), person.employeeId(), day);
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
