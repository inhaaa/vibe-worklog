package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.EmployeeImportRows.Row;
import kr.tna.org.EmployeeImportService;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@AutoConfigureMockMvc
class EmployeeImportWebIT {
    @Autowired EmployeeImportService imports;
    @Autowired MockMvc mvc;
    JdbcTemplate db;
    UUID tenant;
    kr.tna.common.auth.TnaPrincipal manager;
    UUID team;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void seed() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 일괄 등록')",
                tenant, "IMPORT-" + tenant);
        manager = member("ADMIN", java.util.Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        team = organization("D10", null);
    }

    static Row row(String id, String no, String name, String joined, String birth, String org) {
        return new Row(id, no, name, joined, birth, org, "사원", "");
    }

    @Test void previewIssuesNumbersAcrossTheBatchAndFlagsDatabaseConflicts() {
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'19001','기존',DATE '2019-01-01')",
                tenant, UUID.randomUUID());
        var preview = imports.preview(manager, List.of(
                row("a", "", "가", "2019-03-04", "1990-01-01", "D10"),
                row("b", "", "나", "2019-05-01", "1991-01-01", "D10"),
                row("c", "19001", "다", "2019-06-01", "1992-01-01", "D10"),
                row("d", "77", "라", "2019-06-01", "1992-01-01", "D10"),
                row("e", "77", "마", "2019-06-01", "1992-01-01", "D10"),
                row("f", "", "바", "2019-06-01", "1992-01-01", "NOPE")));
        assertFalse(preview.valid());
        assertEquals("19002", preview.rows().get(0).employeeNo());
        assertEquals("19003", preview.rows().get(1).employeeNo());
        assertEquals("이미 등록된 사번입니다.", preview.rows().get(2).errors().getFirst().message());
        assertEquals("붙여넣은 행 안에서 사번이 겹칩니다.", preview.rows().get(3).errors().getFirst().message());
        assertEquals("활성 조직 코드가 아닙니다.", preview.rows().get(5).errors().getFirst().message());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=?", Integer.class, tenant));
    }

    @Test void previewRejectsEmptyAndOversizedBatches() {
        var tooMany = new java.util.ArrayList<Row>();
        for (int i = 0; i < 501; i++) tooMany.add(row("r" + i, "", "가", "2019-03-04", "1990-01-01", "D10"));
        assertEquals(422, assertThrows(OrgException.class, () -> imports.preview(manager, tooMany)).status());
    }

    private UUID organization(String code, UUID parent) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,parent_id,created_by,updated_by) VALUES (?,?,?,?,?,?,?)",
                tenant, id, code, "원본 " + code, parent, manager.accountId(), manager.accountId());
        return id;
    }

    private kr.tna.common.auth.TnaPrincipal member(String number, java.util.Set<String> roles) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 시트 사원',?)",
                tenant, id, number, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, id, account + "@example.test", "synthetic-sheet-password-hash");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new kr.tna.common.auth.TnaPrincipal(tenant, account, id, 1, roles, java.time.Instant.now());
    }
    @Test void saveCreatesEverythingOnceAndReplaysTheSameResult() {
        UUID command = UUID.randomUUID();
        var form = new EmployeeImportService.Form(command, "기존 재직자 이관", List.of(
                row("a", "", "가", "2019-03-04", "1990-01-01", "D10"),
                row("b", "20100", "나", "2020-01-02", "1991-02-03", "D10")));
        var first = imports.save(manager, form);
        var again = imports.save(manager, form);
        assertEquals(first, again);
        assertEquals(List.of("19001", "20100"), first.employeeNos());
        assertEquals(2, first.accounts());
        var joined = java.util.Map.of("19001", LocalDate.of(2019, 3, 4), "20100", LocalDate.of(2020, 1, 2));
        for (String number : first.employeeNos()) {
            UUID employee = db.queryForObject("SELECT id FROM tna.employee WHERE tenant_id=? AND employee_no=?", UUID.class, tenant, number);
            assertEquals(team, db.queryForObject("""
                    SELECT organization_id FROM tna.employee_assignment WHERE tenant_id=? AND employee_id=? AND valid_from=?
                    """, UUID.class, tenant, employee, joined.get(number)));
            assertEquals(1, db.queryForObject("""
                    SELECT count(*) FROM tna.employee_personnel WHERE tenant_id=? AND employee_id=? AND birth_date IS NOT NULL AND grade_name='사원'
                    """, Integer.class, tenant, employee));
            assertEquals(1, db.queryForObject("""
                    SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id=? AND NOT active AND email IS NULL
                      AND activation_expires_at > now()
                    """, Integer.class, tenant, employee));
        }
        String audit = db.queryForObject("""
                SELECT string_agg(before_state::text||after_state::text,'') FROM tna.org_audit_event
                WHERE tenant_id=? AND action='EMPLOYEE_IMPORT'
                """, String.class, tenant);
        for (String secret : new String[] {"1990-01-01", "1991-02-03", "19900101", "19910203"}) assertFalse(audit.contains(secret), secret);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='EMPLOYEE_IMPORT'", Integer.class, tenant));
    }

    @Test void autoNumberFollowsManualNumberInTheSameYear() {
        var preview = imports.preview(manager, List.of(
                row("a", "19050", "가", "2019-03-04", "1990-01-01", "D10"),
                row("b", "", "나", "2019-03-05", "1990-01-01", "D10")));
        assertTrue(preview.valid());
        assertEquals("19051", preview.rows().get(1).employeeNo());
    }

    @Test void clientRowIdMustBeUniqueNonBlankAndShort() {
        var preview = imports.preview(manager, List.of(
                row("a", "", "가", "2019-03-04", "1990-01-01", "D10"),
                row("a", "", "나", "2019-03-04", "1990-01-01", "D10"),
                row(" ", "", "다", "2019-03-04", "1990-01-01", "D10"),
                row("x".repeat(65), "", "라", "2019-03-04", "1990-01-01", "D10"),
                row("x".repeat(64), "", "마", "2019-03-04", "1990-01-01", "D10")));
        assertTrue(preview.rows().get(0).errors().isEmpty());
        for (int i = 1; i <= 3; i++) assertEquals("clientRowId", preview.rows().get(i).errors().getFirst().field(), "row " + i);
        assertTrue(preview.rows().get(4).errors().isEmpty());
    }

    @Test void nullRowIsRejectedWithoutServerError() {
        var rows = new java.util.ArrayList<Row>();
        rows.add(row("a", "", "가", "2019-03-04", "1990-01-01", "D10"));
        rows.add(null);
        assertEquals(422, assertThrows(OrgException.class, () -> imports.preview(manager, rows)).status());
        assertEquals(422, assertThrows(OrgException.class,
                () -> imports.save(manager, new EmployeeImportService.Form(UUID.randomUUID(), "이관", rows))).status());
    }

    @Test void sameCommandWithDifferentRowsConflicts() {
        UUID command = UUID.randomUUID();
        imports.save(manager, new EmployeeImportService.Form(command, "이관", List.of(row("a", "", "가", "2019-03-04", "1990-01-01", "D10"))));
        var error = assertThrows(OrgException.class, () -> imports.save(manager,
                new EmployeeImportService.Form(command, "이관", List.of(row("a", "", "나", "2019-03-04", "1990-01-01", "D10")))));
        assertEquals(409, error.status());
    }

    @Test void saveChecksAgainWhenANumberWasTakenAfterPreview() {
        var form = new EmployeeImportService.Form(UUID.randomUUID(), "이관", List.of(row("a", "19700", "가", "2019-03-04", "1990-01-01", "D10")));
        assertTrue(imports.preview(manager, form.rows()).valid());
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'19700','선점',DATE '2019-01-01')",
                tenant, UUID.randomUUID());
        var error = assertThrows(EmployeeImportService.ImportProblem.class, () -> imports.save(manager, form));
        assertEquals("employeeNo", error.preview().rows().getFirst().errors().getFirst().field());
    }

    @Test void closedMonthBlocksJoinDatesAtOrBeforeIt() {
        db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                VALUES (?,?,DATE '2019-04-01',1,'CLOSED','합성 마감',?,'합성')
                """, tenant, UUID.randomUUID(), manager.accountId());
        var preview = imports.preview(manager, List.of(
                row("a", "", "가", "2019-03-04", "1990-01-01", "D10"),
                row("b", "", "나", "2019-04-30", "1990-01-01", "D10"),
                row("c", "", "다", "2019-05-01", "1990-01-01", "D10")));
        assertEquals("joinedOn", preview.rows().get(0).errors().getFirst().field());
        assertEquals("2019-04 근태가 마감됐습니다. 근태 담당자가 해당 월을 재개한 뒤 다시 처리해 주세요.",
                preview.rows().get(1).errors().getFirst().message());
        assertTrue(preview.rows().get(2).errors().isEmpty());
    }

    @Test void anyInvalidRowCancelsTheWholeBatch() {
        var form = new EmployeeImportService.Form(UUID.randomUUID(), "이관", List.of(
                row("a", "", "가", "2019-03-04", "1990-01-01", "D10"),
                row("b", "", "나", "2019-03-04", "2019-03-04", "D10")));
        var error = assertThrows(EmployeeImportService.ImportProblem.class, () -> imports.save(manager, form));
        assertEquals(422, error.status());
        assertEquals("birthDate", error.preview().rows().get(1).errors().getFirst().field());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=?", Integer.class, tenant));
    }

    @Test void pageTemplateAndJsonEndpointsFollowTheContract() throws Exception {
        mvc.perform(authenticated(get("/admin/employees/import"), manager))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.page())
                .andExpect(ReactPageAssertions.data("/employeeImport/maxRows", 500))
                .andExpect(ReactPageAssertions.data("/employeeImport/organizations/0/code", "D10"));
        var template = mvc.perform(authenticated(get("/admin/employees/import/template"), manager))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=employee-import.csv"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, java.util.Arrays.copyOf(template, 3));
        assertTrue(new String(template, java.nio.charset.StandardCharsets.UTF_8).contains("사번,이름,입사일,생년월일,조직코드,직급,직책"));

        String body = """
                {"rows":[{"clientRowId":"a","employeeNo":"","displayName":"가","joinedOn":"2019-03-04","birthDate":"2019-03-04","organizationCode":"D10","gradeName":"","positionName":""}]}
                """;
        mvc.perform(authenticated(post("/admin/employees/import/preview"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.rows[0].errors[0].field").value("birthDate"));
        String save = body.replace("{\"rows\"", "{\"commandId\":\"" + UUID.randomUUID() + "\",\"reason\":\"이관\",\"rows\"");
        mvc.perform(authenticated(post("/admin/employees/import/save"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(save))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.rows[0].errors[0].field").value("birthDate"));
        mvc.perform(authenticated(post("/admin/employees/import/save"), manager)
                        .contentType(MediaType.APPLICATION_JSON).content(save))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(post("/admin/employees/import/preview"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.message").value("1~500행을 붙여넣어 주세요."));
    }

    @Test void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(authenticated(post("/admin/employees/import/preview"), manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content("{\"rows\":["))
                .andExpect(status().isBadRequest());
    }

    @Test void employeeOnlyAccountCannotUseTheImport() throws Exception {
        var plain = member("PLAIN", java.util.Set.of("EMPLOYEE"));
        mvc.perform(authenticated(get("/admin/employees/import"), plain)).andExpect(status().isForbidden());
        mvc.perform(authenticated(post("/admin/employees/import/preview"), plain).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}"))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, kr.tna.common.auth.TnaPrincipal actor) {
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList())));
    }
}
