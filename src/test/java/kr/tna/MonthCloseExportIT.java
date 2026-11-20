package kr.tna;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
        assertTrue(company.contains(List.of("범위 합계", "", "2", "6", "0", "0.0", "0.0")));
        assertTrue(company.contains(List.of("001", "=합성 이동 사원", "A01", "이동 전 부서", "2", "0", "0.0", "0.0")));
        assertTrue(company.contains(List.of("001", "=합성 이동 사원", "B01", "이동 후 부서", "2", "0", "0.0", "0.0")));
        assertTrue(company.contains(List.of("002", "미배정 사원", "", "소속 미등록", "2", "0", "0.0", "0.0")));
        var department = worksheet(download(admin, 1, "DEPARTMENT", second));
        assertTrue(department.contains(List.of("범위 합계", "", "1", "2", "0", "0.0", "0.0")));
        assertFalse(department.toString().contains("미배정 사원"));
        assertFalse(department.toString().contains("이동 전 부서"));
        var unassignedSheet = worksheet(download(admin, 1, "UNASSIGNED", null));
        assertTrue(unassignedSheet.contains(List.of("범위 합계", "", "1", "2", "0", "0.0", "0.0")));
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
        assertTrue(reclosed.contains(List.of("001", "변경된 사원", "A01", "변경된 부서", "2", "0", "0.0", "0.0")));
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

    private static RequestPostProcessor as(TnaPrincipal actor) {
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

    private static List<List<String>> worksheet(byte[] book) throws Exception {
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
