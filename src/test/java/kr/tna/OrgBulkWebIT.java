package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.BulkData;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgData;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class OrgBulkWebIT {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired OrgCommands commands;
    private JdbcTemplate database;
    private UUID tenant;
    private TnaPrincipal manager;
    private LocalDate today;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var fixture = DatabaseFixture.start();
        properties.add("spring.datasource.url", fixture::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
        properties.add("tna.auth.url", fixture::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void syntheticCompany() {
        database = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        tenant = UUID.randomUUID();
        database.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 시트 회사')",
                tenant, "SHEET-" + tenant);
        manager = member("ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    }

    @Test
    void blankOrganizationCodesGetAvailableNumbersAndReceiptOutsideTheFilter() throws Exception {
        String year = Integer.toString(today.getYear());
        organization(year + "0001", null);
        organization(year + "9999", null);
        database.update("UPDATE tna.organization SET active=false WHERE tenant_id=? AND code=?", tenant, year + "0001");
        var first = organizationRow(null, "", "신규 운영팀", null, "CREATE", null);
        var second = organizationRow(null, "", "신규 기획팀", null, "CREATE", null);
        second.remove("code");
        var existing = organization("ROOT", null);
        var update = organizationRow(existing, "", "이름을 바꾼 조직", null, "UPDATE", 0L);
        var command = UUID.randomUUID();

        var saved = mvc.perform(bulk("organizations", manager, command, List.of(first, second, update))
                        .param("q", "검색 결과에 없는 조직").param("page", "2"))
                .andExpect(status().isSeeOther()).andReturn();
        var location = java.net.URI.create(saved.getResponse().getRedirectedUrl());
        var mapping = result(command);
        mvc.perform(authenticated(get(location), manager)).andExpect(status().isOk())
                .andExpect(model().attribute("organizations", List.of()))
                .andExpect(ReactPageAssertions.data("/createdOrganizations/0/clientRowId", first.get("clientRowId")))
                .andExpect(ReactPageAssertions.data("/createdOrganizations/0/id", mapping.at("/rows/0/id").asString()))
                .andExpect(ReactPageAssertions.data("/createdOrganizations/0/code", year + "0002"))
                .andExpect(ReactPageAssertions.data("/createdOrganizations/1/code", year + "0003"))
                .andExpect(result -> assertEquals(2, ReactPageAssertions.envelope(result).at("/data/createdOrganizations").size()));
        assertEquals("ROOT", database.queryForObject("SELECT code FROM tna.organization WHERE id=?", String.class, existing));
    }

    @Test
    void exhaustingOrganizationCodesRollsBackAllNewRowsAndPointsToTheFailedRow() throws Exception {
        String year = Integer.toString(today.getYear());
        database.update("""
                INSERT INTO tna.organization(tenant_id,id,code,name,active,created_by,updated_by)
                SELECT ?,gen_random_uuid(),? || lpad(sequence::text,4,'0'),'발급 번호가 있는 기존 조직',false,?,?
                FROM generate_series(2,9999) AS sequence
                """, tenant, year, manager.accountId(), manager.accountId());
        var first = organizationRow(null, "", "마지막 번호 후보", null, "CREATE", null);
        var second = organizationRow(null, "", "한도 초과 조직", null, "CREATE", null);
        mvc.perform(bulk("organizations", manager, UUID.randomUUID(), List.of(first, second)))
                .andExpect(status().is(422))
                .andExpect(ReactPageAssertions.data("/organizationSheet/errors/0/clientRowId", second.get("clientRowId")))
                .andExpect(ReactPageAssertions.data("/organizationSheet/errors/0/field", "code"));
        assertEquals(9_998, count("tna.organization"));
        assertEquals(0, count("tna.org_audit_event"));
        mvc.perform(bulk("organizations", manager, UUID.randomUUID(), List.of(first)))
                .andExpect(status().isSeeOther());
        assertEquals(1, database.queryForObject("SELECT count(*) FROM tna.organization WHERE tenant_id=? AND code=?",
                Integer.class, tenant, year + "0001"));
    }

    @Test
    void fiftyOrganizationCreatesCommitOneBatchAndReplayTheSameSafeIdVersionMapping() throws Exception {
        UUID command = UUID.randomUUID();
        var rows = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 50; index++) rows.add(organizationRow(null, "", "조직 " + index, null, "CREATE", null));
        mvc.perform(bulk("organizations", manager, command, rows)).andExpect(status().isSeeOther());
        JsonNode original = result(command);
        assertEquals(50, original.get("rows").size());
        assertEquals(50, count("tna.organization"));
        UUID batchId = UUID.fromString(original.get("batchId").asString());
        assertEquals(51, database.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND request_trace_id=?",
                Integer.class, tenant, batchId));
        for (int index = 0; index < 50; index++) {
            assertEquals(rows.get(index).get("clientRowId"), original.at("/rows/" + index + "/clientRowId").asString());
            assertEquals(0, original.at("/rows/" + index + "/version").asLong());
            assertTrue(original.at("/rows/" + index + "/id").isString());
        }
        mvc.perform(bulk("organizations", manager, command, rows)).andExpect(status().isSeeOther());
        assertEquals(original, result(command));
        assertEquals(50, count("tna.organization"));
        assertEquals(51, count("tna.org_audit_event"));
        mvc.perform(authenticated(get("/admin/organizations"), manager)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.dataPresent("/organizationSheet/rows"))
                .andExpect(ReactPageAssertions.dataPresent("/organizationSheet/latestRows"));
    }

    @Test
    void fiftyOneChangesAreRejectedForBothSheetsWithoutWritingAnything() throws Exception {
        var organizations = new ArrayList<Map<String, Object>>();
        var employees = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 51; index++) {
            organizations.add(organizationRow(null, "", "초과 조직", null, "CREATE", null));
            employees.add(employeeRow(null, "E" + index, "초과 사원", "CREATE", null));
        }
        mvc.perform(bulk("organizations", manager, UUID.randomUUID(), organizations)).andExpect(status().is(422))
                .andExpect(ReactPageAssertions.dataPresent("/organizationSheet/errors"));
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), employees)).andExpect(status().is(422))
                .andExpect(ReactPageAssertions.dataPresent("/employeeSheet/errors"));
        assertEquals(0, count("tna.organization"));
        assertEquals(1, count("tna.employee"));
        assertEquals(0, count("tna.org_audit_event"));
    }

    @Test
    void automaticEmployeeNumbersReserveManualRowsAndReplayWithoutIssuingAgain() throws Exception {
        var automatic = employeeRow(null, "", "자동 하나", "CREATE", null);
        var whitespace = employeeRow(null, "  ", "자동 둘", "CREATE", null);
        var manual = employeeRow(null, "26005", "수동 사원", "CREATE", null);
        var previousYear = employeeRow(null, null, "전년도 사원", "CREATE", null);
        for (var row : List.of(automatic, whitespace, manual)) row.put("joinedOn", "2026-01-01");
        previousYear.put("joinedOn", "2025-01-01");
        var rows = List.of(automatic, whitespace, manual, previousYear);
        UUID command = UUID.randomUUID();
        mvc.perform(bulk("employees", manager, command, rows)).andExpect(status().isSeeOther());
        JsonNode original = result(command);
        assertEquals(List.of("25001", "26005", "26006", "26007"), database.queryForList(
                "SELECT employee_no FROM tna.employee WHERE tenant_id=? AND id<>? ORDER BY employee_no", String.class, tenant, manager.employeeId()));
        mvc.perform(bulk("employees", manager, command, rows)).andExpect(status().isSeeOther());
        assertEquals(original, result(command));
        assertEquals(5, count("tna.employee"));
        assertEquals(5, count("tna.org_audit_event"));
    }

    @Test
    void exhaustedAutomaticNumberRollsBackWholeBulkAndIdentifiesTheField() throws Exception {
        member("26999", Set.of("EMPLOYEE"));
        var manual = employeeRow(null, "LEGACY", "되돌릴 수동 사원", "CREATE", null);
        var automatic = employeeRow(null, null, "초과 사원", "CREATE", null);
        automatic.put("joinedOn", "2026-01-01");
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(manual, automatic)))
                .andExpect(status().is(422))
                .andExpect(ReactPageAssertions.data("/employeeSheet/errors/0/clientRowId", automatic.get("clientRowId")))
                .andExpect(ReactPageAssertions.data("/employeeSheet/errors/0/field", "employeeNo"));
        assertEquals(2, count("tna.employee"));
        assertEquals(0, count("tna.org_audit_event"));
    }

    @Test
    void organizationValidationUsesTheFinalGraphInsteadOfSequentialSingleRowUpdates() throws Exception {
        UUID parent = organization("A", null), child = organization("B", parent);
        var parentRow = organizationRow(parent, "A", "새 자식", child, "UPDATE", 0L);
        var childRow = organizationRow(child, "B", "새 부모", null, "UPDATE", 0L);
        mvc.perform(bulk("organizations", manager, UUID.randomUUID(), List.of(parentRow, childRow)))
                .andExpect(status().isSeeOther());
        assertEquals(child, organizationParent(parent));
        assertNull(organizationParent(child));
        parentRow.put("expectedVersion", 1L);
        childRow.put("expectedVersion", 1L);
        parentRow.put("operation", "DEACTIVATE");
        childRow.put("operation", "DEACTIVATE");
        mvc.perform(bulk("organizations", manager, UUID.randomUUID(), List.of(childRow, parentRow)))
                .andExpect(status().isSeeOther());
        assertEquals(0, database.queryForObject("SELECT count(*) FROM tna.organization WHERE tenant_id=? AND active",
                Integer.class, tenant));
        assertEquals(child, organizationParent(parent));
    }

    @Test
    void finalCycleAndCurrentAssignmentsRejectTheWholeOrganizationBatchAndPreserveDrafts() throws Exception {
        UUID first = organization("A", null), second = organization("B", null);
        UUID command = UUID.randomUUID();
        var firstRow = organizationRow(first, "A", "편집 A", second, "UPDATE", 0L);
        var secondRow = organizationRow(second, "B", "편집 B", first, "UPDATE", 0L);
        var response = mvc.perform(bulk("organizations", manager, command, List.of(firstRow, secondRow)))
                .andExpect(status().is(422)).andExpect(view().name("org/organizations"))
                .andExpect(ReactPageAssertions.data("/organizationSheet/commandId", command))
                .andExpect(ReactPageAssertions.dataPresent("/organizationSheet/errors")).andReturn();
        assertEquals("편집 A", sheetRow(response, "organizationSheet", firstRow).get("name").asString());
        assertEquals(second.toString(), sheetRow(response, "organizationSheet", firstRow).get("parentId").asString());
        assertNull(organizationParent(first));
        assertNull(organizationParent(second));
        assertEquals(0, count("tna.org_audit_event"));
        database.update("""
                INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'합성 소속 보존',?,?)
                """, tenant, UUID.randomUUID(), manager.employeeId(), first, today, manager.accountId(), manager.accountId());
        firstRow.put("parentId", null);
        firstRow.put("operation", "DEACTIVATE");
        secondRow.put("parentId", null);
        mvc.perform(bulk("organizations", manager, UUID.randomUUID(), List.of(secondRow, firstRow)))
                .andExpect(status().is(422));
        assertEquals(1, count("tna.employee_assignment"));
        assertEquals(0, count("tna.org_audit_event"));
    }

    @Test
    void lastInvalidEmployeeRollsBackEarlierDraftsVersionsSessionsAndAudit() throws Exception {
        TnaPrincipal target = member("E001", Set.of("EMPLOYEE"));
        UUID command = UUID.randomUUID();
        var deactivate = employeeRow(target.employeeId(), "E001", "비활성 예정", "DEACTIVATE", 0L);
        var create = employeeRow(null, "NEW-1", "신규 예정", "CREATE", null);
        var invalid = employeeRow(null, "NEW-2", " ", "CREATE", null);
        var response = mvc.perform(bulk("employees", manager, command, List.of(deactivate, create, invalid)))
                .andExpect(status().is(422)).andExpect(view().name("org/employees"))
                .andExpect(ReactPageAssertions.data("/employeeSheet/commandId", command))
                .andExpect(ReactPageAssertions.dataPresent("/employeeSheet/errors")).andReturn();
        assertEquals("DEACTIVATE", sheetRow(response, "employeeSheet", deactivate).get("operation").asString());
        assertEquals(" ", sheetRow(response, "employeeSheet", invalid).get("displayName").asString());
        assertEquals(2, count("tna.employee"));
        assertEquals(0, count("tna.org_audit_event"));
        assertEquals(0, employeeVersion(target.employeeId()));
        assertEquals(1, authVersion(target));
        assertTrue(employeeActive(target.employeeId()));
        invalid.put("displayName", "올바른 신규 사원");
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(deactivate, create, invalid)))
                .andExpect(status().isSeeOther());
        assertEquals(4, count("tna.employee"));
        assertEquals(2, count("tna.tenant_account"));
        assertFalse(employeeActive(target.employeeId()));
        assertEquals(1, employeeVersion(target.employeeId()));
        assertEquals(2, authVersion(target));
    }

    @Test
    void employeeBatchCannotRemoveAllAdministratorsAndCanReactivateTheirReplacementAtomically() throws Exception {
        TnaPrincipal replacement = member("ADMIN-2", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        var first = employeeRow(manager.employeeId(), "ADMIN", "관리자 1", "DEACTIVATE", 0L);
        var second = employeeRow(replacement.employeeId(), "ADMIN-2", "관리자 2", "DEACTIVATE", 0L);
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(first, second)))
                .andExpect(status().is(422));
        assertEquals(1, authVersion(manager));
        assertEquals(1, authVersion(replacement));
        assertEquals(0, count("tna.org_audit_event"));
        database.update("UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?", tenant, replacement.employeeId());
        second.put("operation", "UPDATE");
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(first, second)))
                .andExpect(status().isSeeOther());
        assertFalse(employeeActive(manager.employeeId()));
        assertTrue(employeeActive(replacement.employeeId()));
        assertEquals(2, authVersion(manager));
        assertEquals(2, authVersion(replacement));
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(first))).andExpect(status().isUnauthorized());
        assertEquals(3, count("tna.org_audit_event"));
    }

    @Test
    void nameOnlyChangesKeepSessionsButEmploymentChangesRevokeThemAndReplayDoesNotRepeatEffects() throws Exception {
        TnaPrincipal target = member("E001", Set.of("EMPLOYEE"));
        var row = employeeRow(target.employeeId(), "E001", "이름 편집", "UPDATE", 0L);
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(row))).andExpect(status().isSeeOther());
        assertEquals(1, authVersion(target));
        row.put("expectedVersion", 1L);
        row.put("joinedOn", today.minusYears(1).plusDays(1).toString());
        UUID command = UUID.randomUUID();
        mvc.perform(bulk("employees", manager, command, List.of(row))).andExpect(status().isSeeOther());
        JsonNode original = result(command);
        mvc.perform(bulk("employees", manager, command, List.of(row))).andExpect(status().isSeeOther());
        assertEquals(original, result(command));
        assertEquals(2, employeeVersion(target.employeeId()));
        assertEquals(2, authVersion(target));
        row.put("displayName", "같은 명령의 다른 내용");
        mvc.perform(bulk("employees", manager, command, List.of(row))).andExpect(status().isConflict())
                .andExpect(ReactPageAssertions.data("/employeeSheet/commandId", command));
        assertEquals(2, employeeVersion(target.employeeId()));
    }

    @Test
    void rootAuditFailureRollsBackWrittenEmployeesSessionVersionsAndEarlierRowAudits() throws Exception {
        TnaPrincipal target = member("E001", Set.of("EMPLOYEE"));
        var row = employeeRow(target.employeeId(), "E001", "롤백할 이름", "UPDATE", 0L);
        row.put("joinedOn", today.minusYears(1).plusDays(1).toString());
        var create = employeeRow(null, "NEW", "롤백할 신규 사원", "CREATE", null);
        UUID command = UUID.randomUUID();
        String constraint = "bulk_audit_" + UUID.randomUUID().toString().replace("-", "");
        database.execute("ALTER TABLE tna.org_audit_event ADD CONSTRAINT " + constraint
                + " CHECK (tenant_id <> '" + tenant + "'::uuid OR action <> 'EMPLOYEE_BULK') NOT VALID");
        try {
            mvc.perform(bulk("employees", manager, command, List.of(row, create)))
                    .andExpect(status().isConflict())
                    .andExpect(ReactPageAssertions.data("/employeeSheet/commandId", command));
            assertEquals(2, count("tna.employee"));
            assertEquals(0, employeeVersion(target.employeeId()));
            assertEquals(1, authVersion(target));
            assertEquals("합성 시트 사원", database.queryForObject("SELECT display_name FROM tna.employee WHERE tenant_id=? AND id=?",
                    String.class, tenant, target.employeeId()));
            assertEquals(0, count("tna.org_audit_event"));
        } finally {
            database.execute("ALTER TABLE tna.org_audit_event DROP CONSTRAINT " + constraint);
        }
        mvc.perform(bulk("employees", manager, command, List.of(row, create))).andExpect(status().isSeeOther());
        assertEquals(3, count("tna.employee"));
        assertEquals(1, employeeVersion(target.employeeId()));
        assertEquals(2, authVersion(target));
        assertEquals(3, count("tna.org_audit_event"));
    }

    @Test
    void replayIncludesNormalizedRowOrderAndTheOriginalAdministrator() throws Exception {
        var first = organizationRow(null, "", "첫 조직", null, "CREATE", null);
        var second = organizationRow(null, "", "둘째 조직", null, "CREATE", null);
        UUID command = UUID.randomUUID();
        mvc.perform(bulk("organizations", manager, command, List.of(first, second))).andExpect(status().isSeeOther());
        var original = result(command);
        first.put("name", "  첫 조직  ");
        mvc.perform(bulk("organizations", manager, command, List.of(first, second))).andExpect(status().isSeeOther());
        assertEquals(original, result(command));
        mvc.perform(bulk("organizations", manager, command, List.of(second, first))).andExpect(status().isConflict());
        var other = member("OTHER-ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        mvc.perform(bulk("organizations", other, command, List.of(first, second))).andExpect(status().isConflict());
        assertEquals(2, count("tna.organization"));
        assertEquals(3, count("tna.org_audit_event"));
    }

    @Test
    void oldManualBatchReplaysAndReportsOnlyItsNewOrganizationsToItsAdministrator() throws Exception {
        var row = organizationRow(null, "ROOT", "원본 ROOT", null, "CREATE", null);
        var clientRowId = (String) row.get("clientRowId");
        UUID id = organization("ROOT", null), command = UUID.randomUUID();
        String action = "ORGANIZATION_BULK", reason = "합성 시트 원자 저장";
        var oldRequest = new BulkData.OrganizationRow(clientRowId, null, null, BulkData.Operation.CREATE,
                "ROOT", "원본 ROOT", null, true);
        commands.executeBatch(manager, command, action, "ORGANIZATION",
                OrgCommands.fingerprint(action, reason, JSON.writeValueAsString(List.of(oldRequest))), reason,
                () -> List.of(new BulkData.Change(clientRowId, id, 0, null,
                        new OrgData.Organization(id, "ROOT", "원본 ROOT", null, true, 0))));
        var response = mvc.perform(bulk("organizations", manager, command, List.of(row)))
                .andExpect(status().isSeeOther()).andReturn();
        var location = java.net.URI.create(response.getResponse().getRedirectedUrl());
        mvc.perform(authenticated(get(location), manager)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/createdOrganizations/0/code", "ROOT"))
                .andExpect(ReactPageAssertions.data("/createdOrganizations/0/id", id));
        var other = member("OTHER-ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        mvc.perform(authenticated(get(location), other)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/createdOrganizations", List.of()));
        assertEquals(1, count("tna.organization"));
        assertEquals(2, count("tna.org_audit_event"));
    }

    @Test
    void simultaneousSingleAndBulkOrganizationCreatesGetDistinctCodes() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var first = authenticated(post("/admin/organizations"), manager).with(csrf())
                .param("name", "동시 단건").param("active", "true")
                .param("commandId", UUID.randomUUID().toString()).param("reason", "합성 동시 등록");
        var second = bulk("organizations", manager, UUID.randomUUID(),
                List.of(organizationRow(null, "", "동시 시트", null, "CREATE", null)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var single = executor.submit(() -> createAtTheSameTime(first, ready, start));
            var batch = executor.submit(() -> createAtTheSameTime(second, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(List.of(303, 303), List.of(single.get(20, TimeUnit.SECONDS), batch.get(20, TimeUnit.SECONDS)));
        }
        String prefix = Integer.toString(today.getYear());
        assertEquals(List.of(prefix + "0001", prefix + "0002"), database.queryForList(
                "SELECT code FROM tna.organization WHERE tenant_id=? ORDER BY code", String.class, tenant));
    }

    private int createAtTheSameTime(MockHttpServletRequestBuilder request, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void staleRowsPreserveTheEditedVersionAlongsideCurrentValuesAndNeverOverwrite() throws Exception {
        TnaPrincipal target = member("E001", Set.of("EMPLOYEE"));
        database.update("UPDATE tna.employee SET display_name='이미 반영된 이름',version=1 WHERE tenant_id=? AND id=?",
                tenant, target.employeeId());
        var row = employeeRow(target.employeeId(), "E001", "보존할 편집", "UPDATE", 0L);
        var result = mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(row)))
                .andExpect(status().isConflict()).andExpect(ReactPageAssertions.dataPresent("/employeeSheet/latestRows"))
                .andReturn();
        var draft = sheetRow(result, "employeeSheet", row);
        assertEquals(0, draft.get("expectedVersion").asLong());
        assertEquals("보존할 편집", draft.get("displayName").asString());
        var latest = ReactPageAssertions.envelope(result).at("/data/employeeSheet/latestRows");
        assertTrue(latest.toString().contains("이미 반영된 이름"));
        assertEquals(0, count("tna.org_audit_event"));
    }

    @Test
    void conflictsKeepCurrentOriginalsEvenWhenTheDirtyEmployeeOrOrganizationLeavesTheSearchPage() throws Exception {
        TnaPrincipal target = member("E001", Set.of("EMPLOYEE"));
        database.update("UPDATE tna.employee SET display_name='NEW 이름',version=1 WHERE tenant_id=? AND id=?", tenant, target.employeeId());
        var employee = employeeRow(target.employeeId(), "E001", "OLD 편집", "UPDATE", 0L);
        var employeeResponse = mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(employee)).param("q", "OLD"))
                .andExpect(status().isConflict()).andReturn();
        assertEquals(1, ReactPageAssertions.envelope(employeeResponse).at("/data/employeeSheet/latestRows").size());
        assertEquals(target.employeeId().toString(), ReactPageAssertions.envelope(employeeResponse)
                .at("/data/employeeSheet/latestRows/0/id").asString());
        assertEquals("NEW 이름", ReactPageAssertions.envelope(employeeResponse)
                .at("/data/employeeSheet/latestRows/0/displayName").asString());
        assertEquals(0, sheetRow(employeeResponse, "employeeSheet", employee).get("expectedVersion").asLong());
        UUID id = organization("A", null);
        database.update("UPDATE tna.organization SET name='NEW 조직',version=1 WHERE tenant_id=? AND id=?", tenant, id);
        var organization = organizationRow(id, "A", "OLD 편집 조직", null, "UPDATE", 0L);
        var organizationResponse = mvc.perform(bulk("organizations", manager, UUID.randomUUID(), List.of(organization)).param("q", "OLD"))
                .andExpect(status().isConflict()).andReturn();
        assertEquals(1, ReactPageAssertions.envelope(organizationResponse).at("/data/organizationSheet/latestRows").size());
        assertEquals("NEW 조직", ReactPageAssertions.envelope(organizationResponse)
                .at("/data/organizationSheet/latestRows/0/name").asString());
        assertEquals(0, sheetRow(organizationResponse, "organizationSheet", organization).get("expectedVersion").asLong());
        assertEquals(0, count("tna.org_audit_event"));
    }

    @Test
    void manualOrganizationCodeAndDuplicateEmployeeNumberErrorsIdentifyTheActualSecondDirtyRow() {
        organization("DUP", null);
        member("DUP", Set.of("EMPLOYEE"));
        var firstOrganization = organizationRow(null, "", "정상 첫 조직", null, "CREATE", null);
        var manualOrganization = organizationRow(null, "DUP", "코드를 보낸 둘째 조직", null, "CREATE", null);
        var firstEmployee = employeeRow(null, "UNIQUE", "정상 첫 사원", "CREATE", null);
        var duplicateEmployee = employeeRow(null, "DUP", "중복 둘째 사원", "CREATE", null);
        assertAll(
                () -> mvc.perform(bulk("organizations", manager, UUID.randomUUID(), List.of(firstOrganization, manualOrganization)))
                        .andExpect(status().is(422))
                        .andExpect(ReactPageAssertions.data("/organizationSheet/errors/0/clientRowId", manualOrganization.get("clientRowId")))
                        .andExpect(ReactPageAssertions.data("/organizationSheet/errors/0/field", "code")),
                () -> mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(firstEmployee, duplicateEmployee)))
                        .andExpect(status().isConflict())
                        .andExpect(ReactPageAssertions.data("/employeeSheet/errors/0/clientRowId", duplicateEmployee.get("clientRowId")))
                        .andExpect(ReactPageAssertions.data("/employeeSheet/errors/0/field", "employeeNo")));
        assertEquals(1, count("tna.organization"));
        assertEquals(2, count("tna.employee"));
        assertEquals(0, count("tna.org_audit_event"));
    }

    @Test
    void strictFieldShapeDuplicateRowsAndImmutableIdentifiersAreRejected() throws Exception {
        var row = employeeRow(null, "NEW", "신규 사원", "CREATE", null);
        row.put("role", "ATTENDANCE_ADMIN");
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(row))).andExpect(status().isBadRequest());
        row.remove("role");
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(row)).param("tenantId", tenant.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(row, row))).andExpect(status().is(422));
        TnaPrincipal target = member("E001", Set.of("EMPLOYEE"));
        var first = employeeRow(target.employeeId(), "E001", "중복 대상", "UPDATE", 0L);
        var second = employeeRow(target.employeeId(), "E001", "다른 중복", "UPDATE", 0L);
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(first, second))).andExpect(status().is(422));
        first.put("employeeNo", "CHANGED");
        mvc.perform(bulk("employees", manager, UUID.randomUUID(), List.of(first))).andExpect(status().is(422));
        mvc.perform(authenticated(post("/admin/employees/bulk"), manager).with(csrf())
                        .param("commandId", UUID.randomUUID().toString()).param("reason", "형식 검증")
                        .param("rows", "[{\"operation\":\"CREATE\",\"operation\":\"DEACTIVATE\"}]"))
                .andExpect(status().isBadRequest());
        assertEquals(0, count("tna.org_audit_event"));
    }

    @Test
    void personnelExportReturnsTheCommittedSelectionOrAllMatchingPagesAndAuditsOnlyAuthorizedDownloads() throws Exception {
        var selected = member("00009", Set.of("EMPLOYEE"));
        database.update("UPDATE tna.employee SET display_name='=2+3' WHERE tenant_id=? AND id=?", tenant, selected.employeeId());
        database.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,active,created_by,updated_by)
                SELECT ?,gen_random_uuid(),'SHEET-'||n,'필터 '||n,?,true,?,? FROM generate_series(1,52) n
                """, tenant, today, manager.accountId(), manager.accountId());
        String path = "/admin/employees/exports";
        var selection = mvc.perform(authenticated(post(path), manager).with(csrf())
                        .param("scope", "SELECTED").param("employeeIds", selected.employeeId().toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=personnel.xlsx"))
                .andReturn();
        String selectedSheet = worksheet(selection);
        assertTrue(selectedSheet.contains("00009") && selectedSheet.contains("=2+3"));
        assertFalse(selectedSheet.contains("<f") || selectedSheet.contains("SHEET-"));

        String filteredSheet = worksheet(mvc.perform(authenticated(post(path), manager).with(csrf())
                        .param("scope", "FILTERED").param("q", "SHEET-").param("status", "active"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(54, filteredSheet.split("<row r=", -1).length - 1);
        assertFalse(filteredSheet.contains("00009"));
        mvc.perform(authenticated(post(path), selected).with(csrf()).param("scope", "FILTERED"))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(post(path), manager).param("scope", "FILTERED"))
                .andExpect(status().isForbidden());
        mvc.perform(authenticated(post(path), manager).with(csrf()).param("scope", "SELECTED")
                        .param("employeeIds", selected.employeeId().toString(), UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        assertEquals(List.of("1", "52"), database.queryForList("""
                SELECT after_state->>'rowCount' FROM tna.org_audit_event
                WHERE tenant_id=? AND action='PERSONNEL_EXPORT' ORDER BY (after_state->>'rowCount')::integer
                """, String.class, tenant));
    }

    private String worksheet(MvcResult result) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("워크시트가 없습니다.");
    }

    @Test
    void everyBulkRequiresCsrfAndAdministratorWhileForeignTargetsStayHidden() throws Exception {
        var row = organizationRow(null, "", "허용 안 된 조직", null, "CREATE", null);
        mvc.perform(authenticated(post("/admin/organizations/bulk"), manager)
                .param("commandId", UUID.randomUUID().toString()).param("reason", "CSRF 검증")
                .param("rows", JSON.writeValueAsString(List.of(row)))).andExpect(status().isForbidden());
        for (Set<String> roles : List.of(Set.of("EMPLOYEE"), Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE"))) {
            var unauthorized = member(UUID.randomUUID().toString(), roles);
            mvc.perform(bulk("organizations", unauthorized, UUID.randomUUID(), List.of(row))).andExpect(status().isForbidden());
            mvc.perform(bulk("employees", unauthorized, UUID.randomUUID(), List.of(employeeRow(null, "NEW", "차단", "CREATE", null))))
                    .andExpect(status().isForbidden());
        }
        var foreign = organizationRow(UUID.randomUUID(), "FOREIGN", "숨김 대상", null, "UPDATE", 0L);
        mvc.perform(bulk("organizations", manager, UUID.randomUUID(), List.of(row, foreign))).andExpect(status().isNotFound());
        assertEquals(0, count("tna.organization"));
        assertEquals(0, count("tna.org_audit_event"));
    }

    @Test
    void concurrentSelfDeactivationsCannotRemoveBothAdministrators() throws Exception {
        var second = member("ADMIN-2", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = executor.submit(() -> concurrentDeactivate(manager, "ADMIN", ready, start));
            var secondResult = executor.submit(() -> concurrentDeactivate(second, "ADMIN-2", ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var statuses = List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(value -> value == 303).count());
            assertEquals(1, statuses.stream().filter(value -> value == 422).count());
        }
        assertEquals(1, database.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=? AND active", Integer.class, tenant));
        assertEquals(2, count("tna.org_audit_event"));
    }

    private int concurrentDeactivate(TnaPrincipal actor, String number, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("동시 시작 신호가 없습니다.");
        return mvc.perform(bulk("employees", actor, UUID.randomUUID(),
                List.of(employeeRow(actor.employeeId(), number, "동시 비활성", "DEACTIVATE", 0L))))
                .andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder bulk(String kind, TnaPrincipal actor, UUID command, List<Map<String, Object>> rows) {
        return authenticated(post("/admin/" + kind + "/bulk"), actor).with(csrf())
                .accept("application/vnd.worklog.page+json")
                .param("commandId", command.toString()).param("reason", "합성 시트 원자 저장")
                .param("rows", JSON.writeValueAsString(rows));
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, TnaPrincipal actor) {
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList())));
    }

    private Map<String, Object> organizationRow(UUID id, String code, String name, UUID parent, String operation, Long version) {
        var row = new LinkedHashMap<String, Object>();
        row.put("clientRowId", UUID.randomUUID().toString()); row.put("id", id); row.put("expectedVersion", version);
        row.put("operation", operation); row.put("code", code); row.put("name", name); row.put("parentId", parent); row.put("active", true);
        return row;
    }

    private Map<String, Object> employeeRow(UUID id, String number, String name, String operation, Long version) {
        var row = new LinkedHashMap<String, Object>();
        row.put("clientRowId", UUID.randomUUID().toString()); row.put("id", id); row.put("expectedVersion", version);
        row.put("operation", operation); row.put("employeeNo", number); row.put("displayName", name);
        row.put("joinedOn", today.minusYears(1).toString()); row.put("leftOn", null); row.put("active", true);
        return row;
    }

    private JsonNode sheetRow(MvcResult result, String sheet, Map<String, Object> row) throws Exception {
        for (JsonNode candidate : ReactPageAssertions.envelope(result).at("/data/" + sheet + "/rows")) {
            if (row.get("clientRowId").equals(candidate.get("clientRowId").asString())) return candidate;
        }
        throw new AssertionError("입력 행이 보존되지 않았습니다.");
    }

    private JsonNode result(UUID command) {
        return JSON.readTree(database.queryForObject("SELECT after_state::text FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                String.class, tenant, command));
    }

    private UUID organization(String code, UUID parent) {
        UUID id = UUID.randomUUID();
        database.update("INSERT INTO tna.organization(tenant_id,id,code,name,parent_id,created_by,updated_by) VALUES (?,?,?,?,?,?,?)",
                tenant, id, code, "원본 " + code, parent, manager.accountId(), manager.accountId());
        return id;
    }

    private UUID organizationParent(UUID id) {
        return database.queryForObject("SELECT parent_id FROM tna.organization WHERE tenant_id=? AND id=?", UUID.class, tenant, id);
    }

    private long employeeVersion(UUID id) {
        return database.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?", Long.class, tenant, id);
    }

    private boolean employeeActive(UUID id) {
        return database.queryForObject("SELECT active FROM tna.employee WHERE tenant_id=? AND id=?", Boolean.class, tenant, id);
    }

    private long authVersion(TnaPrincipal principal) {
        return database.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=?", Long.class, tenant, principal.accountId());
    }

    private int count(String table) {
        return database.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant);
    }

    private TnaPrincipal member(String number, Set<String> roles) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        database.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 시트 사원',?)",
                tenant, id, number, today.minusYears(1));
        database.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, id, account + "@example.test", "synthetic-sheet-password-hash");
        for (String role : roles) database.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, id, 1, roles, Instant.now());
    }
}
