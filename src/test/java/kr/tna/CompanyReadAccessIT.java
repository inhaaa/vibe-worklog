package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.CompanyReadAccess;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.AccountData.RolesForm;
import kr.tna.org.AccountService;
import kr.tna.org.EmployeeService;
import kr.tna.org.OrgData.EmployeeForm;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class CompanyReadAccessIT {
    @Autowired CompanyReadAccess access;
    @Autowired TenantTransactions transactions;
    @Autowired AccountService accounts;
    @Autowired EmployeeService employees;
    @Autowired @Qualifier("dataSource") DataSource applicationSource;
    private JdbcTemplate database, application;
    private TnaPrincipal manager, employee;
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
        application = new JdbcTemplate(applicationSource);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        UUID tenant = UUID.randomUUID();
        database.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 회사 읽기')",
                tenant, "COMPANY-READ-" + tenant);
        manager = member(tenant, "ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member(tenant, "COMMON", Set.of("EMPLOYEE"));
    }

    @Test
    void administratorsAndExplicitRepresentativesReadOnlyTheirTenantWithoutContextLeakage() {
        assertEquals(List.of(manager.tenantId()), read(manager, () -> application.queryForList(
                "SELECT DISTINCT tenant_id FROM tna.employee", UUID.class)));
        assertEquals(0, application.queryForObject("SELECT count(*) FROM tna.employee", Integer.class));
        assertDoesNotThrow(() -> accounts.roles(manager, employee.employeeId(), new RolesForm(
                Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE"), 0L, "합성 대표 지정", UUID.randomUUID())));
        var representative = new TnaPrincipal(employee.tenantId(), employee.accountId(), employee.employeeId(), 2,
                Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE"), Instant.now());
        assertEquals("회사 조회", read(representative, () -> "회사 조회"));
        assertStatus(403, () -> accounts.get(representative, manager.employeeId()));
        assertEquals(0, application.queryForObject("SELECT count(*) FROM tna.employee", Integer.class));
        assertEquals(1, database.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=?",
                Integer.class, manager.tenantId()));
    }

    @Test
    void ordinaryEmployeeAndForgedTenantOrPrivilegesNeverEnterTheReadSupplier() {
        AtomicInteger invoked = new AtomicInteger();
        Supplier<String> data = () -> { invoked.incrementAndGet(); return "숨겨진 회사 자료"; };
        assertStatus(403, () -> read(null, data));
        assertStatus(403, () -> read(employee, data));
        assertStatus(403, () -> read(new TnaPrincipal(employee.tenantId(), employee.accountId(), employee.employeeId(), 1,
                Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.now()), data));
        assertStatus(403, () -> read(new TnaPrincipal(UUID.randomUUID(), manager.accountId(), manager.employeeId(), 1,
                manager.roles(), Instant.now()), data));
        assertStatus(403, () -> read(new TnaPrincipal(manager.tenantId(), manager.accountId(), manager.employeeId(), 1,
                Set.of("ATTENDANCE_ADMIN"), Instant.now()), data));
        assertEquals(0, invoked.get());
    }

    @Test
    void currentDatabaseRoleEmploymentAccountAndSessionAreRequiredBeforeReading() {
        for (String mutation : List.of(
                "UPDATE tna.tenant_account SET auth_version=auth_version+1 WHERE tenant_id=? AND id=?",
                "UPDATE tna.tenant_account SET active=false WHERE tenant_id=? AND id=?",
                "UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?",
                "UPDATE tna.employee SET joined_on=CURRENT_DATE+2 WHERE tenant_id=? AND id=?",
                "UPDATE tna.employee SET left_on=CURRENT_DATE-2 WHERE tenant_id=? AND id=?",
                "UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='EMPLOYEE'",
                "UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'")) {
            var candidate = member(manager.tenantId(), UUID.randomUUID().toString(), Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
            UUID target = mutation.startsWith("UPDATE tna.employee") ? candidate.employeeId() : candidate.accountId();
            database.update(mutation, candidate.tenantId(), target);
            AtomicInteger invoked = new AtomicInteger();
            assertStatus(403, () -> read(candidate, () -> invoked.incrementAndGet()));
            assertEquals(0, invoked.get());
        }
    }

    @Test
    void returnedCompanyDataIsDiscardedWhenRoleOrSessionChangesDuringTheSupplier() {
        for (String mutation : List.of(
                "UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",
                "UPDATE tna.tenant_account SET auth_version=auth_version+1 WHERE tenant_id=? AND id=?",
                "UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?")) {
            var candidate = member(manager.tenantId(), UUID.randomUUID().toString(), Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
            AtomicInteger invoked = new AtomicInteger();
            assertStatus(403, () -> read(candidate, () -> {
                invoked.incrementAndGet();
                database.update(mutation, candidate.tenantId(), mutation.startsWith("UPDATE tna.employee")
                        ? candidate.employeeId() : candidate.accountId());
                return "반환하면 안 되는 회사 자료";
            }));
            assertEquals(1, invoked.get());
        }
    }

    @Test
    void companyReadsShareTheTenantLockAndEmployeeWritesWaitForBothReaders() throws Exception {
        var firstReady = new CountDownLatch(1);
        var secondReady = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var firstPid = new AtomicInteger();
        var secondPid = new AtomicInteger();
        var writerPid = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> holdRead(firstPid, firstReady, releaseFirst));
                assertTrue(firstReady.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> holdRead(secondPid, secondReady, releaseSecond));
                assertTrue(secondReady.await(5, TimeUnit.SECONDS), "회사 조회 두 건은 공유 잠금으로 함께 진행되어야 합니다.");
                assertNotEquals(firstPid.get(), secondPid.get());
                var writer = executor.submit(() -> transactions.execute(manager.tenantId(), () -> {
                    writerPid.set(application.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return employees.update(manager, employee.employeeId(), new EmployeeForm("COMMON", "조회 후 변경",
                            today.minusYears(1), null, true, 0L, "공유 조회 검증", UUID.randomUUID()));
                }));
                await(() -> writerPid.get() > 0 && blocks(writerPid.get(), firstPid.get())
                        && blocks(writerPid.get(), secondPid.get()));
                assertFalse(writer.isDone());
                releaseFirst.countDown();
                assertEquals("회사 조회", first.get(10, TimeUnit.SECONDS));
                await(() -> blocks(writerPid.get(), secondPid.get()));
                assertFalse(writer.isDone());
                releaseSecond.countDown();
                assertEquals("회사 조회", second.get(10, TimeUnit.SECONDS));
                assertEquals(employee.employeeId(), writer.get(10, TimeUnit.SECONDS));
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
        }
    }

    private String holdRead(AtomicInteger pid, CountDownLatch ready, CountDownLatch release) {
        return read(manager, () -> {
            pid.set(application.queryForObject("SELECT pg_backend_pid()", Integer.class));
            ready.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("회사 읽기 해제 신호가 없습니다.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return "회사 조회";
        });
    }

    private <T> T read(TnaPrincipal actor, Supplier<T> action) {
        return access.read(actor, action);
    }

    private boolean blocks(int blocked, int blocker) {
        return database.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))", Boolean.class, blocker, blocked);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int attempt = 0; attempt < 250; attempt++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail("회사 읽기 공유 잠금에서 변경이 대기해야 합니다.");
    }

    private TnaPrincipal member(UUID tenant, String number, Set<String> roles) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        database.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 회사 조회 사원',?)",
                tenant, id, number, today.minusYears(1));
        database.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, id, account + "@example.test", "synthetic-company-read-hash");
        for (String role : roles) database.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",
                tenant, account, role);
        return new TnaPrincipal(tenant, account, id, 1, roles, Instant.now());
    }

    private static void assertStatus(int status, Runnable action) {
        assertEquals(status, assertThrows(OrgException.class, action::run).status());
    }
}
