package kr.tna;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.org.OrgData.Employee;
import kr.tna.request.RequestData.BucketForm;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class LeaveBalanceSelectionWebIT {
    private static final String PASSWORD = "Synthetic balance selection 43!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private UUID tenant, lastEmployee, foreignEmployee;
    private String company;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        var fixture = DatabaseFixture.start();
        p.add("spring.datasource.url", fixture::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", fixture::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void companyWithThreeHundredEmployees() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        company = "BALANCE-" + tenant.toString().toUpperCase(Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 선택 고객사')", tenant, company);
        db.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on)
                SELECT ?,gen_random_uuid(),'E'||lpad(n::text,4,'0'),'합성 사원 '||n,DATE '2020-01-01'
                FROM generate_series(1,300) AS n
                """, tenant);
        UUID adminEmployee = employee("E0001");
        lastEmployee = employee("E0300");
        account(adminEmployee, "manager@example.test", true);
        account(lastEmployee, "worker@example.test", false);
        UUID foreignTenant = UUID.randomUUID();
        foreignEmployee = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'타 고객사')", foreignTenant, foreignTenant.toString());
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'FOREIGN-ONLY','타 고객사 사원',DATE '2020-01-01')", foreignTenant, foreignEmployee);
    }

    @Test
    void allThreeHundredEmployeesCanBeSelectedWithoutListPaginationTruncation() throws Exception {
        var result = mvc.perform(get("/admin/leave-balances").session(login("manager@example.test")))
                .andExpect(status().isOk()).andExpect(view().name("request/balances"))
                .andExpect(ReactPageAssertions.data("/employees/299/id", lastEmployee))
                .andReturn();
        var options = options(result.getModelAndView().getModel().get("employees"));
        assertEquals(300, options.size());
        assertEquals(lastEmployee, options.getLast().id());
        assertFalse(options.stream().anyMatch(e -> e.id().equals(foreignEmployee)));
    }

    @Test
    void lastEmployeeCanBeSelectedAndAnOpeningEntryCanBeCreated() throws Exception {
        var session = login("manager@example.test");
        mvc.perform(get("/admin/leave-balances").session(session).param("employeeId", lastEmployee.toString()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/form/employeeId", lastEmployee));
        UUID command = UUID.randomUUID();
        mvc.perform(post("/admin/leave-balances/entries").session(session).with(csrf())
                        .param("employeeId", lastEmployee.toString()).param("baseDate", TODAY.toString())
                        .param("usableFrom", TODAY.toString()).param("usableTo", TODAY.plusDays(30).toString())
                        .param("quantityDays", "5").param("sourceReference", "합성 기초 근거")
                        .param("reason", "마지막 사원의 기초 등록").param("commandId", command.toString()))
                .andExpect(status().isSeeOther());
        assertEquals(5, db.queryForObject("""
                SELECT sum(signed_quantity_days)::int FROM tna.leave_ledger
                WHERE tenant_id=? AND employee_id=? AND entry_kind='OPENING'
                """, Integer.class, tenant, lastEmployee));
    }

    @Test
    void invalidOpeningKeepsTheSelectedLateEmployeeAndTheFullSelector() throws Exception {
        var result = mvc.perform(post("/admin/leave-balances/entries").session(login("manager@example.test")).with(csrf())
                        .param("employeeId", lastEmployee.toString()).param("baseDate", TODAY.toString())
                        .param("usableFrom", TODAY.toString()).param("usableTo", TODAY.plusDays(30).toString())
                        .param("quantityDays", "-1").param("sourceReference", "유지할 합성 근거")
                        .param("reason", "음수 검증").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(view().name("request/balances"))
                .andReturn();
        var model = result.getModelAndView().getModel();
        assertEquals(300, options(model.get("employees")).size());
        assertEquals(lastEmployee, ((BucketForm) model.get("form")).employeeId());
        assertEquals("유지할 합성 근거", ((BucketForm) model.get("form")).sourceReference());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.leave_bucket WHERE tenant_id=?", Integer.class, tenant));
    }

    @Test
    void selectorRemainsAdministratorOnlyAndForeignTargetsStayHidden() throws Exception {
        mvc.perform(get("/admin/leave-balances").session(login("worker@example.test")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/leave-balances").session(login("manager@example.test"))
                        .param("employeeId", foreignEmployee.toString()))
                .andExpect(status().isNotFound());
    }

    @SuppressWarnings("unchecked")
    private List<Employee> options(Object value) { return (List<Employee>) value; }

    @Test
    void singleFormsAcceptExactHalfDaysAndReplayIntegerCommandsFromBeforeTheUpgrade() throws Exception {
        var session = login("manager@example.test");
        UUID command = UUID.randomUUID();
        singleOpening(session, "10", command).andExpect(status().isSeeOther());
        var bucket = onlyBucket();
        String legacyForm = "BucketForm[employeeId=" + lastEmployee + ", baseDate=" + TODAY + ", usableFrom=" + TODAY
                + ", usableTo=null, quantityDays=10, sourceReference=이관 원장, reason=기초 이관, commandId=" + command + "]";
        assertEquals(kr.tna.org.OrgCommands.fingerprint("LEAVE_BUCKET_OPEN", legacyForm), auditHash(command));
        singleOpening(session, "10.00", command).andExpect(status().isSeeOther());
        UUID adjust = UUID.randomUUID();
        singleAdjustment(session, bucket, "-1", 0, adjust).andExpect(status().isSeeOther());
        assertEquals(kr.tna.org.OrgCommands.fingerprint("LEAVE_BUCKET_ADJUST", bucket, -1, TODAY, 0L, "대조 원장", "잔여 대조"), auditHash(adjust));
        singleAdjustment(session, bucket, "-1.0", 0, adjust).andExpect(status().isSeeOther());
        singleAdjustment(session, bucket, "-1.5", 1, UUID.randomUUID()).andExpect(status().isSeeOther());
        for (String invalid : List.of("0.50000000000000001", "0.25", "100000.5", "-0.5")) {
            singleOpening(session, invalid, UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
        }
        for (String invalid : List.of("0", "-0.25", "-8", "0.50000000000000001")) {
            singleAdjustment(session, bucket, invalid, 2, UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
        }
        assertEquals(7.5, total(bucket));
        singleOpening(session, "7.5", UUID.randomUUID()).andExpect(status().isSeeOther());
        assertEquals(4, ledgerCount());
    }

    @Test
    void previewIsReadOnlyAndMixedEmployeeSaveReplaysTheSameReceipt() throws Exception {
        var session = login("manager@example.test");
        singleOpening(session, "3", UUID.randomUUID()).andExpect(status().isSeeOther());
        UUID bucket = onlyBucket(), command = UUID.randomUUID();
        var opening = opening("r1", "E0299", "7.5");
        var adjustment = adjustment("r2", "E0300", bucket, "-0.5");
        var options = mvc.perform(get("/admin/leave-balances/bulk/options").session(session).param("employeeNo", "E0300"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.buckets[0].id").value(bucket.toString()));
        assertEquals(3, json(options).at("/buckets/0/balanceDays").asDouble());
        var preview = json(batch(session, "preview", command, List.of(opening, adjustment)).andExpect(status().isOk()));
        assertTrue(preview.path("valid").asBoolean());
        assertEquals("합성 사원 299", preview.at("/rows/0/employeeName").asString());
        assertEquals(0, preview.at("/rows/0/beforeDays").asDouble()); assertEquals(7.5, preview.at("/rows/0/afterDays").asDouble());
        assertEquals(3, preview.at("/rows/1/beforeDays").asDouble()); assertEquals(2.5, preview.at("/rows/1/afterDays").asDouble());
        assertEquals(0, preview.at("/rows/1/expectedVersion").asLong()); assertEquals(1, ledgerCount());
        adjustment.put("expectedVersion", preview.at("/rows/1/expectedVersion").asLong());
        var rows = List.of(opening, adjustment);
        var receipt = json(batch(session, "save", command, rows).andExpect(status().isOk()));
        assertEquals(receipt, json(batch(session, "save", command, rows).andExpect(status().isOk())));
        var opened = UUID.fromString(receipt.at("/rows/0/id").asString());
        assertEquals(7.5, total(opened)); assertEquals(2.5, total(bucket)); assertEquals(3, ledgerCount());
        assertEquals(3, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND request_trace_id=?",
                Integer.class, tenant, UUID.fromString(receipt.path("batchId").asString())));
        adjustment.put("quantityDays", "-1");
        batch(session, "save", command, rows).andExpect(status().isConflict());
        assertEquals(3, ledgerCount());
    }

    @Test
    void stalePreviewAfterAnotherAdjustmentRejectsAllRows() throws Exception {
        var session = login("manager@example.test");
        singleOpening(session, "2", UUID.randomUUID()).andExpect(status().isSeeOther());
        UUID bucket = onlyBucket(), command = UUID.randomUUID();
        var adjustment = adjustment("old", "E0300", bucket, "-1");
        var rows = List.of(opening("new", "E0299", "5.5"), adjustment);
        var preview = json(batch(session, "preview", command, rows).andExpect(status().isOk()));
        assertTrue(preview.path("valid").asBoolean());
        singleAdjustment(session, bucket, "-0.5", 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        adjustment.put("expectedVersion", preview.at("/rows/1/expectedVersion").asLong());
        var conflict = json(batch(session, "save", command, rows).andExpect(status().isConflict()));
        assertEquals("expectedVersion", conflict.at("/rows/1/errors/0/field").asString());
        assertEquals(2, ledgerCount()); assertEquals(1.5, total(bucket));
        adjustment.remove("expectedVersion");
        batch(session, "save", command, rows).andExpect(status().isUnprocessableEntity());
        assertEquals(2, ledgerCount());
    }

    @Test
    void previewAndSaveReportInvalidRowsWithoutExposingOtherBalances() throws Exception {
        var session = login("manager@example.test");
        singleOpening(session, "1", UUID.randomUUID()).andExpect(status().isSeeOther());
        UUID bucket = onlyBucket(), command = UUID.randomUUID();
        var rows = List.of(opening("valid", "E0299", "7"), opening("fraction", "E0298", "0.50000000000000001"),
                opening("self", "E0001", "1"), opening("foreign", "FOREIGN-ONLY", "1"),
                adjustment("mismatch", "E0297", bucket, "-0.5"), adjustment("negative", "E0300", bucket, "-1.5"));
        rows.getLast().put("expectedVersion", 0);
        for (String action : List.of("preview", "save")) {
            var response = json(batch(session, action, command, rows).andExpect(status().is(action.equals("preview") ? 200 : 422)));
            assertFalse(response.path("valid").asBoolean()); assertTrue(response.at("/rows/0/errors").isEmpty());
            for (int i = 1; i < rows.size(); i++) assertFalse(response.at("/rows/" + i + "/errors").isEmpty());
            assertTrue(response.at("/rows/3/employeeName").isNull()); assertTrue(response.at("/rows/4/beforeDays").isNull());
        }
        assertEquals(1, ledgerCount());
        mvc.perform(get("/admin/leave-balances/bulk/options").session(session).param("employeeNo", "FOREIGN-ONLY")).andExpect(status().isNotFound());
        mvc.perform(get("/admin/leave-balances/bulk/options").session(session).param("employeeNo", "E0001")).andExpect(status().isForbidden());
        var worker = login("worker@example.test");
        for (String action : List.of("preview", "save")) batch(worker, action, command, rows).andExpect(status().isForbidden());
        mvc.perform(post("/admin/leave-balances/bulk/save").session(session).contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("commandId", command, "rows", rows)))).andExpect(status().isForbidden());
    }

    @Test
    void duplicateOpeningAndAdjustmentTargetsAndRoundedJsonNumbersAreRejected() throws Exception {
        var session = login("manager@example.test");
        singleOpening(session, "1", UUID.randomUUID()).andExpect(status().isSeeOther());
        UUID bucket = onlyBucket(), command = UUID.randomUUID();
        var rows = List.of(opening("a", "E0299", "5"), opening("b", "E0299", "5.0"),
                adjustment("c", "E0300", bucket, "1"), adjustment("d", "E0300", bucket, "0.5"));
        var preview = json(batch(session, "preview", command, rows).andExpect(status().isOk()));
        assertFalse(preview.path("valid").asBoolean());
        assertFalse(preview.at("/rows/1/errors").isEmpty()); assertFalse(preview.at("/rows/3/errors").isEmpty());
        batch(session, "save", command, rows).andExpect(status().isUnprocessableEntity());
        var raw = JSON.writeValueAsString(Map.of("commandId", command, "rows", List.of(opening("nearhalf", "E0299", "QTY"))))
                .replace("\"QTY\"", "0.50000000000000001");
        mvc.perform(post("/admin/leave-balances/bulk/preview").session(session).with(csrf()).contentType("application/json").content(raw))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.rows[0].errors[0].field").value("quantityDays"));
        assertEquals(1, ledgerCount());
    }

    @Test
    void databaseFailureOnSecondEntryRollsBackEarlierBucketLedgerAndAudit() throws Exception {
        var session = login("manager@example.test");
        UUID command = UUID.randomUUID();
        var rows = List.of(opening("first", "E0299", "7.5"), opening("second", "E0300", "3.5"));
        assertTrue(json(batch(session, "preview", command, rows).andExpect(status().isOk())).path("valid").asBoolean());
        String constraint = "fixture_balance_" + tenant.toString().replace("-", "");
        db.execute("ALTER TABLE tna.leave_ledger ADD CONSTRAINT " + constraint + " CHECK (tenant_id <> '" + tenant
                + "'::uuid OR employee_id <> '" + lastEmployee + "'::uuid) NOT VALID");
        try {
            batch(session, "save", command, rows).andExpect(status().isConflict());
            for (String table : List.of("leave_bucket", "leave_ledger", "org_audit_event"))
                assertEquals(0, db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Integer.class, tenant));
        } finally {
            db.execute("ALTER TABLE tna.leave_ledger DROP CONSTRAINT " + constraint);
        }
        batch(session, "save", command, rows).andExpect(status().isOk());
        assertEquals(2, ledgerCount());
    }

    private ResultActions singleOpening(MockHttpSession session, String quantity, UUID command) throws Exception {
        return mvc.perform(post("/admin/leave-balances/entries").session(session).with(csrf())
                .param("employeeId", lastEmployee.toString()).param("baseDate", TODAY.toString()).param("usableFrom", TODAY.toString())
                .param("quantityDays", quantity).param("sourceReference", "이관 원장").param("reason", "기초 이관").param("commandId", command.toString()));
    }
    private ResultActions singleAdjustment(MockHttpSession session, UUID bucket, String quantity, long version, UUID command) throws Exception {
        return mvc.perform(post("/admin/leave-balances/buckets/{id}/adjustments", bucket).session(session).with(csrf())
                .param("effectiveOn", TODAY.toString()).param("expectedVersion", String.valueOf(version)).param("quantityDays", quantity)
                .param("sourceReference", "대조 원장").param("reason", "잔여 대조").param("commandId", command.toString()));
    }
    private ResultActions batch(MockHttpSession session, String action, UUID command, List<Map<String,Object>> rows) throws Exception {
        return mvc.perform(post("/admin/leave-balances/bulk/" + action).session(session).with(csrf()).contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("commandId", command, "rows", rows))));
    }
    private static JsonNode json(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsByteArray());
    }
    private static Map<String,Object> opening(String rowId, String employeeNo, String quantity) {
        return new java.util.HashMap<>(Map.of("clientRowId", rowId, "operation", "OPENING", "employeeNo", employeeNo,
                "quantityDays", quantity, "effectiveOn", TODAY, "usableFrom", TODAY, "sourceReference", "이관 원장", "reason", "기초 이관"));
    }
    private static Map<String,Object> adjustment(String rowId, String employeeNo, UUID bucket, String quantity) {
        return new java.util.HashMap<>(Map.of("clientRowId", rowId, "operation", "ADJUSTMENT", "employeeNo", employeeNo,
                "quantityDays", quantity, "effectiveOn", TODAY, "bucketId", bucket, "sourceReference", "대조 원장", "reason", "잔여 대조"));
    }
    private UUID onlyBucket() {return db.queryForObject("SELECT id FROM tna.leave_bucket WHERE tenant_id=?", UUID.class, tenant);}
    private double total(UUID bucket) {return db.queryForObject("SELECT sum(signed_quantity_days) FROM tna.leave_ledger WHERE tenant_id=? AND bucket_id=?", Double.class, tenant, bucket);}
    private int ledgerCount() {return db.queryForObject("SELECT count(*) FROM tna.leave_ledger WHERE tenant_id=?", Integer.class, tenant);}
    private String auditHash(UUID command) {return db.queryForObject("SELECT input_hash FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?", String.class, tenant, command);}

    private UUID employee(String number) {
        return db.queryForObject("SELECT id FROM tna.employee WHERE tenant_id=? AND employee_no=?", UUID.class, tenant, number);
    }

    private void account(UUID employee, String email, boolean administrator) {
        UUID account = UUID.randomUUID();
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)", tenant, account, employee, email, HASH);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, account);
        if (administrator) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')", tenant, account);
    }

    private MockHttpSession login(String email) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("companyCode", company)
                        .param("email", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);
    }
}
