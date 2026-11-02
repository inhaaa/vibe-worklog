package kr.tna;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.console.LocalDemoBootstrap;
import kr.tna.console.PublicDemoPersonnelFill;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static kr.tna.PublicDemoPersonnelFillTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicDemoPersonnelFillIT {
    private static PostgreSQLContainer container;
    private static DataSource app;
    private static DataSource migration;
    private static JdbcTemplate admin;
    private static String url;
    @TempDir Path directory;

    @BeforeAll
    static void dedicatedContainerCannotTargetAnyExistingDatabase() {
        container = new PostgreSQLContainer(DockerImageName.parse(DatabaseFixture.postgresImage().get())
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("worklog_demo").withUsername("personnel_fixture_owner")
                .withPassword("Synthetic-Personnel-Owner-Only-43!")
                .withCreateContainerCmdModifier(DatabaseFixture::bindOwnedPostgresToLoopback);
        container.start();
        url = container.getJdbcUrl();
        admin = new JdbcTemplate(new DriverManagerDataSource(url, container.getUsername(), container.getPassword()));
        for (String role : List.of("tna_migration", "tna_app", "tna_auth", "tna_operator")) {
            admin.execute("CREATE ROLE " + role + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'Synthetic-Personnel-Fixture-Only-43!'");
        }
        admin.execute("GRANT CREATE ON DATABASE worklog_demo TO tna_migration");
        admin.execute("GRANT USAGE,CREATE ON SCHEMA public TO tna_migration");
        migration = source("tna_migration");
        app = source("tna_app");
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stopOnlyTheFixtureOwnedContainer() {
        if (container != null) container.stop();
    }

    @BeforeEach
    void syntheticFourEmployeeFixture() throws Exception {
        var tables = admin.queryForList("""
                SELECT quote_ident(n.nspname)||'.'||quote_ident(c.relname)
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE c.relkind='r' AND (n.nspname='tna' OR (n.nspname='platform' AND c.relname='tenant_registry'))
                """, String.class);
        admin.execute("TRUNCATE " + String.join(",", tables) + " CASCADE");
        LocalDemoBootstrap.bootstrapPublic(migration);
        // V14 provisions tenant codes automatically; the historical empty-V10 sample script is not a V16 fixture.
        UUID operations = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0101");
        UUID development = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0102");
        admin.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?, 'DEMO-OPS','가상 운영팀',?,?),(?,?, 'DEMO-DEV','가상 개발팀',?,?)",
                LocalDemoLogin.TENANT_ID, operations, LocalDemoLogin.ACCOUNT_ID, LocalDemoLogin.ACCOUNT_ID,
                LocalDemoLogin.TENANT_ID, development, LocalDemoLogin.ACCOUNT_ID, LocalDemoLogin.ACCOUNT_ID);
        for (int index = 1; index < 4; index++) {
            admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,DATE '2026-01-01')",
                    LocalDemoLogin.TENANT_ID, UUID.fromString(IDS.get(index)), "DEMO-00" + index,
                    List.of("", "가상 사원 하나", "가상 사원 둘", "가상 사원 셋").get(index));
        }
        for (int index = 0; index < 4; index++) {
            admin.update("INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,DATE '2026-01-01','합성 소속',?,?)",
                    LocalDemoLogin.TENANT_ID, UUID.randomUUID(), UUID.fromString(IDS.get(index)),
                    index < 2 ? operations : development, LocalDemoLogin.ACCOUNT_ID, LocalDemoLogin.ACCOUNT_ID);
        }
        admin.update("""
                INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                    reason,before_state,after_state,request_trace_id)
                VALUES (?,?,?,?,'PUBLIC_DEMO_SAMPLE_BOOTSTRAP','TENANT',?,?,'합성 fixture','{}'::jsonb,
                    '{"synthetic":true,"organizations":2,"employees":4}'::jsonb,?)
                """, LocalDemoLogin.TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), LocalDemoLogin.ACCOUNT_ID,
                LocalDemoLogin.TENANT_ID, "a".repeat(64), UUID.randomUUID());
        assets(directory);
    }

    @Test
    void insertsOnlyFourPersonnelFourStatusAndFiveSafeAuditsAndPreservesEveryExistingRow() throws Exception {
        String before = preservedSnapshot();
        assertTrue(fill());
        assertEquals(before, preservedSnapshot());
        assertEquals(4, count("employee_personnel"));
        assertEquals(4, count("employee_employment_status"));
        assertEquals(7, count("org_audit_event"));
        assertEquals(List.of("WORKING", "WORKING", "ON_LEAVE", "SUSPENDED"),
                IDS.stream().map(id -> admin.queryForObject("SELECT employment_status FROM tna.employee_employment_status WHERE employee_id=?", String.class, UUID.fromString(id))).toList());
        for (String id : IDS) {
            assertEquals(1L, admin.queryForObject("SELECT version FROM tna.employee_personnel WHERE employee_id=?", Long.class, UUID.fromString(id)));
            assertEquals(TODAY, admin.queryForObject("SELECT effective_from FROM tna.employee_employment_status WHERE employee_id=?", java.time.LocalDate.class, UUID.fromString(id)));
        }
        String audit = admin.queryForObject("SELECT string_agg(before_state::text||after_state::text,'') FROM tna.org_audit_event WHERE action LIKE 'PUBLIC_DEMO_PERSONNEL_FILL%'", String.class);
        for (String birth : List.of("1988-07-14", "1992-03-11", "1986-09-22", "1996-12-05")) assertFalse(audit.contains(birth));
        assertFalse(audit.contains("photoData"));
        assertFalse(audit.contains("photo_bytes"));
    }

    @Test
    void exactReplayDoesNotChangePhotosVersionsHistoryOrAudit() throws Exception {
        assertTrue(fill());
        String snapshot = completeSnapshot();
        assertFalse(fill());
        assertEquals(snapshot, completeSnapshot());
    }

    @Test
    void actualAiPhotoAssetsAreNormalizedAndTheirStoredHashesMatchThePinnedManifest() throws Exception {
        Path realAssets = Path.of("demo/personnel-20261102");
        assertTrue(PublicDemoPersonnelFill.fill(app, realAssets, TODAY, CLOCK));
        var manifest = tools.jackson.databind.json.JsonMapper.builder().build().readTree(Files.readString(realAssets.resolve("manifest.json")));
        for (var row : manifest.path("employees")) {
            assertEquals(row.path("normalizedSha256").asText(), admin.queryForObject(
                    "SELECT encode(sha256(photo_bytes),'hex') FROM tna.employee_personnel WHERE employee_id=?",
                    String.class, UUID.fromString(row.path("employeeId").asText())));
        }
    }

    @Test
    void changedMetadataPhotoOrStatusAfterRegistrationIsNeverRepaired() throws Exception {
        fill();
        admin.update("UPDATE tna.employee_personnel SET grade_name='외부 변경' WHERE employee_id=?", UUID.fromString(IDS.get(2)));
        refusedWithoutChanges();
        admin.update("UPDATE tna.employee_personnel SET grade_name='수석',photo_bytes=decode('010203','hex') WHERE employee_id=?", UUID.fromString(IDS.get(2)));
        refusedWithoutChanges();
    }

    @Test
    void preexistingOrPartialPersonnelIsRejectedBeforeAnyOfTheOtherThreeAreWritten() throws Exception {
        admin.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,version,updated_by) VALUES (?,?,1,?)",
                LocalDemoLogin.TENANT_ID, UUID.fromString(IDS.get(2)), LocalDemoLogin.ACCOUNT_ID);
        refusedWithoutChanges();
        assertEquals(1, count("employee_personnel"));
        assertEquals(0, count("employee_employment_status"));
    }

    @Test
    void aLateDatabaseFailureRollsBackTheWholeBatchIncludingAudits() throws Exception {
        admin.execute("ALTER TABLE tna.employee_personnel ADD CONSTRAINT synthetic_reject_third CHECK (employee_id<>'" + IDS.get(2) + "'::uuid)");
        try { refusedWithoutChanges(); }
        finally { admin.execute("ALTER TABLE tna.employee_personnel DROP CONSTRAINT synthetic_reject_third"); }
        assertEquals(0, count("employee_personnel"));
        assertEquals(0, count("employee_employment_status"));
        assertEquals(2, count("org_audit_event"));
    }

    @Test
    void concurrentCommandsProduceOneExactBatchAndOneNoop() throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return fill(); });
            var second = workers.submit(() -> { start.await(); return fill(); });
            start.countDown();
            assertEquals(1, List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))
                    .stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(4, count("employee_personnel"));
        assertEquals(4, count("employee_employment_status"));
        assertEquals(7, count("org_audit_event"));
    }

    @Test
    void reservedIdentityOrganizationAndCurrentRolesMustStillMatch() throws Exception {
        admin.update("UPDATE tna.employee SET display_name='변경된 가상 사원' WHERE id=?", UUID.fromString(IDS.get(1)));
        refusedWithoutChanges();
        admin.update("UPDATE tna.employee SET display_name='가상 사원 하나' WHERE id=?", UUID.fromString(IDS.get(1)));
        admin.update("UPDATE tna.role_grant SET active=false WHERE role='ATTENDANCE_ADMIN'");
        refusedWithoutChanges();
    }

    @Test
    void privilegedRoleWrongDatabaseAndOtherTenantCannotEnterTheMaintenanceBoundary() throws Exception {
        String before = completeSnapshot();
        assertThrows(RuntimeException.class, () -> PublicDemoPersonnelFill.fill(migration, directory, TODAY, CLOCK));
        assertEquals(before, completeSnapshot());
        admin.execute("CREATE DATABASE other_personnel_fixture");
        try {
            var wrong = new DriverManagerDataSource(url.replace("/worklog_demo", "/other_personnel_fixture"), "tna_app", "Synthetic-Personnel-Fixture-Only-43!");
            assertThrows(RuntimeException.class, () -> PublicDemoPersonnelFill.fill(wrong, directory, TODAY, CLOCK));
        } finally { admin.execute("DROP DATABASE other_personnel_fixture"); }
        fill();
        var jdbc = new JdbcTemplate(app);
        new TenantTransactions(app).execute(UUID.randomUUID(), () -> {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personnel", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee_employment_status", Integer.class));
            return null;
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personnel", Integer.class));
    }

    private boolean fill() throws Exception { return PublicDemoPersonnelFill.fill(app, directory, TODAY, CLOCK); }

    private void refusedWithoutChanges() throws Exception {
        String before = completeSnapshot();
        assertThrows(RuntimeException.class, this::fill);
        assertEquals(before, completeSnapshot());
    }

    private String preservedSnapshot() {
        return snapshot(false);
    }

    private String completeSnapshot() { return snapshot(true); }

    private String snapshot(boolean includeNew) {
        var tables = admin.queryForList("""
                SELECT quote_ident(n.nspname)||'.'||quote_ident(c.relname)
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE c.relkind='r' AND (n.nspname='tna' OR (n.nspname='platform' AND c.relname='tenant_registry'))
                ORDER BY 1
                """, String.class);
        List<String> rows = new ArrayList<>();
        for (String table : tables) {
            if (!includeNew && (table.endsWith("employee_personnel") || table.endsWith("employee_employment_status"))) continue;
            String filter = !includeNew && table.endsWith("org_audit_event") ? " WHERE action NOT LIKE 'PUBLIC_DEMO_PERSONNEL_FILL%'" : "";
            rows.add(table + admin.queryForList("SELECT to_jsonb(r)::text FROM " + table + " r" + filter + " ORDER BY to_jsonb(r)::text", String.class));
        }
        return rows.toString();
    }

    private int count(String table) { return admin.queryForObject("SELECT count(*) FROM tna." + table, Integer.class); }
    private static DataSource source(String role) { return new DriverManagerDataSource(url, role, "Synthetic-Personnel-Fixture-Only-43!"); }
}
