package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.org.ApproverService;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Each case owns a separate synthetic tenant; no shared fixture identities are modified. */
@SpringBootTest
class AuditTrailIT {
    private static final Instant TIED_AT = Instant.parse("2099-01-01T00:00:00Z");
    @Autowired WorktimeService reports;
    @Autowired ApproverService approvers;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal manager, employee;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void ownSyntheticTenant() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = tenant();
        manager = member(tenant, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member(tenant, Set.of("EMPLOYEE"));
    }

    @Test
    void lookupOrderHasATenantAndTimestampIndex() {
        assertEquals(1, db.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='tna' AND tablename='org_audit_event' AND indexdef LIKE '%(tenant_id, occurred_at DESC, id DESC)%'", Integer.class));
    }

    @Test
    void tiedTimestampPagesHaveStableBidirectionalCursorsAndNoOverlap() {
        var expected = new ArrayList<UUID>();
        for (int i = 150; i >= 1; i--) {
            UUID id = new UUID(0, i);
            event(tenant, manager.accountId(), id, TIED_AT, "{}", "{}");
            expected.add(id);
        }
        var first = reports.audits(manager, null, null);
        assertNotNull(first);
        assertEquals(expected.subList(0, 50), ids(first.rows()));
        assertNull(first.previousCursor());
        assertEquals(first.rows().getLast().id(), first.nextCursor());
        var second = reports.audits(manager, first.nextCursor(), null);
        assertEquals(expected.subList(50, 100), ids(second.rows()));
        assertEquals(second.rows().getFirst().id(), second.previousCursor());
        assertEquals(second.rows().getLast().id(), second.nextCursor());
        var third = reports.audits(manager, second.nextCursor(), null);
        assertEquals(expected.subList(100, 150), ids(third.rows()));
        var unique = new HashSet<>(ids(first.rows()));
        unique.addAll(ids(second.rows()));
        unique.addAll(ids(third.rows()));
        assertEquals(150, unique.size());
        var previous = reports.audits(manager, null, second.previousCursor());
        assertEquals(ids(first.rows()), ids(previous.rows()));
    }

    @Test
    void insertionAndSelfAuditDoNotMoveAnExistingOlderCursor() {
        for (int i = 150; i >= 1; i--)
            event(tenant, manager.accountId(), new UUID(0, i), TIED_AT, "{}", "{}");
        var first = reports.audits(manager, null, null);
        assertNotNull(first);
        UUID inserted = new UUID(0, 151);
        event(tenant, manager.accountId(), inserted, TIED_AT, "{}", "{}");
        var second = reports.audits(manager, first.nextCursor(), null);
        assertEquals(50, second.rows().size());
        assertEquals(new UUID(0, 100), second.rows().getFirst().id());
        assertEquals(new UUID(0, 51), second.rows().getLast().id());
        assertTrue(second.rows().stream().noneMatch(row -> row.id().equals(inserted)));
        assertTrue(second.rows().stream().noneMatch(row -> ids(first.rows()).contains(row.id())));
        assertEquals(inserted, reports.audits(manager, null, null).rows().getFirst().id());
        assertEquals(3, count("AUDIT_VIEW"));
    }

    @Test
    void emptyJournalReturnsNoRowsAndStillRecordsTheRead() {
        var page = reports.audits(manager, null, null);
        assertNotNull(page);
        assertTrue(page.rows().isEmpty());
        assertNull(page.nextCursor());
        assertNull(page.previousCursor());
        assertEquals(1, count("AUDIT_VIEW"));
    }

    @Test
    void cursorAndDetailCannotResolveAnotherTenantAndInvalidRequestsLeaveNoReadAudit() {
        UUID other = tenant();
        var foreign = member(other, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        UUID secret = UUID.randomUUID();
        event(other, foreign.accountId(), secret, TIED_AT, "{}", "{}");
        status(404, () -> reports.audits(manager, secret, null));
        status(404, () -> reports.audits(manager, null, secret));
        status(404, () -> reports.auditDetail(manager, secret));
        status(400, () -> reports.audits(manager, secret, secret));
        status(404, () -> reports.auditDetail(manager, UUID.randomUUID()));
        assertEquals(0, count("AUDIT_VIEW"));
        assertEquals(0, count("AUDIT_DETAIL_VIEW"));
    }

    @Test
    void currentAdministratorAuthorityIsRequiredForBothReadPaths() {
        UUID id = UUID.randomUUID();
        event(tenant, manager.accountId(), id, TIED_AT, "{}", "{}");
        status(403, () -> reports.audits(employee, null, null));
        status(403, () -> reports.auditDetail(employee, id));
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",
                tenant, manager.accountId());
        status(403, () -> reports.audits(manager, null, null));
        status(403, () -> reports.auditDetail(manager, id));
        assertEquals(0, count("AUDIT_VIEW"));
        assertEquals(0, count("AUDIT_DETAIL_VIEW"));
    }

    @Test
    void detailProjectsSafeTypedFactsAndIncludesActualCommandAndTraceIdentifiers() {
        UUID id = UUID.randomUUID(), reference = UUID.randomUUID();
        UUID command = UUID.randomUUID(), trace = UUID.randomUUID();
        String before = """
                {"version":1,"active":false,"employeeId":"%s","status":"PENDING",
                 "validFrom":"2026-01-01","quantityDays":2,"roles":["EMPLOYEE"],
                 "email":"secret@example.test","token":"secret-token-value",
                 "passwordHash":"secret-password-hash","validatedIp":"192.0.2.123",
                 "sourceReference":"private-source-reference","reason":"private-nested-reason",
                 "displayName":"private-freeform-name","code":"private-freeform-code",
                 "unknown":{"version":999,"token":"nested-secret-token"}}
                """.formatted(reference);
        String after = """
                {"version":2,"active":true,"status":"APPROVED","quantityDays":1,
                 "roles":["EMPLOYEE","ATTENDANCE_ADMIN"],"authVersion":3}
                """;
        event(tenant, manager.accountId(), id, command, trace, TIED_AT, before, after);
        var detail = reports.auditDetail(manager, id);
        assertNotNull(detail);
        assertEquals(id, detail.id());
        assertEquals(command, detail.commandId());
        assertEquals(trace, detail.requestTraceId());
        assertEquals(manager.accountId(), detail.actorAccountId());
        assertEquals("합성 사유 <script> 화면 이스케이프 검증", detail.reason());
        assertEquals(TIED_AT, detail.at());
        String projected = detail.beforeSummary().toString() + detail.afterSummary();
        for (String allowed : List.of("version", "1", "2", "false", "true", reference.toString(),
                "PENDING", "APPROVED", "2026-01-01", "EMPLOYEE", "ATTENDANCE_ADMIN", "authVersion"))
            assertTrue(projected.contains(allowed), allowed);
        for (String hidden : List.of("secret@example.test", "secret-token-value", "secret-password-hash", "192.0.2.123",
                "private-source-reference", "private-nested-reason", "private-freeform-name", "private-freeform-code",
                "nested-secret-token", "999"))
            assertFalse(projected.contains(hidden), hidden);
        assertEquals(1, count("AUDIT_DETAIL_VIEW"));
        assertEquals(id, db.queryForObject("SELECT target_id FROM tna.org_audit_event WHERE tenant_id=? AND action='AUDIT_DETAIL_VIEW'",
                UUID.class, tenant));
    }

    @Test
    void clearedNullableReferencesRemainVisibleWithoutExposingSensitiveNullKeys() {
        var approver = member(tenant, Set.of("EMPLOYEE", "APPROVER"));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        approvers.set(manager, new ApproverForm(employee.employeeId(), approver.employeeId(), today,
                0L, "합성 승인자 최초 지정", UUID.randomUUID()));
        UUID clearCommand = UUID.randomUUID();
        approvers.set(manager, new ApproverForm(employee.employeeId(), null, today,
                1L, "합성 승인자 명시 해제", clearCommand));
        UUID clearedId = db.queryForObject("SELECT id FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                UUID.class, tenant, clearCommand);
        var cleared = reports.auditDetail(manager, clearedId);
        assertEquals("없음", cleared.afterSummary().get("approverEmployeeId"));
        assertEquals("없음", cleared.afterSummary().get("assignmentId"));
        assertEquals(approver.employeeId().toString(), cleared.beforeSummary().get("approverEmployeeId"));
        assertNotNull(cleared.beforeSummary().get("assignmentId"));

        UUID nullableId = UUID.randomUUID();
        event(tenant, manager.accountId(), nullableId, TIED_AT, "{}", """
                {"parentId":null,"validTo":null,"leftOn":null,"email":null,"token":null,
                 "passwordHash":null,"validatedIp":null,"unknown":null}
                """);
        var nullable = reports.auditDetail(manager, nullableId).afterSummary();
        for (String key : List.of("parentId", "validTo", "leftOn")) assertEquals("없음", nullable.get(key));
        for (String hidden : List.of("email", "token", "passwordHash", "validatedIp", "unknown", "organizationId"))
            assertFalse(nullable.containsKey(hidden), "민감/미등록/누락 필드는 명시 해제와 구분합니다: " + hidden);
    }

    @Test
    void ledgerRestoreKindIsPreservedAndUnknownCancellationAliasIsOmitted() {
        UUID id = UUID.randomUUID();
        event(tenant, manager.accountId(), id, TIED_AT,
                "{\"entryKind\":\"RESTORE\"}", "{\"entryKind\":\"CANCEL_RESTORE\"}");
        var detail = reports.auditDetail(manager, id);
        assertEquals("RESTORE", detail.beforeSummary().get("entryKind"));
        assertFalse(detail.afterSummary().containsKey("entryKind"));
    }

    @Test
    void unexpectedTypesAndUnknownEnumValuesAreNotTreatedAsSafeSummaries() {
        UUID id = UUID.randomUUID();
        event(tenant, manager.accountId(), id, TIED_AT, "null", """
                {"version":"private-version-string","active":"private-boolean-string",
                 "employeeId":"private-non-uuid","validFrom":"2026-99-99",
                 "quantityDays":"private-number-string","status":"private-enum-string",
                 "roles":["EMPLOYEE","private-role-string"]}
                """);
        var detail = reports.auditDetail(manager, id);
        assertNotNull(detail);
        assertTrue(detail.beforeSummary().isEmpty());
        String projected = detail.afterSummary().toString();
        for (String hidden : List.of("private-", "2026-99-99")) assertFalse(projected.contains(hidden));
    }

    @Test
    void auditInsertFailureDoesNotReturnUnauditedResults() {
        UUID id = UUID.randomUUID();
        event(tenant, manager.accountId(), id, TIED_AT, "{}", "{}");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String trigger = "audit_read_failure_" + suffix;
        String function = "tna." + trigger;
        db.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.tenant_id='" + tenant + "'::uuid AND NEW.action IN ('AUDIT_VIEW','AUDIT_DETAIL_VIEW') THEN "
                + "RAISE EXCEPTION 'Synthetic audit read write failure'; END IF; RETURN NEW; END $$");
        try {
            db.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON tna.org_audit_event FOR EACH ROW EXECUTE FUNCTION " + function + "()");
            status(503, () -> reports.auditDetail(manager, id));
            status(503, () -> reports.audits(manager, null, null));
            assertEquals(0, count("AUDIT_DETAIL_VIEW"));
            assertEquals(0, count("AUDIT_VIEW"));
            assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND id=?", Integer.class, tenant, id));
        } finally {
            db.execute("DROP TRIGGER IF EXISTS " + trigger + " ON tna.org_audit_event");
            db.execute("DROP FUNCTION IF EXISTS " + function + "()");
        }
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 감사 고객사')",
                id, "AUDIT-" + id);
        return id;
    }

    private TnaPrincipal member(UUID company, Set<String> roles) {
        UUID employeeId = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 감사 사용자',?)",
                company, employeeId, employeeId.toString(), LocalDate.now(ZoneId.of("Asia/Seoul")).minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                company, account, employeeId, account + "@example.test", "synthetic-audit-hash");
        for (String role : roles)
            db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", company, account, role);
        return new TnaPrincipal(company, account, employeeId, 1, roles, Instant.now());
    }

    private void event(UUID company, UUID actor, UUID id, Instant at, String before, String after) {
        event(company, actor, id, UUID.randomUUID(), UUID.randomUUID(), at, before, after);
    }

    private void event(UUID company, UUID actor, UUID id, UUID command, UUID trace, Instant at, String before, String after) {
        UUID target = company.equals(tenant) ? employee.employeeId() : db.queryForObject(
                "SELECT employee_id FROM tna.tenant_account WHERE tenant_id=? AND id=?", UUID.class, company, actor);
        db.update("""
                INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,
                    input_hash,reason,before_state,after_state,request_trace_id,occurred_at)
                VALUES (?,?,?,?,'SYNTHETIC_AUDIT','EMPLOYEE',?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?)
                """, company, id, command, actor, target, "0".repeat(64),
                "합성 사유 <script> 화면 이스케이프 검증", before, after, trace, Timestamp.from(at));
    }

    private int count(String action) {
        return db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action=?", Integer.class, tenant, action);
    }

    private static List<UUID> ids(List<kr.tna.worktime.WorktimeData.AuditRow> rows) {
        return rows.stream().map(kr.tna.worktime.WorktimeData.AuditRow::id).toList();
    }

    private static void status(int expected, Runnable action) {
        assertEquals(expected, assertThrows(OrgException.class, action::run).status());
    }
}
