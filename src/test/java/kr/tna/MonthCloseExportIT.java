package kr.tna;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MonthCloseExportIT {
    private static final String PATH = "/admin/attendance/month-close";
    private static final LocalDate FIRST = LocalDate.of(2024, 3, 1);
    private static final String MONTH = "2024-03";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    private JdbcTemplate db;

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

    @Test
    void exportsFrozenCompanyDepartmentAndUnassignedRowsAndRequiresTheLatestClose() throws Exception {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        var admin = company();
        UUID first = department(admin, "A01", "이동 전 부서"), second = department(admin, "B01", "이동 후 부서");
        UUID worker = employee(admin.tenantId(), "001", "=합성 이동 사원", FIRST, FIRST.plusDays(3));
        UUID unassigned = employee(admin.tenantId(), "002", "미배정 사원", FIRST.plusDays(2), FIRST.plusDays(3));
        assign(admin, worker, first, FIRST, FIRST.plusDays(2));
        assign(admin, worker, second, FIRST.plusDays(2), null);
        restDays(admin, worker, unassigned);
        mvc.perform(options(admin, 0)).andExpect(status().isConflict());
        close(admin, "CLOSE", 0);
        var options = optionBody(admin, 1);
        assertEquals(MONTH, options.path("month").asString());
        assertEquals(1, options.path("version").asLong());
        var departments = new HashSet<String>();
        for (var row : options.get("departments")) {
            assertTrue(row.isObject(), "소속 미등록도 선택 가능한 객체여야 합니다.");
            departments.add(row.required("organizationId").isNull() ? "미등록" : row.get("organizationId").asString());
        }
        assertEquals(Set.of(first.toString(), second.toString(), "미등록"), departments);
        var workbook = download(admin, 1, "COMPANY", null);
        var company = worksheet(workbook);
        assertTrue(company.get(3).contains("유급휴가 인정시간(시간)"));
        assertTrue(company.contains(withZeroPayroll(List.of("범위 합계", "", "2", "6", "0", "0.0", "0.0", "0.0"))));
        assertTrue(company.contains(withZeroPayroll(List.of("001", "=합성 이동 사원", "A01", "이동 전 부서", "2", "0", "0.0", "0.0", "0.0"))));
        assertTrue(company.contains(withZeroPayroll(List.of("001", "=합성 이동 사원", "B01", "이동 후 부서", "2", "0", "0.0", "0.0", "0.0"))));
        assertTrue(company.contains(withZeroPayroll(List.of("002", "미배정 사원", "", "소속 미등록", "2", "0", "0.0", "0.0", "0.0"))));
        var department = worksheet(download(admin, 1, "DEPARTMENT", second));
        assertTrue(department.contains(withZeroPayroll(List.of("범위 합계", "", "1", "2", "0", "0.0", "0.0", "0.0"))));
        assertFalse(department.toString().contains("미배정 사원"));
        assertFalse(department.toString().contains("이동 전 부서"));
        var unassignedSheet = worksheet(download(admin, 1, "UNASSIGNED", null));
        assertTrue(unassignedSheet.contains(withZeroPayroll(List.of("범위 합계", "", "1", "2", "0", "0.0", "0.0", "0.0"))));
        assertFalse(unassignedSheet.toString().contains("합성 이동 사원"));

        var other = company();
        var elsewhere = department(other, "B01", "외부 회사 부서");
        mvc.perform(export(admin, 1, "DEPARTMENT", elsewhere)).andExpect(status().isNotFound());
        mvc.perform(options(other, 1).param("tenantId", admin.tenantId().toString())).andExpect(status().isConflict());
        mvc.perform(export(other, 1, "COMPANY", null).param("tenantId", admin.tenantId().toString())).andExpect(status().isConflict());
        var app = DatabaseFixture.start().appDataSource();
        var application = new JdbcTemplate(app);
        assertEquals(0, new TenantTransactions(app).execute(other.tenantId(), () -> application.queryForObject(
                "SELECT count(*) FROM tna.attendance_month_export_row WHERE tenant_id=?", Integer.class, admin.tenantId())));
        for (Set<String> roles : List.of(Set.of("EMPLOYEE"), Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE"))) {
            var unauthorized = account(admin.tenantId(), roles);
            mvc.perform(options(unauthorized, 1)).andExpect(status().isForbidden());
            mvc.perform(export(unauthorized, 1, "COMPANY", null)).andExpect(status().isForbidden());
        }
        mvc.perform(export(admin, 1, "COMPANY", first)).andExpect(status().isUnprocessableContent());
        mvc.perform(export(admin, 1, "DEPARTMENT", null)).andExpect(status().isUnprocessableContent());
        db.update("UPDATE tna.employee SET display_name='변경된 사원' WHERE tenant_id=? AND id=?", admin.tenantId(), worker);
        db.update("UPDATE tna.organization SET name='변경된 부서' WHERE tenant_id=? AND id=?", admin.tenantId(), first);
        assertArrayEquals(workbook, download(admin, 1, "COMPANY", null));
        assertEquals(options, optionBody(admin, 1));
        close(admin, "REOPEN", 1);
        mvc.perform(options(admin, 1)).andExpect(status().isConflict());
        mvc.perform(export(admin, 2, "COMPANY", null)).andExpect(status().isConflict());
        close(admin, "CLOSE", 2);
        mvc.perform(export(admin, 1, "COMPANY", null)).andExpect(status().isConflict());
        var reclosed = worksheet(download(admin, 3, "DEPARTMENT", first));
        assertTrue(reclosed.contains(withZeroPayroll(List.of("001", "변경된 사원", "A01", "변경된 부서", "2", "0", "0.0", "0.0", "0.0"))));
    }

    @Test
    void earlierCloseKeepsItsOriginalColumnsUntilReopenedAndClosedAgain() throws Exception {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        var admin = company();
        var worker = employee(admin.tenantId(), "009", "기존 마감 사원", FIRST, FIRST);
        restDays(admin, worker);
        db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                VALUES (?,gen_random_uuid(),?,1,'CLOSED','기존 마감',?,'담당자')
                """, admin.tenantId(), FIRST, admin.accountId());
        db.update("""
                UPDATE tna.attendance_month_export_row SET payroll_captured=false,approved_overtime_millis=NULL,
                  unapproved_extra_millis=NULL,night_work_millis=NULL,holiday_work_millis=NULL,late_count=NULL,late_millis=NULL,
                  early_leave_count=NULL,early_leave_millis=NULL,absence_days=NULL WHERE tenant_id=?""", admin.tenantId());
        var legacy = worksheet(download(admin, 1, "COMPANY", null));
        var blanks = Collections.nCopies(9, "");
        assertEquals(concat(List.of("집계 범위", "부서코드", "사원 수(명)", "재직일수(일)", "실근로 발생 일수(일)",
                "실근로시간(시간)", "사용 휴가(일)"), PAYROLL_HEADERS), legacy.get(3));
        assertTrue(legacy.contains(concat(List.of("범위 합계", "", "1", "1", "0", "0.0", "0.0"), blanks)));
        close(admin, "REOPEN", 1);
        close(admin, "CLOSE", 2);
        assertEquals("유급휴가 인정시간(시간)", worksheet(download(admin, 3, "COMPANY", null)).get(3).get(7));
    }

    @Test
    void capturesPayrollTotalsAtCloseAndBlanksThemForCloseWithoutCaptureOrUnknownDays() throws Exception {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        var admin = company();
        var team = department(admin, "PAY", "급여 부서");
        UUID worker = employee(admin.tenantId(), "P01", "급여 사원", FIRST, FIRST.plusDays(4));
        UUID legacyDays = employee(admin.tenantId(), "P02", "이전 규칙 사원", FIRST, FIRST.plusDays(1));
        UUID legacyNoOvertime = employee(admin.tenantId(), "P03", "이전 규칙 무연장", FIRST, FIRST);
        assign(admin, worker, team, FIRST, null);
        assign(admin, legacyDays, team, FIRST, null);
        assign(admin, legacyNoOvertime, team, FIRST, null);
        restDays(admin, worker, legacyDays, legacyNoOvertime);
        payrollResult(admin, worker, 0, "CALC-3", 2L, 5L, 1L, 420_000L, 0L, null, 2L);  // 5-2-2(휴일근로)=1
        payrollResult(admin, worker, 1, "CALC-3", 0L, 0L, 0L, 0L, 1_800_000L, "FULL", 0L);
        payrollResult(admin, worker, 2, "CALC-3", 0L, 0L, 0L, 0L, 0L, "HALF", 0L);
        payrollResult(admin, legacyDays, 0, "CALC-2", null, null, null, null, null, null, null);
        payrollResult(admin, legacyNoOvertime, 0, "CALC-2", null, null, null, null, null, null, null);
        approvedOvertime(admin, legacyDays, FIRST);
        db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                VALUES (?,gen_random_uuid(),?,1,'CLOSED','합성 마감',?,'담당자')
                """, admin.tenantId(), FIRST, admin.accountId());
        var row = db.queryForMap("""
                SELECT r.* FROM tna.attendance_month_export_row r WHERE r.tenant_id=? AND r.employee_id=?
                """, admin.tenantId(), worker);
        assertEquals(true, row.get("payroll_captured"));
        assertEquals(7_200_000L, row.get("approved_overtime_millis"));
        assertEquals(3_600_000L, row.get("unapproved_extra_millis"));
        assertEquals(3_600_000L, row.get("night_work_millis"));
        assertEquals(2 * 3_600_000L, row.get("holiday_work_millis"));
        assertEquals(1, row.get("late_count"));
        assertEquals(420_000L, row.get("late_millis"));
        assertEquals(1, row.get("early_leave_count"));
        assertEquals(1_800_000L, row.get("early_leave_millis"));
        assertEquals(0, new java.math.BigDecimal("1.5").compareTo((java.math.BigDecimal) row.get("absence_days")));
        var legacy = db.queryForMap("SELECT * FROM tna.attendance_month_export_row WHERE tenant_id=? AND employee_id=?",
                admin.tenantId(), legacyDays);
        assertNull(legacy.get("approved_overtime_millis"));
        assertNull(legacy.get("unapproved_extra_millis"));
        assertEquals(0L, legacy.get("night_work_millis"));
        // 승인된 연장이 없는 CALC-2 날은 인정 연장 0으로 센다.
        var noOvertime = db.queryForMap("SELECT * FROM tna.attendance_month_export_row WHERE tenant_id=? AND employee_id=?",
                admin.tenantId(), legacyNoOvertime);
        assertEquals(0L, noOvertime.get("approved_overtime_millis"));
        assertEquals(0L, noOvertime.get("unapproved_extra_millis"));
        var sheet = worksheet(download(admin, 1, "COMPANY", null));
        assertTrue(sheet.contains(concat(List.of("P01", "급여 사원", "PAY", "급여 부서", "5", "3", "3.0", "0.0"),
                List.of("2.0", "1.0", "1.0", "2.0", "1", "7", "1", "30", "1.5"))), sheet.toString());
        assertTrue(sheet.contains(concat(List.of("P02", "이전 규칙 사원", "PAY", "급여 부서", "2", "1", "1.0", "0.0"),
                List.of("미확정", "미확정", "0.0", "0.0", "0", "0", "0", "0", "0.0"))), sheet.toString());
        assertTrue(sheet.contains(concat(List.of("범위 합계", "", "3", "8", "5", "5.0", "0.0"),
                List.of("미확정", "미확정", "1.0", "2.0", "1", "7", "1", "30", "1.5"))), sheet.toString());
    }

    private static final List<String> PAYROLL_HEADERS = List.of("인정 연장(시간)", "승인 없는 초과(시간)", "야간근로(시간)",
            "휴일근로(시간)", "지각(회)", "지각(분)", "조퇴(회)", "조퇴(분)", "결근(일)");

    private static List<String> concat(List<String> a, List<String> b) {
        var all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    static List<String> withZeroPayroll(List<String> cells) {
        return concat(cells, List.of("0.0", "0.0", "0.0", "0.0", "0", "0", "0", "0", "0.0"));
    }

    /** 승인된 유효 연장 신청 한 건. 이 검증과 무관한 참조 키는 이 연결에서만 건너뛴다. */
    private void approvedOvertime(TnaPrincipal person, UUID employee, LocalDate day) {
        UUID request = UUID.randomUUID();
        db.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) { st.execute("SET session_replication_role = replica"); }
            try (var ps = c.prepareStatement("""
                    INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,approver_assignment_id,approver_assignment_version,reason,
                        from_date,to_date,client_command_id,form_version_id,version)
                    VALUES (?,?,?,?,'OVERTIME','APPROVED',?,gen_random_uuid(),0,'합성',?,?,gen_random_uuid(),gen_random_uuid(),1)""")) {
                ps.setObject(1, person.tenantId()); ps.setObject(2, request); ps.setObject(3, employee); ps.setObject(4, person.accountId());
                ps.setObject(5, UUID.randomUUID()); ps.setObject(6, day); ps.setObject(7, day); ps.executeUpdate();
            }
            try (var ps = c.prepareStatement("""
                    INSERT INTO tna.overtime_request_detail(tenant_id,request_id,work_date,start_time,end_time,requested_minutes,
                        schedule_version_id,code_version_id,calendar_version_id,basis_token)
                    VALUES (?,?,?,TIME '18:00',TIME '20:00',60,gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),repeat('a',64))""")) {
                ps.setObject(1, person.tenantId()); ps.setObject(2, request); ps.setObject(3, day); ps.executeUpdate();
            }
            try (var ps = c.prepareStatement("INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,created_by) VALUES (?,gen_random_uuid(),?,?,'OVERTIME',?)")) {
                ps.setObject(1, person.tenantId()); ps.setObject(2, request); ps.setObject(3, employee); ps.setObject(4, person.accountId()); ps.executeUpdate();
            }
            return null;
        });
    }

    private void payrollResult(TnaPrincipal person, UUID employee, int dayOffset, String rule, Long approvedHours,
            Long outsideHours, Long nightHours, Long late, Long early, String absence, Long holidayHours) {
        UUID run = UUID.randomUUID();
        LocalDate day = FIRST.plusDays(dayOffset);
        db.update("""
                INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required)
                VALUES (?,?,?,1,0,false) ON CONFLICT DO NOTHING""", person.tenantId(), employee, day);
        db.update("""
                INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version)
                VALUES (?,?,?,NULL,?,?,?,?,?)""", person.tenantId(), run, employee, day, day, day, day, rule);
        long hour = 3_600_000L;
        db.update("""
                INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
                  source_fingerprint,rule_version,status,leave_days,reason_codes,input_refs,actual_work_millis,
                  approved_overtime_millis,outside_schedule_millis,night_work_millis,late_millis,early_leave_millis,absence,
                  holiday_work_millis)
                VALUES (?,?,?,?,?,1,1,0,?,?,'CALCULATED',0,'[]'::jsonb,'[]'::jsonb,?,?,?,?,?,?,?,?)""",
                person.tenantId(), UUID.randomUUID(), run, employee, day, "c".repeat(64), rule, hour,
                approvedHours == null || "CALC-2".equals(rule) ? null : approvedHours * hour,
                outsideHours == null ? null : outsideHours * hour, nightHours == null ? null : nightHours * hour,
                late, early, absence, holidayHours == null ? null : holidayHours * hour);
    }

    @Test
    void auditsEachSuccessfulDownloadWithItsScopeAndCloseVersion() throws Exception {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        var admin = company();
        var team = department(admin, "AUDIT", "감사 확인 부서");
        UUID worker = employee(admin.tenantId(), "E01", "부서 사원", FIRST, FIRST);
        UUID unassigned = employee(admin.tenantId(), "E02", "미배정 사원", FIRST, FIRST);
        assign(admin, worker, team, FIRST, null);
        restDays(admin, worker, unassigned);
        close(admin, "CLOSE", 0);
        UUID closure = db.queryForObject("""
                SELECT id FROM tna.attendance_month_closure WHERE tenant_id=? AND month_start=? AND version=1
                """, UUID.class, admin.tenantId(), FIRST);
        var originalOptions = optionBody(admin, 1);
        mvc.perform(export(account(admin.tenantId(), Set.of("EMPLOYEE")), 1, "COMPANY", null)).andExpect(status().isForbidden());
        mvc.perform(export(admin, 1, "DEPARTMENT", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(export(admin, 2, "COMPANY", null)).andExpect(status().isConflict());
        mvc.perform(export(admin, 1, "DEPARTMENT", null)).andExpect(status().isUnprocessableContent());
        assertTrue(downloadScopes(admin, closure).isEmpty());

        var originalBook = download(admin, 1, "COMPANY", null);
        assertEquals(List.of("COMPANY"), downloadScopes(admin, closure));
        assertArrayEquals(originalBook, download(admin, 1, "COMPANY", null));
        download(admin, 1, "DEPARTMENT", team);
        download(admin, 1, "UNASSIGNED", null);
        assertEquals(originalOptions, optionBody(admin, 1));
        assertEquals(List.of("COMPANY", "COMPANY", "DEPARTMENT/" + team, "UNASSIGNED"), downloadScopes(admin, closure));
    }

    private List<String> downloadScopes(TnaPrincipal actor, UUID closure) {
        return db.query("""
                SELECT actor_account_id,target_kind,target_id,after_state::text FROM tna.org_audit_event
                WHERE tenant_id=? AND action='MONTH_CLOSE_EXPORT_DOWNLOAD' ORDER BY after_state->>'scope',id
                """, (row, index) -> {
            assertEquals(actor.accountId(), row.getObject("actor_account_id", UUID.class));
            assertEquals("ATTENDANCE_MONTH", row.getString("target_kind"));
            assertEquals(closure, row.getObject("target_id", UUID.class));
            var after = JSON.readTree(row.getString("after_state"));
            assertEquals(MONTH, after.required("month").asString());
            assertEquals(1, after.required("version").asLong());
            String scope = after.required("scope").asString();
            if ("DEPARTMENT".equals(scope)) return scope + "/" + after.required("organizationId").asString();
            assertFalse(after.has("organizationId"));
            return scope;
        }, actor.tenantId());
    }

    private TnaPrincipal company() {
        UUID tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 마감 회사')", tenant, "EX-" + tenant);
        return account(tenant, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    }

    private TnaPrincipal account(UUID tenant, Set<String> roles) {
        UUID id = UUID.randomUUID();
        UUID employee = employee(tenant, id.toString(), "합성 사용자", FIRST.plusMonths(1), null);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic')",
                tenant, id, employee, id + "@example.test");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, id, role);
        return new TnaPrincipal(tenant, id, employee, 1, roles, Instant.now());
    }

    private UUID employee(UUID tenant, String number, String name, LocalDate from, LocalDate through) {
        var id = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on) VALUES (?,?,?,?,?,?)",
                tenant, id, number, name, from, through);
        return id;
    }

    private UUID department(TnaPrincipal actor, String code, String name) {
        var id = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,?,?,?)",
                actor.tenantId(), id, code, name, actor.accountId(), actor.accountId());
        return id;
    }

    private void assign(TnaPrincipal actor, UUID employee, UUID department, LocalDate from, LocalDate until) {
        db.update("""
                INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,valid_to,reason,created_by,updated_by)
                VALUES (?,gen_random_uuid(),?,?,?,?,'합성 발령',?,?)
                """, actor.tenantId(), employee, department, from, until, actor.accountId(), actor.accountId());
    }

    private void restDays(TnaPrincipal actor, UUID... employees) {
        UUID tenant = actor.tenantId(), author = actor.accountId();
        UUID template = UUID.randomUUID(), version = UUID.randomUUID(), calendar = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'REST','휴무 일정',?,?)",
                tenant, template, author, author);
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'휴무 일정',?)",
                tenant, version, template, FIRST, author);
        db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday) SELECT ?,?,n,false FROM generate_series(1,7) n",
                tenant, version);
        for (UUID employee : employees) {
            db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,gen_random_uuid(),?,?,?,'휴무 배정',?,?)",
                    tenant, employee, version, FIRST, author, author);
        }
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,?,?,'합성 달력',?)",
                tenant, calendar, FIRST, FIRST.plusMonths(1), author);
        db.update("""
                INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis)
                SELECT ?,?,DATE '2024-03-01'+n,'NON_WORKDAY','휴무일','합성 달력' FROM generate_series(0,30) n
                """, tenant, calendar);
    }

    static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }

    private void close(TnaPrincipal actor, String action, long version) throws Exception {
        mvc.perform(post(PATH).with(as(actor)).with(csrf()).contentType("application/json").accept("application/json")
                .content(JSON.writeValueAsString(Map.of("month", MONTH, "action", action, "expectedVersion", version,
                        "commandId", UUID.randomUUID(), "reason", "합성 결과 검토")))).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder options(TnaPrincipal actor, long version) {
        return get(PATH + "/export-options").with(as(actor)).param("month", MONTH).param("version", Long.toString(version)).accept("application/json");
    }

    private JsonNode optionBody(TnaPrincipal actor, long version) throws Exception {
        return JSON.readTree(mvc.perform(options(actor, version)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private static MockHttpServletRequestBuilder export(TnaPrincipal actor, long version, String scope, UUID department) {
        var get = get(PATH + "/export").with(as(actor)).param("month", MONTH).param("version", Long.toString(version)).param("scope", scope);
        return department == null ? get : get.param("organizationId", department.toString());
    }

    private byte[] download(TnaPrincipal actor, long version, String scope, UUID department) throws Exception {
        var response = mvc.perform(export(actor, version, scope, department)).andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        assertTrue(response.getHeader("Content-Disposition").endsWith(".xlsx\""));
        return response.getContentAsByteArray();
    }

    static List<List<String>> worksheet(byte[] book) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(book))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!"xl/worksheets/sheet1.xml".equals(entry.getName())) continue;
                var documents = DocumentBuilderFactory.newInstance();
                documents.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var document = documents.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
                var rows = document.getElementsByTagName("row");
                var result = new ArrayList<List<String>>();
                for (int i = 0; i < rows.getLength(); i++) {
                    var cells = ((Element) rows.item(i)).getElementsByTagName("c");
                    var values = new ArrayList<String>();
                    for (int j = 0; j < cells.getLength(); j++) values.add(cells.item(j).getTextContent());
                    result.add(values);
                }
                return result;
            }
        }
        throw new AssertionError("월 마감 시트를 찾을 수 없습니다.");
    }
}
