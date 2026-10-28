package kr.tna;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.console.LocalDemoBootstrap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class PublicDemoBootstrapIT {
    private static DataSource migration;
    private static JdbcTemplate admin;
    private static String jdbcUrl;
    private static final UUID OTHER = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddd01");

    @BeforeAll
    static void createSeparateDatabaseOnlyInsideTheFixtureOwnedContainer() {
        var fixture = DatabaseFixture.start();
        var container = fixture.ownedContainer();
        new JdbcTemplate(fixture.adminDataSource()).execute("CREATE DATABASE worklog_demo OWNER tna_migration");
        jdbcUrl = fixture.jdbcUrl().replace("/" + DatabaseFixture.DATABASE_NAME, "/worklog_demo");
        migration = new DriverManagerDataSource(jdbcUrl,
                DatabaseFixture.MIGRATION_USER, DatabaseFixture.MIGRATION_PASSWORD);
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, container.getUsername(), container.getPassword()));
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }

    @AfterEach
    void clearOnlyThisTestOwnedDatabase() {
        admin.update("UPDATE tna.employee SET created_by=NULL,updated_by=NULL WHERE tenant_id=?", LocalDemoLogin.TENANT_ID);
        for (String table : List.of("tna.attendance_type_version", "tna.attendance_type", "tna.common_code", "tna.common_code_group",
                "tna.org_audit_event", "tna.account_token", "tna.role_grant",
                "tna.tenant_account", "tna.employee")) {
            admin.update("DELETE FROM " + table + " WHERE tenant_id IN (?,?)", LocalDemoLogin.TENANT_ID, OTHER);
        }
        admin.update("DELETE FROM platform.tenant_registry WHERE id IN (?,?)", LocalDemoLogin.TENANT_ID, OTHER);
    }

    @Test
    void emptyDatabaseIsSeededAndReservedOnlyReplayPreservesEveryRow() throws Exception {
        assertEquals(LocalDemoLogin.TENANT_ID, LocalDemoBootstrap.bootstrapPublic(migration));
        String before = snapshot();
        assertEquals(LocalDemoLogin.TENANT_ID, LocalDemoBootstrap.bootstrapPublic(migration));
        assertEquals(before, snapshot());
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM platform.tenant_registry", Integer.class));
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM tna.tenant_account", Integer.class));
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM tna.org_audit_event", Integer.class));
    }

    @Test
    void migrationRlsRemainsScopedAndOtherTenantsAreNeverChanged() throws Exception {
        addOtherTenant();
        String before = admin.queryForList("SELECT * FROM platform.tenant_registry WHERE id=?", OTHER).toString();
        assertEquals(0, new JdbcTemplate(migration).queryForObject(
                "SELECT count(*) FROM platform.tenant_registry", Integer.class));
        LocalDemoBootstrap.bootstrapPublic(migration);
        LocalDemoBootstrap.bootstrapPublic(migration);
        assertEquals(before, admin.queryForList("SELECT * FROM platform.tenant_registry WHERE id=?", OTHER).toString());
        assertEquals(0, new JdbcTemplate(migration).queryForObject(
                "SELECT count(*) FROM platform.tenant_registry", Integer.class));
    }

    @Test
    void changedReservedIdentityIsRejectedWithoutRepair() throws Exception {
        LocalDemoBootstrap.bootstrapPublic(migration);
        admin.update("UPDATE tna.tenant_account SET active=false WHERE tenant_id=?", LocalDemoLogin.TENANT_ID);
        assertRefusedWithoutChanges();
    }

    @Test
    void concurrentPublicCommandsProduceOneAccountAndOneAudit() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> LocalDemoBootstrap.bootstrapPublic(migration));
            var second = workers.submit(() -> LocalDemoBootstrap.bootstrapPublic(migration));
            assertEquals(LocalDemoLogin.TENANT_ID, first.get(20, TimeUnit.SECONDS));
            assertEquals(LocalDemoLogin.TENANT_ID, second.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM tna.tenant_account", Integer.class));
        assertEquals(1, admin.queryForObject("SELECT count(*) FROM tna.org_audit_event", Integer.class));
    }

    @Test
    void differentActualDatabaseIsRejectedEvenForTheMigrationRole() {
        assertThrows(IllegalStateException.class,
                () -> LocalDemoBootstrap.bootstrapPublic(DatabaseFixture.start().migrationDataSource()));
    }

    private void addOtherTenant() {
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,'OTHER-PUBLIC-TEST','다른 합성 고객사')", OTHER);
    }

    private void assertRefusedWithoutChanges() {
        String before = snapshot();
        assertThrows(IllegalStateException.class, () -> LocalDemoBootstrap.bootstrapPublic(migration));
        assertEquals(before, snapshot());
    }

    private String snapshot() {
        return List.of(admin.queryForList("SELECT * FROM platform.tenant_registry ORDER BY id"),
                admin.queryForList("SELECT * FROM tna.employee ORDER BY tenant_id,id"),
                admin.queryForList("SELECT * FROM tna.tenant_account ORDER BY tenant_id,id"),
                admin.queryForList("SELECT * FROM tna.role_grant ORDER BY tenant_id,account_id,role"),
                admin.queryForList("SELECT * FROM tna.org_audit_event ORDER BY tenant_id,id")).toString();
    }
}
