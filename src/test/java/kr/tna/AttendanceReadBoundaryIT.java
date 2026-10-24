package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
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
class AttendanceReadBoundaryIT {
    @Autowired ClockService clocks;
    @Autowired OrgCommands administration;
    private JdbcTemplate db;
    private TnaPrincipal actor;
    private LocalDate day;

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

    @BeforeEach
    void syntheticIdentity() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        day = LocalDate.now(ZoneId.of("Asia/Seoul"));
        UUID tenant = UUID.randomUUID(), employee = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 조회 경계')",
                tenant, "READ-" + tenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 조회 사원',?)",
                tenant, employee, employee.toString(), day.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, employee, account + "@example.test", "synthetic-read-boundary-hash");
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, account);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')", tenant, account);
        actor = new TnaPrincipal(tenant, account, employee, 1, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.now());
        assertEquals(employee, clocks.day(actor, day).employeeId());
    }

    @Test
    void accountRevocationCommittedWhileTheReadWaitsMustBlockTheRead() throws Exception {
        assertRevocationDuringRead("UPDATE tna.tenant_account SET active=false,auth_version=auth_version+1 WHERE tenant_id=? AND id=?", false);
    }

    @Test
    void roleRevocationCommittedWhileTheReadWaitsMustBlockTheRead() throws Exception {
        assertRevocationDuringRead("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='EMPLOYEE'", false);
    }

    @Test
    void administrativeAccountRevocationDuringExceptionalSnapshotReadMustBlockTheRead() throws Exception {
        assertRevocationDuringRead("UPDATE tna.tenant_account SET active=false,auth_version=auth_version+1 WHERE tenant_id=? AND id=?", true);
    }

    @Test
    void administrativeRoleRevocationDuringExceptionalSnapshotReadMustBlockTheRead() throws Exception {
        assertRevocationDuringRead("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'", true);
    }

    private void assertRevocationDuringRead(String revocation, boolean administrativeRead) throws Exception {
        try (var writer = DatabaseFixture.start().adminDataSource().getConnection();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            writer.setAutoCommit(false);
            try {
                int writerPid;
                try (var pid = writer.createStatement(); var rows = pid.executeQuery("SELECT pg_backend_pid()")) {
                    rows.next(); writerPid = rows.getInt(1);
                }
                try (var lock = writer.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))")) {
                    lock.setObject(1, actor.tenantId()); lock.execute();
                }
                try (var revoke = writer.prepareStatement(revocation)) {
                    revoke.setObject(1, actor.tenantId()); revoke.setObject(2, actor.accountId());
                    assertEquals(1, revoke.executeUpdate());
                }
                var reader = executor.submit(() -> {
                    try {
                        if (administrativeRead) {
                            administration.read(actor, () -> clocks.snapshot(actor.tenantId(), actor.employeeId(), day));
                        } else {
                            clocks.day(actor, day);
                        }
                        return 200;
                    }
                    catch (OrgException error) { return error.status(); }
                });
                boolean waiting = false;
                for (int attempt = 0; attempt < 250; attempt++) {
                    waiting = db.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                            WHERE wait_event_type='Lock' AND ?=ANY(pg_blocking_pids(pid)))
                            """, Boolean.class, writerPid);
                    if (waiting) break;
                    Thread.sleep(20);
                }
                assertTrue(waiting, "해당 권한 변경 트랜잭션의 잠금에서 본인 조회가 대기해야 합니다.");
                writer.commit();
                assertEquals(403, reader.get(10, TimeUnit.SECONDS),
                        "잠금 대기 중 권한 회수가 커밋되면 과거 인증 주체로 자료를 반환하면 안 됩니다.");
            } finally {
                writer.rollback();
            }
        }
    }
}
