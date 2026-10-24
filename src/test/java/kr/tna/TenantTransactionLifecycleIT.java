package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class TenantTransactionLifecycleIT {
    @Autowired TenantTransactions tenants;
    @Autowired @Qualifier("dataSource") DataSource source;
    private JdbcTemplate jdbc, admin;
    private UUID tenantA, tenantB, employeeA;

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
    void syntheticTenants() {
        assertEquals(5, ((HikariDataSource) source).getMaximumPoolSize());
        jdbc = new JdbcTemplate(source);
        admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenantA = UUID.randomUUID(); tenantB = UUID.randomUUID(); employeeA = UUID.randomUUID();
        seed(tenantA, employeeA);
        seed(tenantB, UUID.randomUUID());
    }

    @Test
    void requiresNewHasNoImplicitTenantAndResumesTheOriginalScope() {
        tenants.execute(tenantA, () -> {
            int originalBackend = backend();
            assertVisible(tenantA);
            raw(TransactionDefinition.PROPAGATION_REQUIRES_NEW).executeWithoutResult(status -> {
                assertNotEquals(originalBackend, backend());
                assertUnscoped();
                tenants.execute(tenantB, () -> {
                    assertVisible(tenantB);
                    return null;
                });
            });
            assertEquals(originalBackend, backend());
            assertVisible(tenantA);
            return null;
        });
    }

    @Test
    void asynchronousTransactionMustEstablishItsOwnTenant() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            tenants.execute(tenantA, () -> {
                int originalBackend = backend();
                var worker = executor.submit(() -> raw(TransactionDefinition.PROPAGATION_REQUIRED).execute(status -> {
                    int workerBackend = backend();
                    assertUnscoped();
                    tenants.execute(tenantB, () -> {
                        assertVisible(tenantB);
                        return null;
                    });
                    return workerBackend;
                }));
                assertNotEquals(originalBackend, assertDoesNotThrow(() -> worker.get(10, TimeUnit.SECONDS)));
                assertEquals(originalBackend, backend());
                assertVisible(tenantA);
                return null;
            });
        }
    }

    @Test
    void savepointRollbackAfterCrossTenantAttemptPreservesTheOuterTenant() {
        tenants.execute(tenantA, () -> {
            int originalBackend = backend();
            String originalTimeout = jdbc.queryForObject("SHOW statement_timeout", String.class);
            assertThrows(IllegalStateException.class, () ->
                    raw(TransactionDefinition.PROPAGATION_NESTED).executeWithoutResult(status -> {
                        jdbc.execute("SET LOCAL statement_timeout='2s'");
                        assertVisible(tenantA);
                        tenants.execute(tenantB, () -> fail("세이브포인트에서도 고객사를 바꿀 수 없습니다."));
                    }));
            assertEquals(originalBackend, backend());
            assertEquals(originalTimeout, jdbc.queryForObject("SHOW statement_timeout", String.class));
            assertVisible(tenantA);
            return null;
        });
    }

    @Test
    void statementCancellationRollsBackDataAndClearsContextBeforeTheSameBackendServesAnotherTenant() throws SQLException {
        // Hold the other four connections so reuse of the cancelled transaction's physical connection is proven.
        List<Connection> held = new ArrayList<>();
        int[] cancelledBackend = new int[1];
        String[] originalTimeout = new String[1];
        try {
            for (int i = 0; i < 4; i++) held.add(source.getConnection());
            var error = assertThrows(DataAccessException.class, () -> tenants.execute(tenantA, () -> {
                cancelledBackend[0] = backend();
                originalTimeout[0] = jdbc.queryForObject("SHOW statement_timeout", String.class);
                assertVisible(tenantA);
                assertEquals(1, jdbc.update("UPDATE tna.employee SET display_name='롤백되어야 하는 합성 이름' WHERE tenant_id=? AND id=?", tenantA, employeeA));
                jdbc.execute("SET LOCAL statement_timeout='100ms'");
                jdbc.queryForObject("SELECT pg_sleep(5)", Object.class);
                return null;
            }));
            assertEquals("57014", sqlState(error));
            assertEquals("합성 생명주기 사원", admin.queryForObject("SELECT display_name FROM tna.employee WHERE tenant_id=? AND id=?", String.class, tenantA, employeeA));
            raw(TransactionDefinition.PROPAGATION_REQUIRED).executeWithoutResult(status -> {
                assertEquals(cancelledBackend[0], backend());
                assertUnscoped();
                assertEquals(originalTimeout[0], jdbc.queryForObject("SHOW statement_timeout", String.class));
            });
            tenants.execute(tenantB, () -> {
                assertEquals(cancelledBackend[0], backend());
                assertVisible(tenantB);
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=?", Integer.class, tenantA));
                return null;
            });
        } finally {
            for (Connection connection : held) connection.close();
        }
    }

    private TransactionTemplate raw(int propagation) {
        var template = new TransactionTemplate(new DataSourceTransactionManager(source));
        template.setPropagationBehavior(propagation);
        return template;
    }

    private void assertUnscoped() {
        assertEquals(DatabaseFixture.APP_USER, jdbc.queryForObject("SELECT current_user", String.class));
        String current = jdbc.queryForObject("SELECT current_setting('app.tenant_id',true)", String.class);
        assertTrue(current == null || current.isEmpty());
        assertEquals(List.of(), jdbc.queryForList("SELECT DISTINCT tenant_id FROM tna.employee", UUID.class));
    }

    private void assertVisible(UUID tenant) {
        assertEquals(tenant.toString(), jdbc.queryForObject("SELECT current_setting('app.tenant_id',true)", String.class));
        assertEquals(List.of(tenant), jdbc.queryForList("SELECT DISTINCT tenant_id FROM tna.employee", UUID.class));
    }

    private int backend() { return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class); }

    private void seed(UUID tenant, UUID employee) {
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 생명주기 고객사')", tenant, "LIFECYCLE-" + tenant);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'E001','합성 생명주기 사원',DATE '2020-01-01')", tenant, employee);
    }

    private String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return "";
    }
}
