package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.EmployeeService;
import kr.tna.org.OrgData.EmployeeForm;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class TenantReadConcurrencyIT {
    @Autowired TenantTransactions transactions;
    @Autowired ClockService clocks;
    @Autowired WorktimeService reports;
    @Autowired EmployeeService employees;
    @Autowired @Qualifier("dataSource") DataSource applicationSource;
    private JdbcTemplate admin, application;
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
    void syntheticTenant() {
        admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        application = new JdbcTemplate(applicationSource);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        UUID tenant = UUID.randomUUID();
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 공유 조회')",
                tenant, "SHARED-" + tenant);
        manager = member(tenant, "ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member(tenant, "E001", Set.of("EMPLOYEE"));
    }

    @Test
    void monthlyReadsShareTheTenantLockButEmployeeMutationWaitsForBoth() throws Exception {
        assertConcurrentReadsAndExclusiveWrite(() -> {
            var result = reports.month(employee, employee.employeeId(), YearMonth.from(today));
            assertEquals(today.lengthOfMonth(), result.days().size());
            return result;
        });
    }

    @Test
    void clockDayReadsShareTheTenantLockButEmployeeMutationWaitsForBoth() throws Exception {
        assertConcurrentReadsAndExclusiveWrite(() -> {
            var result = clocks.day(employee, today);
            assertEquals(employee.employeeId(), result.employeeId());
            return result;
        });
    }

    private void assertConcurrentReadsAndExclusiveWrite(Supplier<?> read) throws Exception {
        var firstReady = new CountDownLatch(1);
        var secondReady = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var firstPid = new AtomicInteger();
        var secondPid = new AtomicInteger();
        var writerPid = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> holdRead(read, firstPid, firstReady, releaseFirst));
                assertTrue(firstReady.await(10, TimeUnit.SECONDS), "첫 서비스 조회가 완료되어야 합니다.");
                var second = executor.submit(() -> holdRead(read, secondPid, secondReady, releaseSecond));
                assertTrue(secondReady.await(5, TimeUnit.SECONDS),
                        "첫 조회 트랜잭션이 열려 있어도 같은 고객사의 두 번째 조회가 완료되어야 합니다.");
                assertNotEquals(firstPid.get(), secondPid.get());

                UUID command = UUID.randomUUID();
                var writer = executor.submit(() -> transactions.execute(employee.tenantId(), () -> {
                    writerPid.set(application.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    return employees.update(manager, employee.employeeId(), new EmployeeForm("E001", "공유 조회 후 변경",
                            today.minusYears(1), null, true, 0L, "합성 독점 쓰기 검증", command));
                }));
                await(() -> writerPid.get() > 0 && blocks(writerPid.get(), firstPid.get())
                        && blocks(writerPid.get(), secondPid.get()),
                        "실제 사원 변경 명령이 두 서비스 조회의 공유 잠금에서 대기해야 합니다.");
                assertFalse(writer.isDone());

                releaseFirst.countDown();
                first.get(10, TimeUnit.SECONDS);
                await(() -> blocks(writerPid.get(), secondPid.get()),
                        "첫 조회가 끝나도 남은 조회의 잠금이 사원 변경을 보호해야 합니다.");
                assertFalse(writer.isDone());

                releaseSecond.countDown();
                second.get(10, TimeUnit.SECONDS);
                assertEquals(employee.employeeId(), writer.get(10, TimeUnit.SECONDS));
                var changed = employees.get(manager, employee.employeeId());
                assertEquals("공유 조회 후 변경", changed.displayName());
                assertEquals(1, changed.version());
                assertEquals(1, admin.queryForObject(
                        "SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                        Integer.class, employee.tenantId(), command));
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
        }
    }

    private Object holdRead(Supplier<?> read, AtomicInteger pid, CountDownLatch ready, CountDownLatch release) {
        return transactions.execute(employee.tenantId(), () -> {
            pid.set(application.queryForObject("SELECT pg_backend_pid()", Integer.class));
            Object value = read.get();
            ready.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) throw new AssertionError("조회 잠금 해제 신호가 없습니다.");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            return value;
        });
    }

    private boolean blocks(int blockedPid, int blockerPid) {
        return admin.queryForObject("SELECT ?=ANY(pg_blocking_pids(?))", Boolean.class, blockerPid, blockedPid);
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        for (int attempt = 0; attempt < 250; attempt++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        fail(message);
    }

    private TnaPrincipal member(UUID tenant, String number, Set<String> roles) {
        UUID employeeId = UUID.randomUUID(), account = UUID.randomUUID();
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 공유 조회 사원',?)",
                tenant, employeeId, number, today.minusYears(1));
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, employeeId, account + "@example.test", "synthetic-shared-read-hash");
        for (String role : roles) {
            admin.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        }
        return new TnaPrincipal(tenant, account, employeeId, 1, roles, Instant.now());
    }
}
