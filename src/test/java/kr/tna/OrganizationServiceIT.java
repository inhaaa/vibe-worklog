package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgData.OrganizationForm;
import kr.tna.org.OrgException;
import kr.tna.org.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class OrganizationServiceIT {
    @Autowired OrganizationService organizations;
    @Autowired OrgCommands commands;
    private JdbcTemplate admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var database = DatabaseFixture.start();
        properties.add("spring.datasource.url", database::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", database::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void administrator() {
        DatabaseFixture.start().seedIdentity("synthetic-organization-service-hash");
        admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        for (UUID tenant : List.of(DatabaseFixture.TENANT_A, DatabaseFixture.TENANT_B)) {
            admin.update("""
                    INSERT INTO tna.role_grant (tenant_id, account_id, role)
                    VALUES (?, ?, 'ATTENDANCE_ADMIN') ON CONFLICT DO NOTHING
                    """, tenant, tenant.equals(DatabaseFixture.TENANT_A)
                    ? DatabaseFixture.ACCOUNT_A : DatabaseFixture.ACCOUNT_B);
        }
    }

    @Test
    void blankOrganizationCodeUsesThePersistedSeoulCreationYear() {
        UUID id = organizations.create(actor(), form("", "자동 코드 조직", null, true, null));
        String code = organizations.get(actor(), id).code();
        Integer year = admin.queryForObject("""
                SELECT EXTRACT(YEAR FROM created_at AT TIME ZONE 'Asia/Seoul')::int
                FROM tna.organization WHERE tenant_id=? AND id=?
                """, Integer.class, DatabaseFixture.TENANT_A, id);
        assertTrue(code.matches(year + "[0-9]{4}"));
    }

    @Test
    void createNormalizesInputAndReplaysOneAuditedEffect() {
        UUID command = UUID.randomUUID();
        var first = new OrganizationForm("  ", "  연구 조직  ", null,
                true, null, "  신규 조직 등록  ", command);
        UUID id = organizations.create(actor(), first);
        UUID replayed = organizations.create(actor(), new OrganizationForm(null, "연구 조직", null,
                true, null, "신규 조직 등록", command));
        assertEquals(id, replayed);
        var saved = organizations.get(actor(), id);
        assertTrue(saved.code().matches("[0-9]{8}"));
        assertEquals("연구 조직", saved.name());
        assertTrue(saved.active());
        assertEquals(0, saved.version());
        assertEquals(1, auditCount(command));
        assertEquals("신규 조직 등록", admin.queryForObject("""
                SELECT reason FROM tna.org_audit_event WHERE tenant_id = ? AND command_id = ?
                """, String.class, DatabaseFixture.TENANT_A, command));
        assertStatus(409, () -> organizations.create(actor(), new OrganizationForm(null, "다른 이름", null,
                true, null, "신규 조직 등록", command)));
        assertEquals("연구 조직", organizations.get(actor(), id).name());
        assertEquals(1, auditCount(command));
    }

    @Test
    void updateChecksVersionKeepsCodeImmutableAndDoesNotRepeatEffects() {
        UUID id = create("변경 전", null, true);
        var original = organizations.get(actor(), id);
        UUID command = UUID.randomUUID();
        var form = new OrganizationForm(original.code(), "변경 후", null, true, 0L, "조직명 변경", command);
        assertEquals(id, organizations.update(actor(), id, form));
        assertEquals(id, organizations.update(actor(), id, form));
        assertEquals("변경 후", organizations.get(actor(), id).name());
        assertEquals(1, organizations.get(actor(), id).version());
        assertEquals(1, auditCount(command));
        assertStatus(409, () -> organizations.update(actor(), id, new OrganizationForm(original.code(),
                "오래된 화면", null, true, 0L, "오래된 버전", UUID.randomUUID())));
        assertStatus(422, () -> organizations.update(actor(), id, new OrganizationForm(code(),
                "변경 후", null, true, 1L, "코드 변경 시도", UUID.randomUUID())));
        assertEquals(original.code(), organizations.get(actor(), id).code());
        assertEquals("변경 후", organizations.get(actor(), id).name());
        assertEquals(1, organizations.get(actor(), id).version());
    }

    @Test
    void successBeforeAutomaticCodesStillReplaysWithItsOriginalInput() {
        String code = code();
        String name = "전환 전에 저장한 조직";
        String reason = "수동 코드 등록";
        UUID id = seedOrganization(actor(), code, name, true), command = UUID.randomUUID();
        var beforeRollout = organizations.get(actor(), id);
        commands.execute(actor(), command, "ORGANIZATION_CREATE", "ORGANIZATION",
                OrgCommands.fingerprint("ORGANIZATION_CREATE", null, code, name, null, true, null, reason),
                reason, () -> new OrgCommands.Change(id, null, beforeRollout));
        assertEquals(id, organizations.create(actor(), new OrganizationForm(" " + code + " ", name, null,
                true, null, reason, command)));
        assertEquals(code, organizations.get(actor(), id).code());
        assertEquals(1, auditCount(command));
        assertStatus(422, () -> organizations.create(actor(), form(code, "새 수동 코드 등록", null, true, null)));
    }

    @Test
    void storedCodesAreTenantScopedAndCanBeUpdatedWithoutSupplyingCode() {
        String code = code();
        UUID inactive = seedOrganization(actor(), code, "비활성 조직", false);
        UUID otherTenant = seedOrganization(otherActor(), code, "다른 고객사", true);
        assertNotEquals(inactive, otherTenant);
        assertStatus(404, () -> organizations.get(actor(), otherTenant));
        assertStatus(404, () -> organizations.update(actor(), otherTenant,
                form(code, "타 고객사 변경 시도", null, true, 0L)));
        assertEquals(inactive, organizations.update(actor(), inactive, form(null, "보관 조직", null, false, 0L)));
        assertEquals(code, organizations.get(actor(), inactive).code());
        assertFalse(organizations.get(actor(), inactive).active());
    }

    @Test
    void parentsMustBeActiveAndBelongToTheSameTenant() {
        UUID foreign = organizations.create(otherActor(), form(null, "다른 고객사 부모", null, true, null));
        UUID inactive = create("비활성 부모", null, false);
        assertStatus(404, () -> organizations.create(actor(), form(null, "자식", foreign, true, null)));
        assertStatus(422, () -> organizations.create(actor(), form(null, "자식", inactive, true, null)));
        assertStatus(422, () -> organizations.create(actor(), form(null, "비활성 자식", inactive, false, null)));
        UUID child = create("부모 없는 조직", null, true);
        var saved = organizations.get(actor(), child);
        assertStatus(404, () -> organizations.update(actor(), child,
                form(saved.code(), saved.name(), foreign, true, saved.version())));
        assertStatus(422, () -> organizations.update(actor(), child,
                form(saved.code(), saved.name(), inactive, true, saved.version())));
    }

    @Test
    void selfAndIndirectCyclesAreRejectedWithoutChangingTheHierarchy() {
        UUID parent = create("부모", null, true);
        UUID child = create("자식", parent, true);
        UUID grandchild = create("손자", child, true);
        var saved = organizations.get(actor(), parent);
        assertStatus(422, () -> organizations.update(actor(), parent,
                form(saved.code(), saved.name(), parent, true, saved.version())));
        assertStatus(422, () -> organizations.update(actor(), parent,
                form(saved.code(), saved.name(), grandchild, true, saved.version())));
        assertEquals(null, organizations.get(actor(), parent).parentId());
        assertEquals(parent, organizations.get(actor(), child).parentId());
        assertEquals(child, organizations.get(actor(), grandchild).parentId());
    }

    @Test
    void deactivationWaitsUntilActiveChildrenAreDeactivated() {
        UUID parent = create("부모", null, true);
        UUID child = create("활성 자식", parent, true);
        assertStatus(422, () -> changeActive(parent, false));
        changeActive(child, false);
        changeActive(parent, false);
        assertFalse(organizations.get(actor(), parent).active());
        assertEquals(parent, organizations.get(actor(), child).parentId());
    }

    @Test
    void currentAndFutureAssignmentsBlockDeactivationEvenForInactiveEmployees() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (LocalDate from : List.of(today.minusDays(1), today.plusDays(10))) {
            UUID organization = create("배정 중 조직", null, true);
            seedAssignment(organization, from, null, false);
            assertStatus(422, () -> changeActive(organization, false));
            assertTrue(organizations.get(actor(), organization).active());
        }
        UUID futureClosed = create("미래 종료 조직", null, true);
        seedAssignment(futureClosed, today.minusDays(1), today.plusDays(1), true);
        assertStatus(422, () -> changeActive(futureClosed, false));
    }

    @Test
    void assignmentsEndingTodayAreHistoricalAndDoNotBlockDeactivation() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        UUID organization = create("과거 배정 조직", null, true);
        seedAssignment(organization, today.minusDays(10), today, true);
        changeActive(organization, false);
        assertFalse(organizations.get(actor(), organization).active());
        assertEquals(1, admin.queryForObject("""
                SELECT count(*) FROM tna.employee_assignment WHERE tenant_id = ? AND organization_id = ?
                """, Integer.class, DatabaseFixture.TENANT_A, organization));
    }

    @Test
    void inactiveChildCanKeepItsInactiveParentWhenRenamedButCannotBeReactivated() {
        UUID parent = create("보관 부모", null, true);
        UUID child = create("보관 자식", parent, true);
        changeActive(child, false);
        changeActive(parent, false);
        var saved = organizations.get(actor(), child);
        organizations.update(actor(), child, form(saved.code(), "보관 이름 수정", parent, false, saved.version()));
        var after = organizations.get(actor(), child);
        assertEquals(parent, after.parentId());
        assertEquals("보관 이름 수정", after.name());
        assertStatus(422, () -> organizations.update(actor(), child,
                form(after.code(), after.name(), parent, true, after.version())));
    }

    @Test
    void searchIsTenantScopedAndUsesLiteralTextWithFiftyRowPages() {
        String prefix = "search_" + UUID.randomUUID().toString().substring(0, 8) + "%";
        for (int index = 0; index < 52; index++) {
            seedOrganization(actor(), prefix + index, "검색 " + index, true);
        }
        seedOrganization(actor(), code(), prefix.replace("_", "X").replace("%", "Y"), true);
        seedOrganization(otherActor(), prefix + "foreign", "타 고객사", true);
        var first = organizations.list(actor(), "  " + prefix + "  ", 0);
        var second = organizations.list(actor(), prefix, 1);
        assertEquals(51, first.size());
        assertEquals(2, second.size());
        assertTrue(first.stream().allMatch(row -> row.code().startsWith(prefix)));
        assertTrue(second.stream().allMatch(row -> row.code().startsWith(prefix)));
        assertEquals(first.get(50).id(), second.getFirst().id());
        assertTrue(organizations.options(actor()).stream().anyMatch(row -> row.id().equals(first.getFirst().id())));
        assertFalse(organizations.options(otherActor()).stream().anyMatch(row -> row.id().equals(first.getFirst().id())));
        assertStatus(422, () -> organizations.list(actor(), "", -1));
    }

    @Test
    void validationUsesUnicodeCodePointsAndRequiresReasonAndVersion() {
        String code = "😀".repeat(8) + UUID.randomUUID().toString().replace("-", "");
        UUID id = seedOrganization(actor(), code, "과거 조직", true);
        organizations.update(actor(), id, form(code, "😀".repeat(120), null, true, 0L));
        assertEquals(code, organizations.get(actor(), id).code());
        assertEquals(120, organizations.get(actor(), id).name().codePointCount(0, 240));
        assertStatus(422, () -> organizations.create(actor(), form(null, "😀".repeat(121), null, true, null)));
        assertStatus(422, () -> organizations.create(actor(), form(null, " ", null, true, null)));
        assertStatus(422, () -> organizations.create(actor(), new OrganizationForm(null, "조직", null,
                true, null, " ", UUID.randomUUID())));
        assertStatus(422, () -> organizations.update(actor(), id, form(code, "조직", null, true, null)));
    }

    @Test
    void concurrentMutualParentChangesAllowOnlyOneCommit() throws Exception {
        UUID first = create("경합 A", null, true);
        UUID second = create("경합 B", null, true);
        var one = organizations.get(actor(), first);
        var two = organizations.get(actor(), second);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> concurrentChange(first,
                    form(one.code(), one.name(), second, true, one.version()), ready, start));
            var b = workers.submit(() -> concurrentChange(second,
                    form(two.code(), two.name(), first, true, two.version()), ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> outcomes = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(status -> status == 200).count());
            assertEquals(1, outcomes.stream().filter(status -> status == 422).count());
        }
        var afterOne = organizations.get(actor(), first);
        var afterTwo = organizations.get(actor(), second);
        assertFalse(second.equals(afterOne.parentId()) && first.equals(afterTwo.parentId()));
    }

    private int concurrentChange(UUID id, OrganizationForm form, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 실행 시작 신호 누락");
        }
        try {
            organizations.update(actor(), id, form);
            return 200;
        } catch (OrgException exception) {
            return exception.status();
        }
    }

    private UUID create(String name, UUID parent, boolean active) {
        return organizations.create(actor(), form(null, name, parent, active, null));
    }

    private UUID seedOrganization(TnaPrincipal actor, String code, String name, boolean active) {
        UUID id = UUID.randomUUID();
        admin.update("""
                INSERT INTO tna.organization(tenant_id,id,code,name,active,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?)
                """, actor.tenantId(), id, code, name, active, actor.accountId(), actor.accountId());
        return id;
    }

    private void changeActive(UUID id, boolean active) {
        var saved = organizations.get(actor(), id);
        organizations.update(actor(), id, form(saved.code(), saved.name(), saved.parentId(), active, saved.version()));
    }

    private void seedAssignment(UUID organization, LocalDate from, LocalDate to, boolean employeeActive) {
        UUID employee = UUID.randomUUID();
        admin.update("""
                INSERT INTO tna.employee (tenant_id, id, employee_no, display_name, joined_on, active)
                VALUES (?, ?, ?, '배정 검증 사원', ?, ?)
                """, DatabaseFixture.TENANT_A, employee, code(), from.minusDays(1), employeeActive);
        admin.update("""
                INSERT INTO tna.employee_assignment
                    (tenant_id, id, employee_id, organization_id, valid_from, valid_to, reason, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, '합성 소속 이력', ?, ?)
                """, DatabaseFixture.TENANT_A, UUID.randomUUID(), employee, organization, from, to,
                DatabaseFixture.ACCOUNT_A, DatabaseFixture.ACCOUNT_A);
    }

    private int auditCount(UUID command) {
        return admin.queryForObject("""
                SELECT count(*) FROM tna.org_audit_event WHERE tenant_id = ? AND command_id = ?
                """, Integer.class, DatabaseFixture.TENANT_A, command);
    }

    private static OrganizationForm form(String code, String name, UUID parent, boolean active, Long version) {
        return new OrganizationForm(code, name, parent, active, version, "합성 조직 관리 검증", UUID.randomUUID());
    }

    private static String code() {
        return "ORG-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static TnaPrincipal actor() {
        return new TnaPrincipal(DatabaseFixture.TENANT_A, DatabaseFixture.ACCOUNT_A, DatabaseFixture.EMPLOYEE_A,
                1, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.now());
    }

    private static TnaPrincipal otherActor() {
        return new TnaPrincipal(DatabaseFixture.TENANT_B, DatabaseFixture.ACCOUNT_B, DatabaseFixture.EMPLOYEE_B,
                1, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.now());
    }

    private static void assertStatus(int status, Runnable action) {
        assertEquals(status, assertThrows(OrgException.class, action::run).status());
    }
}
