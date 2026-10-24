package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverAssignment;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ApproverServiceIT {
    @Autowired ApproverService approvers;
    @Autowired OrgCommands commands;
    private JdbcTemplate database;
    private UUID tenant;
    private TnaPrincipal actor;
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
    void tenantAdministrator() {
        database = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = commands.today();
        tenant = tenant("APPROVER-");
        actor = account(tenant, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
    }

    @Test
    void activeEmployeeNeedsNoAcceptedAccountToReceiveAnApprover() {
        UUID employee = employee(tenant);
        TnaPrincipal approver = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        assertEquals(employee, approvers.set(actor, form(employee, approver.employeeId(), 0L)));
        ApproverAssignment current = approvers.history(actor, employee).getFirst();
        assertEquals(approver.employeeId(), current.approverEmployeeId());
        assertEquals(today, current.validFrom());
        assertNull(current.validTo());
        assertTrue(current.eligible());
        assertEquals(1, version(employee));
        assertEquals(0, database.queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?",
                Integer.class, tenant, employee));
        UUID pendingEmployee = employee(tenant);
        pendingAccount(pendingEmployee);
        approvers.set(actor, form(pendingEmployee, approver.employeeId(), 0L));
        assertTrue(approvers.history(actor, pendingEmployee).getFirst().eligible());
    }

    @Test
    void crossTenantEmployeeAndApproverAreNotFound() {
        UUID employee = employee(tenant);
        UUID otherTenant = tenant("FOREIGN-");
        TnaPrincipal foreign = account(otherTenant, Set.of("EMPLOYEE", "APPROVER"));
        assertStatus(404, () -> approvers.set(actor, form(foreign.employeeId(), actor.employeeId(), 0L)));
        assertStatus(404, () -> approvers.set(actor, form(employee, foreign.employeeId(), 0L)));
        assertStatus(404, () -> approvers.history(actor, foreign.employeeId()));
        assertFalse(approvers.candidates(actor).stream().anyMatch(row -> row.id().equals(foreign.employeeId())));
        assertEquals(0, version(employee));
    }

    @Test
    void selfAssignmentIsRejectedEvenWithApproverRole() {
        TnaPrincipal employee = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        assertStatus(422, () -> approvers.set(actor, form(employee.employeeId(), employee.employeeId(), 0L)));
        assertTrue(approvers.history(actor, employee.employeeId()).isEmpty());
    }

    @Test
    void candidatesRequireAcceptedActiveCurrentEmployeeAndActiveApproverRole() {
        TnaPrincipal eligible = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        TnaPrincipal noRole = account(tenant, Set.of("EMPLOYEE"));
        TnaPrincipal inactiveAccount = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        TnaPrincipal revokedRole = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        TnaPrincipal inactiveEmployee = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        TnaPrincipal futureEmployee = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        TnaPrincipal retiredEmployee = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        UUID pendingEmployee = employee(tenant);
        UUID pending = pendingAccount(pendingEmployee);
        grant(tenant, pending, "APPROVER");
        database.update("UPDATE tna.tenant_account SET active=false WHERE tenant_id=? AND id=?", tenant, inactiveAccount.accountId());
        database.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='APPROVER'",
                tenant, revokedRole.accountId());
        database.update("UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?", tenant, inactiveEmployee.employeeId());
        database.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", today.plusDays(1), tenant, futureEmployee.employeeId());
        database.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", today.minusDays(1), tenant, retiredEmployee.employeeId());
        UUID target = employee(tenant);
        for (UUID invalid : List.of(noRole.employeeId(), inactiveAccount.employeeId(), revokedRole.employeeId(),
                inactiveEmployee.employeeId(), futureEmployee.employeeId(), retiredEmployee.employeeId(), pendingEmployee)) {
            assertStatus(422, () -> approvers.set(actor, form(target, invalid, 0L)));
        }
        assertEquals(List.of(eligible.employeeId()), approvers.candidates(actor).stream().map(row -> row.id()).toList());
        database.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", today, tenant, eligible.employeeId());
        approvers.set(actor, form(target, eligible.employeeId(), 0L));
        assertTrue(approvers.history(actor, target).getFirst().eligible());
    }

    @Test
    void targetMustBeActiveAndCurrentlyEmployed() {
        TnaPrincipal approver = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        for (String state : List.of("inactive", "future", "retired")) {
            UUID employee = employee(tenant);
            if (state.equals("inactive")) database.update("UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?", tenant, employee);
            if (state.equals("future")) database.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", today.plusDays(1), tenant, employee);
            if (state.equals("retired")) database.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", today.minusDays(1), tenant, employee);
            assertStatus(422, () -> approvers.set(actor, form(employee, approver.employeeId(), 0L)));
        }
    }

    @Test
    void onlyTodayAndRequiredCommandFieldsAreAccepted() {
        UUID employee = employee(tenant);
        TnaPrincipal approver = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        for (LocalDate invalid : List.of(today.minusDays(1), today.plusDays(1))) {
            assertStatus(422, () -> approvers.set(actor, new ApproverForm(employee, approver.employeeId(), invalid,
                    0L, "지원 범위 검증", UUID.randomUUID())));
        }
        assertStatus(422, () -> approvers.set(actor, new ApproverForm(employee, approver.employeeId(), null, 0L, "검증", UUID.randomUUID())));
        assertStatus(422, () -> approvers.set(actor, new ApproverForm(null, approver.employeeId(), today, 0L, "검증", UUID.randomUUID())));
        assertStatus(422, () -> approvers.set(actor, new ApproverForm(employee, approver.employeeId(), today, 0L, " ", UUID.randomUUID())));
        assertStatus(422, () -> approvers.set(actor, new ApproverForm(employee, approver.employeeId(), today, null, "검증", UUID.randomUUID())));
        assertStatus(422, () -> approvers.set(actor, new ApproverForm(employee, approver.employeeId(), today, 0L, "검증", null)));
        assertEquals(0, version(employee));
    }

    @Test
    void previousDayIntervalIsClosedAndSameDayReplacementAndClearPreserveRows() {
        UUID employee = employee(tenant);
        TnaPrincipal first = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        TnaPrincipal next = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        UUID prior = UUID.randomUUID();
        database.update("""
                INSERT INTO tna.approver_assignment
                (tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'이전 배정',?,?)
                """, tenant, prior, employee, first.employeeId(), today.minusDays(2), actor.accountId(), actor.accountId());
        approvers.set(actor, form(employee, next.employeeId(), 0L));
        var history = approvers.history(actor, employee);
        ApproverAssignment old = history.stream().filter(row -> row.id().equals(prior)).findFirst().orElseThrow();
        assertEquals(today, old.validTo());
        assertNull(old.supersededAt());
        assertFalse(old.eligible());
        UUID nextId = current(history).id();
        approvers.set(actor, form(employee, first.employeeId(), 1L));
        history = approvers.history(actor, employee);
        assertEquals(3, history.size());
        ApproverAssignment superseded = history.stream().filter(row -> row.id().equals(nextId)).findFirst().orElseThrow();
        assertNotNull(superseded.supersededAt());
        assertNull(superseded.validTo());
        assertFalse(superseded.eligible());
        approvers.set(actor, form(employee, null, 2L));
        history = approvers.history(actor, employee);
        assertEquals(3, history.size());
        assertTrue(history.stream().noneMatch(ApproverAssignment::eligible));
        assertEquals(3, version(employee));
        assertEquals(3, database.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND target_id=?",
                Integer.class, tenant, employee));
    }

    @Test
    void staleVersionAndCommandReuseConflictWhileExactRetryHasOneEffect() {
        UUID employee = employee(tenant);
        TnaPrincipal approver = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        var request = form(employee, approver.employeeId(), 0L);
        assertEquals(employee, approvers.set(actor, request));
        assertEquals(employee, approvers.set(actor, request));
        assertStatus(409, () -> approvers.set(actor, form(employee, approver.employeeId(), 0L)));
        assertStatus(409, () -> approvers.set(actor, new ApproverForm(employee, null, today, 1L, request.reason(), request.commandId())));
        assertEquals(1, approvers.history(actor, employee).size());
        assertEquals(1, version(employee));
        assertEquals(1, database.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                Integer.class, tenant, request.commandId()));
    }

    @Test
    void roleRevocationMarksHistoryIneligibleAndStillAllowsSameDayReplacementAndClear() {
        UUID employee = employee(tenant);
        TnaPrincipal first = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        TnaPrincipal next = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        approvers.set(actor, form(employee, first.employeeId(), 0L));
        database.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='APPROVER'",
                tenant, first.accountId());
        assertFalse(approvers.history(actor, employee).getFirst().eligible());
        assertEquals(1, approvers.history(actor, employee).size());
        approvers.set(actor, form(employee, next.employeeId(), 1L));
        assertEquals(next.employeeId(), current(approvers.history(actor, employee)).approverEmployeeId());
        database.update("UPDATE tna.tenant_account SET active=false WHERE tenant_id=? AND id=?", tenant, next.accountId());
        assertTrue(approvers.history(actor, employee).stream().noneMatch(ApproverAssignment::eligible));
        approvers.set(actor, form(employee, null, 2L));
        assertEquals(2, approvers.history(actor, employee).size());
        assertTrue(approvers.history(actor, employee).stream().allMatch(row -> row.supersededAt() != null));
    }

    @Test
    void currentAdminAuthorizationIsRecheckedForReadsWritesAndReplay() {
        UUID employee = employee(tenant);
        TnaPrincipal approver = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        var request = form(employee, approver.employeeId(), 0L);
        approvers.set(actor, request);
        assertStatus(403, () -> approvers.candidates(approver));
        assertStatus(403, () -> approvers.history(approver, employee));
        assertStatus(403, () -> approvers.set(approver, form(employee, null, 1L)));
        database.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",
                tenant, actor.accountId());
        assertStatus(403, () -> approvers.candidates(actor));
        assertStatus(403, () -> approvers.history(actor, employee));
        assertStatus(403, () -> approvers.set(actor, request));
    }

    @Test
    void concurrentDifferentCommandsUseTheEmployeeVersionToKeepOneAssignment() throws Exception {
        UUID employee = employee(tenant);
        TnaPrincipal first = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        TnaPrincipal next = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> setAfter(start, form(employee, first.employeeId(), 0L)));
            var two = workers.submit(() -> setAfter(start, form(employee, next.employeeId(), 0L)));
            start.countDown();
            var statuses = List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 0).count());
            assertEquals(1, statuses.stream().filter(status -> status == 409).count());
        }
        assertEquals(1, version(employee));
        assertEquals(1, approvers.history(actor, employee).size());
        assertTrue(approvers.history(actor, employee).getFirst().eligible());
    }

    @Test
    void historyEligibilityRechecksEmploymentAndDoesNotReactivateClosedIntervals() {
        UUID employee = employee(tenant);
        TnaPrincipal approver = account(tenant, Set.of("EMPLOYEE", "APPROVER"));
        approvers.set(actor, form(employee, approver.employeeId(), 0L));
        database.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", today.minusDays(1), tenant, approver.employeeId());
        assertFalse(approvers.history(actor, employee).getFirst().eligible());
        database.update("UPDATE tna.employee SET left_on=NULL WHERE tenant_id=? AND id=?", tenant, approver.employeeId());
        assertTrue(approvers.history(actor, employee).getFirst().eligible());
        approvers.set(actor, form(employee, null, 1L));
        assertFalse(approvers.history(actor, employee).getFirst().eligible());
    }

    private int setAfter(CountDownLatch start, ApproverForm form) throws Exception {
        start.await();
        try { approvers.set(actor, form); return 0; }
        catch (OrgException exception) { return exception.status(); }
    }

    private ApproverAssignment current(List<ApproverAssignment> history) {
        return history.stream().filter(ApproverAssignment::eligible).findFirst().orElseThrow();
    }

    private ApproverForm form(UUID employee, UUID approver, Long version) {
        return new ApproverForm(employee, approver, today, version, "합성 승인자 변경", UUID.randomUUID());
    }

    private UUID tenant(String prefix) {
        UUID id = UUID.randomUUID();
        database.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 승인자 고객사')",
                id, prefix + id);
        return id;
    }

    private UUID employee(UUID accountTenant) {
        UUID id = UUID.randomUUID();
        database.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 승인자 사원',?)",
                accountTenant, id, "E-" + id, today.minusYears(1));
        return id;
    }

    private TnaPrincipal account(UUID accountTenant, Set<String> roles) {
        UUID employee = employee(accountTenant);
        UUID id = UUID.randomUUID();
        database.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash)
                VALUES (?,?,?,?,'synthetic-unused-password-hash')
                """, accountTenant, id, employee, id + "@example.test");
        for (String role : roles) grant(accountTenant, id, role);
        return new TnaPrincipal(accountTenant, id, employee, 1, roles, Instant.now());
    }

    private UUID pendingAccount(UUID employee) {
        UUID id = UUID.randomUUID();
        database.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash,active)
                VALUES (?,?,?,?,NULL,false)
                """, tenant, id, employee, id + "@example.test");
        grant(tenant, id, "EMPLOYEE");
        return id;
    }

    private void grant(UUID accountTenant, UUID account, String role) {
        database.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", accountTenant, account, role);
    }

    private long version(UUID employee) {
        return database.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?", Long.class, tenant, employee);
    }

    private void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(OrgException.class, action::run).status());
    }
}
