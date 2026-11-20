package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.approval.ApprovalRouteData.RouteRow;
import kr.tna.approval.ApprovalRouteService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.BulkData;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ApprovalRouteAdminWebIT {
    private static final String PAGE = "application/vnd.worklog.page+json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired TenantTransactions transactions;
    @Autowired @Qualifier("dataSource") DataSource applicationSource;
    @Autowired ApprovalRouteService service;
    private JdbcTemplate db;
    private UUID tenant, organization;
    private TnaPrincipal manager, employee, approver;
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

    @BeforeEach void company() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 결재선 회사')", tenant, "ROUTE-" + tenant);
        manager = member("ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member("MEMBER", Set.of("EMPLOYEE"));
        approver = member("APPROVER", Set.of("EMPLOYEE", "APPROVER"));
        organization = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 조직',?,?)",
                tenant, organization, "ORG", manager.accountId(), manager.accountId());
    }

    @Test void managementProvidesFiftyRowSheetAndCurrentEligibleCandidates() throws Exception {
        mvc.perform(as(get("/admin/approval-routes"), manager).accept(PAGE))
                .andExpect(status().isOk()).andExpect(view().name("org/approval-routes"))
                .andExpect(ReactPageAssertions.dataPresent("/approvalRoutes/sheet/commandId"))
                .andExpect(ReactPageAssertions.data("/approvalRoutes/sheet/rows", List.of()));
        mvc.perform(as(get("/admin/approval-route-assignments"), manager).accept(PAGE).param("employeeId", employee.employeeId().toString()))
                .andExpect(status().isOk()).andExpect(view().name("org/approval-route-assignments"))
                .andExpect(ReactPageAssertions.data("/approvalRouteAssignments/form/expectedVersion", 0))
                .andExpect(ReactPageAssertions.data("/approvalRouteAssignments/form/validFrom", today.toString()));
    }

    @Test void sequentialVersionRetainsTerminalUnitAndIsOnlyAssignableToForms() throws Exception {
        var definition = row(null, "CHAIN", "순차결재", "ORGANIZATION_CHAIN", null, "CREATE", null);
        mvc.perform(bulk(UUID.randomUUID(), List.of(definition))).andExpect(status().isUnprocessableEntity());
        definition.put("terminalUnit", "OFFICE");
        mvc.perform(bulk(UUID.randomUUID(), List.of(definition))).andExpect(status().isSeeOther());
        UUID route = db.queryForObject("SELECT id FROM tna.approval_route WHERE tenant_id=? AND code='CHAIN'", UUID.class, tenant);
        var v1 = version(route, 1);
        var updated = row(route, "CHAIN", "순차결재", "ORGANIZATION_CHAIN", null, "UPDATE", 0L);
        updated.put("terminalUnit", "TEAM");
        mvc.perform(bulk(UUID.randomUUID(), List.of(updated))).andExpect(status().isSeeOther());
        mvc.perform(as(get("/admin/approval-routes"), manager).accept(PAGE))
                .andExpect(ReactPageAssertions.data("/approvalRoutes/sheet/rows/0/terminalUnit", "TEAM"))
                .andExpect(ReactPageAssertions.data("/approvalRoutes/versions/1/terminalUnit", "OFFICE"));
        mvc.perform(as(get("/admin/approval-route-assignments"), manager).accept(PAGE))
                .andExpect(ReactPageAssertions.data("/approvalRouteAssignments/versions", List.of()));
        mvc.perform(assign(employee.employeeId(), null, v1, today, 0, UUID.randomUUID(), false))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test void fiftyDefinitionsCommitOneBatchAndReplayExactlyOnce() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 50; i++) rows.add(row(null, "R" + i, "정의 " + i, "ORGANIZATION_HEAD", null, "CREATE", null));
        UUID command = UUID.randomUUID();
        mvc.perform(bulk(command, rows)).andExpect(status().isSeeOther());
        assertEquals(50, count("approval_route"));
        assertEquals(50, count("approval_route_version"));
        assertEquals(51, count("org_audit_event"));
        String original = db.queryForObject("SELECT after_state::text FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?", String.class, tenant, command);
        mvc.perform(bulk(command, rows)).andExpect(status().isSeeOther());
        assertEquals(original, db.queryForObject("SELECT after_state::text FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?", String.class, tenant, command));
        assertEquals(50, count("approval_route"));
        assertEquals(51, count("org_audit_event"));
        rows.getFirst().put("name", "다른 내용");
        mvc.perform(bulk(command, rows)).andExpect(status().isConflict());
        mvc.perform(as(get("/admin/approval-routes"), manager).accept(PAGE))
                .andExpect(ReactPageAssertions.data("/approvalRoutes/hasNext", false));
    }

    @Test void fiftyOneRowsAndLastInvalidRowRollbackAllDefinitionsAndAudit() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 51; i++) rows.add(row(null, "R" + i, "정의 " + i, "ORGANIZATION_HEAD", null, "CREATE", null));
        mvc.perform(bulk(UUID.randomUUID(), rows)).andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("approval_route"));
        rows.removeLast();
        rows.getLast().put("approverKind", "DIRECT_EMPLOYEE");
        mvc.perform(bulk(UUID.randomUUID(), rows)).andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("approval_route"));
        assertEquals(0, count("approval_route_version"));
        assertEquals(0, count("org_audit_event"));
    }

    @Test void rootAuditFailureRollsBackDefinitionsVersionsAndEarlierRowAudits() throws Exception {
        var rows = List.of(row(null, "ONE", "첫 정의", "ORGANIZATION_HEAD", null, "CREATE", null),
                row(null, "TWO", "둘째 정의", "ORGANIZATION_HEAD", null, "CREATE", null));
        UUID command = UUID.randomUUID();
        String constraint = "route_audit_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("ALTER TABLE tna.org_audit_event ADD CONSTRAINT " + constraint
                + " CHECK (tenant_id <> '" + tenant + "'::uuid OR action <> 'APPROVAL_ROUTE_BULK') NOT VALID");
        try {
            mvc.perform(bulk(command, rows)).andExpect(status().isConflict())
                    .andExpect(ReactPageAssertions.data("/approvalRoutes/sheet/commandId", command));
            assertEquals(0, count("approval_route"));
            assertEquals(0, count("approval_route_version"));
            assertEquals(0, count("org_audit_event"));
        } finally { db.execute("ALTER TABLE tna.org_audit_event DROP CONSTRAINT " + constraint); }
        mvc.perform(bulk(command, rows)).andExpect(status().isSeeOther());
        assertEquals(2, count("approval_route"));
        assertEquals(2, count("approval_route_version"));
        assertEquals(3, count("org_audit_event"));
    }

    @Test void definitionEditsAppendImmutableVersionAndDoNotChangeExistingAssignments() throws Exception {
        UUID route = create("DIRECT_EMPLOYEE", approver.employeeId());
        UUID v1 = version(route, 1);
        mvc.perform(assign(employee.employeeId(), null, v1, today, 0, UUID.randomUUID(), false)).andExpect(status().isSeeOther());
        mvc.perform(bulk(UUID.randomUUID(), List.of(row(route, "ROUTE", "새 조직장 결재", "ORGANIZATION_HEAD", null, "UPDATE", 0L))))
                .andExpect(status().isSeeOther());
        assertEquals(2, count("approval_route_version"));
        assertEquals(approver.employeeId(), db.queryForObject("SELECT employee_id FROM tna.approval_route_version WHERE tenant_id=? AND id=?", UUID.class, tenant, v1));
        assertEquals(v1, db.queryForObject("SELECT route_version_id FROM tna.approval_route_assignment WHERE tenant_id=?", UUID.class, tenant));
        assertEquals("ORGANIZATION_HEAD", db.queryForObject("SELECT approver_kind FROM tna.approval_route_version WHERE tenant_id=? AND route_id=? AND revision=2", String.class, tenant, route));
    }

    @Test void deactivatePreservesVersionsAndHistoryButPreventsNewAssignment() throws Exception {
        UUID route = create("ORGANIZATION_HEAD", null), v1 = version(route, 1);
        mvc.perform(assign(employee.employeeId(), null, v1, today, 0, UUID.randomUUID(), false)).andExpect(status().isSeeOther());
        mvc.perform(bulk(UUID.randomUUID(), List.of(row(route, "ROUTE", "정의", "ORGANIZATION_HEAD", null, "DEACTIVATE", 0L))))
                .andExpect(status().isSeeOther());
        assertEquals(false, db.queryForObject("SELECT active FROM tna.approval_route WHERE tenant_id=? AND id=?", Boolean.class, tenant, route));
        assertEquals(1, count("approval_route_version"));
        assertEquals(1, count("approval_route_assignment"));
        mvc.perform(assign(null, organization, v1, today, 0, UUID.randomUUID(), false)).andExpect(status().isUnprocessableEntity());
        mvc.perform(assign(employee.employeeId(), null, null, today, 1, UUID.randomUUID(), true)).andExpect(status().isSeeOther());
    }

    @Test void storedCodeIsImmutableAndConflictPreservesDraftAndFreshOriginal() throws Exception {
        UUID route = create("ORGANIZATION_HEAD", null), command = UUID.randomUUID();
        var draft = row(route, "CHANGED", "수정 값", "ORGANIZATION_HEAD", null, "UPDATE", 0L);
        var invalid = mvc.perform(bulk(command, List.of(draft))).andExpect(status().isUnprocessableEntity()).andReturn();
        assertEquals("CHANGED", ReactPageAssertions.envelope(invalid).at("/data/approvalRoutes/sheet/rows/0/code").asString());
        assertEquals(command.toString(), ReactPageAssertions.envelope(invalid).at("/data/approvalRoutes/sheet/commandId").asString());
        draft.put("code", "ROUTE");
        mvc.perform(bulk(UUID.randomUUID(), List.of(draft))).andExpect(status().isSeeOther());
        draft.put("name", "충돌 이후 보존");
        var conflict = mvc.perform(bulk(command, List.of(draft)).param("q", "찾을 수 없는 검색어"))
                .andExpect(status().isConflict()).andReturn();
        var sheet = ReactPageAssertions.envelope(conflict).at("/data/approvalRoutes/sheet");
        assertEquals("충돌 이후 보존", sheet.at("/rows/0/name").asString());
        assertEquals(1, sheet.at("/latestRows/0/version").asLong());
        assertEquals(2, count("approval_route_version"));
    }

    @Test void assignmentReplacementAndEndPreserveFutureReservationsAndOriginalRows() throws Exception {
        UUID route = create("ORGANIZATION_HEAD", null), version = version(route, 1);
        mvc.perform(assign(null, organization, version, today, 0, UUID.randomUUID(), false)).andExpect(status().isSeeOther());
        mvc.perform(assign(null, organization, version, today.plusDays(10), 1, UUID.randomUUID(), false)).andExpect(status().isSeeOther());
        mvc.perform(assign(null, organization, version, today.plusDays(5), 2, UUID.randomUUID(), false)).andExpect(status().isSeeOther());
        mvc.perform(assign(null, organization, null, today.plusDays(7), 3, UUID.randomUUID(), true)).andExpect(status().isSeeOther());
        assertEquals(3, count("approval_route_assignment"));
        assertEquals(today.plusDays(5), endAt(today));
        assertEquals(today.plusDays(7), endAt(today.plusDays(5)));
        assertNull(endAt(today.plusDays(10)));
        mvc.perform(assign(null, organization, version, today.plusDays(10), 4, UUID.randomUUID(), false)).andExpect(status().isSeeOther());
        assertEquals(4, count("approval_route_assignment"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.approval_route_assignment WHERE tenant_id=? AND superseded_at IS NOT NULL", Integer.class, tenant));
        assertEquals(5L, db.queryForObject("SELECT version FROM tna.organization WHERE tenant_id=? AND id=?", Long.class, tenant, organization));
    }

    @Test void assignmentReplayConflictAndPastDateHaveNoPartialEffects() throws Exception {
        UUID route = create("ORGANIZATION_HEAD", null), version = version(route, 1), command = UUID.randomUUID();
        mvc.perform(assign(employee.employeeId(), null, version, today, 0, command, false)).andExpect(status().isSeeOther());
        mvc.perform(assign(employee.employeeId(), null, version, today, 0, command, false)).andExpect(status().isSeeOther());
        assertEquals(1, count("approval_route_assignment"));
        mvc.perform(assign(employee.employeeId(), null, version, today.plusDays(1), 0, command, false)).andExpect(status().isConflict());
        mvc.perform(assign(employee.employeeId(), null, version, today.minusDays(1), 1, UUID.randomUUID(), false)).andExpect(status().isUnprocessableEntity());
        mvc.perform(assign(employee.employeeId(), null, version, today, 0, UUID.randomUUID(), false)).andExpect(status().isConflict());
        assertEquals(1, count("approval_route_assignment"));
        assertEquals(1L, db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?", Long.class, tenant, employee.employeeId()));
    }

    @Test void directCandidateMustBeAcceptedActiveCurrentApproverAndSelfAssignmentIsRejected() throws Exception {
        mvc.perform(bulk(UUID.randomUUID(), List.of(row(null, "NOT-APPROVER", "부적격", "DIRECT_EMPLOYEE", employee.employeeId(), "CREATE", null))))
                .andExpect(status().isUnprocessableEntity());
        UUID route = create("DIRECT_EMPLOYEE", approver.employeeId());
        mvc.perform(assign(approver.employeeId(), null, version(route, 1), today, 0, UUID.randomUUID(), false)).andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("approval_route_assignment"));
        assertEquals(1L, db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=?", Long.class, tenant, approver.accountId()));
    }

    @Test void strictShapeScopeAndTenantTargetsAreRejectedWithoutAudit() throws Exception {
        var row = row(null, "ROUTE", "정의", "ORGANIZATION_HEAD", null, "CREATE", null);
        row.put("tenantId", tenant.toString());
        mvc.perform(bulk(UUID.randomUUID(), List.of(row))).andExpect(status().isBadRequest());
        row.remove("tenantId");
        mvc.perform(bulk(UUID.randomUUID(), List.of(row, row))).andExpect(status().isUnprocessableEntity());
        mvc.perform(bulk(UUID.randomUUID(), List.of(row)).param("tenantId", tenant.toString())).andExpect(status().isBadRequest());
        var foreign = row(UUID.randomUUID(), "FOREIGN", "숨김", "ORGANIZATION_HEAD", null, "UPDATE", 0L);
        mvc.perform(bulk(UUID.randomUUID(), List.of(row, foreign))).andExpect(status().isNotFound());
        assertEquals(0, count("approval_route"));
        assertEquals(0, count("org_audit_event"));
    }

    @Test void scopeMismatchForeignVersionAndPastAssignmentKeepTheExactForm() throws Exception {
        UUID route = create("ORGANIZATION_HEAD", null), version = version(route, 1), command = UUID.randomUUID();
        var invalid = mvc.perform(assign(employee.employeeId(), null, version, today.minusDays(1), 0, command, false))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        var form = ReactPageAssertions.envelope(invalid).at("/data/approvalRouteAssignments/form");
        assertEquals(command.toString(), form.get("commandId").asString());
        assertEquals(today.minusDays(1).toString(), form.get("validFrom").asString());
        assertEquals(version.toString(), form.get("routeVersionId").asString());
        mvc.perform(assign(employee.employeeId(), organization, version, today, 0, UUID.randomUUID(), false)).andExpect(status().isUnprocessableEntity());
        mvc.perform(assign(employee.employeeId(), null, UUID.randomUUID(), today, 0, UUID.randomUUID(), false)).andExpect(status().isNotFound());
        assertEquals(0, count("approval_route_assignment"));
    }

    @Test void concurrentAssignmentsUseTheTargetVersionToRejectOneStaleChange() throws Exception {
        UUID route = create("ORGANIZATION_HEAD", null), version = version(route, 1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new java.util.concurrent.CountDownLatch(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> change = () -> {
                ready.countDown();
                assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                return mvc.perform(assign(employee.employeeId(), null, version, today, 0, UUID.randomUUID(), false))
                        .andReturn().getResponse().getStatus();
            };
            var one = executor.submit(change);
            var two = executor.submit(change);
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var outcomes = List.of(one.get(20, java.util.concurrent.TimeUnit.SECONDS), two.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1L, outcomes.stream().filter(status -> status == 303).count());
            assertEquals(1L, outcomes.stream().filter(status -> status == 409).count());
        }
        assertEquals(1, count("approval_route_assignment"));
    }

    @Test void retirementDoesNotPreventEndingAnExistingAssignmentOrDisablingTheDefinition() throws Exception {
        UUID route = create("DIRECT_EMPLOYEE", approver.employeeId()), version = version(route, 1);
        mvc.perform(assign(employee.employeeId(), null, version, today, 0, UUID.randomUUID(), false)).andExpect(status().isSeeOther());
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", today.minusDays(1), tenant, approver.employeeId());
        mvc.perform(bulk(UUID.randomUUID(), List.of(row(route, "ROUTE", "정의", "DIRECT_EMPLOYEE", approver.employeeId(), "DEACTIVATE", 0L))))
                .andExpect(status().isSeeOther());
        mvc.perform(assign(employee.employeeId(), null, null, today, 1, UUID.randomUUID(), true)).andExpect(status().isSeeOther());
        assertEquals(1, count("approval_route_version"));
    }

    @Test void representativeAndEmployeeCannotConfigureAndRevokedAdminCannotReplay() throws Exception {
        var rows = List.of(row(null, "ROUTE", "정의", "ORGANIZATION_HEAD", null, "CREATE", null));
        UUID command = UUID.randomUUID();
        mvc.perform(bulk(command, rows)).andExpect(status().isSeeOther());
        for (var actor : List.of(employee, member("REP", Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE")))) {
            mvc.perform(as(get("/admin/approval-routes"), actor)).andExpect(status().isForbidden());
            mvc.perform(as(get("/admin/approval-route-assignments"), actor)).andExpect(status().isForbidden());
            mvc.perform(as(post("/admin/approval-routes/bulk"), actor).with(csrf()).param("commandId", UUID.randomUUID().toString()).param("reason", "차단").param("rows", JSON.writeValueAsString(rows))).andExpect(status().isForbidden());
        }
        mvc.perform(as(post("/admin/approval-routes/bulk"), manager).param("commandId", UUID.randomUUID().toString()).param("reason", "차단").param("rows", JSON.writeValueAsString(rows))).andExpect(status().isForbidden());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'", tenant, manager.accountId());
        mvc.perform(bulk(command, rows)).andExpect(status().isUnauthorized());
        var original = rows.getFirst();
        var replay = new BulkData.Form<>(command, "합성 결재선 변경", List.of(new RouteRow(
                (String) original.get("clientRowId"), null, null, BulkData.Operation.CREATE,
                "ROUTE", "정의", true, "ORGANIZATION_HEAD", null)));
        assertEquals(403, assertThrows(OrgException.class, () -> service.save(manager, replay)).status());
        assertEquals(1, count("approval_route"));
    }

    @Test void snapshotAndVersionAreImmutableAndNewTablesForceTenantIsolation() throws Exception {
        UUID route = create("ORGANIZATION_HEAD", null);
        mvc.perform(assign(employee.employeeId(), null, version(route, 1), today, 0, UUID.randomUUID(), false)).andExpect(status().isSeeOther());
        for (String table : List.of("approval_route", "approval_route_version", "approval_route_assignment")) {
            assertEquals(1, transactions.execute(tenant, () -> new JdbcTemplate(applicationSource).queryForObject("SELECT count(*) FROM tna." + table, Integer.class)));
        }
        for (String table : List.of("approval_route", "approval_route_version", "approval_route_assignment", "approval_route_snapshot")) {
            assertTrue(db.queryForObject("SELECT relforcerowsecurity FROM pg_class WHERE oid=CAST(? AS regclass)", Boolean.class, "tna." + table));
            assertEquals(0, transactions.execute(UUID.randomUUID(), () -> new JdbcTemplate(applicationSource).queryForObject("SELECT count(*) FROM tna." + table, Integer.class)));
            assertEquals(false, db.queryForObject("SELECT has_table_privilege('tna_app',?,'DELETE')", Boolean.class, "tna." + table));
        }
        assertEquals(false, db.queryForObject("SELECT has_table_privilege('tna_app','tna.approval_route_version','UPDATE')", Boolean.class));
        assertEquals(false, db.queryForObject("SELECT has_table_privilege('tna_app','tna.approval_route_snapshot','UPDATE')", Boolean.class));
        assertEquals(false, db.queryForObject("SELECT has_column_privilege('tna_app','tna.approval_request','approver_assignment_id','UPDATE')", Boolean.class));
        assertEquals(false, db.queryForObject("SELECT has_column_privilege('tna_app','tna.approval_request','approver_assignment_version','UPDATE')", Boolean.class));
        assertEquals(false, db.queryForObject("SELECT has_column_privilege('tna_app','tna.approval_request','route_snapshot_id','UPDATE')", Boolean.class));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> transactions.execute(tenant,
                () -> new JdbcTemplate(applicationSource).update("UPDATE tna.approval_route_version SET name='허용 안 된 수정' WHERE tenant_id=?", tenant)));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> transactions.execute(tenant,
                () -> new JdbcTemplate(applicationSource).update("UPDATE tna.approval_route_snapshot SET resolved_at=CURRENT_TIMESTAMP WHERE tenant_id=?", tenant)));
        assertEquals(1, transactions.execute(tenant, () -> new JdbcTemplate(applicationSource).queryForObject("SELECT count(*) FROM tna.approval_route_version", Integer.class)));
    }

    private UUID create(String kind, UUID target) throws Exception {
        mvc.perform(bulk(UUID.randomUUID(), List.of(row(null, "ROUTE", "정의", kind, target, "CREATE", null)))).andExpect(status().isSeeOther());
        return db.queryForObject("SELECT id FROM tna.approval_route WHERE tenant_id=? AND code='ROUTE'", UUID.class, tenant);
    }
    private UUID version(UUID route, int revision) { return db.queryForObject("SELECT id FROM tna.approval_route_version WHERE tenant_id=? AND route_id=? AND revision=?", UUID.class, tenant, route, revision); }
    private int count(String table) { return db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Integer.class, tenant); }
    private LocalDate endAt(LocalDate from) { return db.queryForObject("SELECT valid_to FROM tna.approval_route_assignment WHERE tenant_id=? AND valid_from=? AND superseded_at IS NULL", LocalDate.class, tenant, from); }
    private MockHttpServletRequestBuilder bulk(UUID command, List<Map<String, Object>> rows) {
        return as(post("/admin/approval-routes/bulk"), manager).with(csrf()).accept(PAGE).param("commandId", command.toString()).param("reason", "합성 결재선 변경").param("rows", JSON.writeValueAsString(rows));
    }
    private MockHttpServletRequestBuilder assign(UUID target, UUID org, UUID version, LocalDate from, long expected, UUID command, boolean end) {
        var request = as(post("/admin/approval-route-assignments" + (end ? "/end" : "")), manager).with(csrf()).accept(PAGE)
                .param("scopeKind", target == null ? "ORGANIZATION" : "EMPLOYEE").param("validFrom", from.toString()).param("expectedVersion", Long.toString(expected))
                .param("reason", "합성 배정 변경").param("commandId", command.toString());
        if (target != null) request.param("employeeId", target.toString());
        if (org != null) request.param("organizationId", org.toString());
        if (version != null) request.param("routeVersionId", version.toString());
        return request;
    }
    private Map<String, Object> row(UUID id, String code, String name, String kind, UUID target, String operation, Long expected) {
        var row = new LinkedHashMap<String, Object>();
        row.put("clientRowId", UUID.randomUUID().toString()); row.put("id", id); row.put("expectedVersion", expected); row.put("operation", operation);
        row.put("code", code); row.put("name", name); row.put("approverKind", kind); row.put("employeeId", target); row.put("active", true);
        return row;
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, TnaPrincipal actor) {
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList())));
    }
    private TnaPrincipal member(String number, Set<String> roles) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?, ?,?)", tenant, id, number, "합成 " + number, today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)", tenant, account, id, account + "@example.test", "synthetic-route-password-hash");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, id, 1, roles, Instant.now());
    }
}
