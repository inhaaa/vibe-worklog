package kr.tna;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.worktime.ReviewWorkbook;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Explicit synthetic logical-restore probe; not part of default Test/IT discovery. */
public final class Phase6RecoveryProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> RUNTIME_ROLES = List.of("tna_app", "tna_auth", "tna_operator");

    public static void main(String[] args) throws Exception {
        if (args.length != 0 || supplied(System.getProperty("tna.test.jdbc-url"))
                || supplied(System.getenv("TNA_TEST_JDBC_URL"))) {
            throw new IllegalArgumentException("Recovery requires a new fixture-owned container; external database targets are refused");
        }
        var fixture = DatabaseFixture.start();
        var container = fixture.ownedContainer();
        check(DatabaseFixture.DATABASE_NAME.equals(container.getDatabaseName()), "Unexpected source database");
        var source = new JdbcTemplate(fixture.adminDataSource());
        check(DatabaseFixture.DATABASE_NAME.equals(source.queryForObject("SELECT current_database()", String.class)), "Source database mismatch");
        seed(fixture, source);
        Map<String,Object> before = manifest(source);

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String restoredName = "tna_phase6_restore_" + suffix;
        String remoteDump = "/tmp/tna_phase6_" + suffix + ".dump";
        Path directory = Path.of("target").toAbsolutePath();
        Files.createDirectories(directory);
        Path dump = directory.resolve("phase6-recovery-" + suffix + ".dump");
        long started = System.nanoTime();
        execute(container, "pg_dump", "--username=" + container.getUsername(), "--format=custom",
                "--file=" + remoteDump, "--dbname=" + DatabaseFixture.DATABASE_NAME);
        container.copyFileFromContainer(remoteDump, dump.toString());
        long dumpMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        // No --create/--clean: restoration must never reconnect to the source name in the archive.
        execute(container, "createdb", "--username=" + container.getUsername(), "--template=template0", restoredName);
        URI address = URI.create(fixture.jdbcUrl().substring("jdbc:".length()));
        String restoredUrl = "jdbc:postgresql://" + address.getHost() + ":" + address.getPort() + "/" + restoredName;
        var restored = new JdbcTemplate(new DriverManagerDataSource(restoredUrl, container.getUsername(), container.getPassword()));
        check(restoredName.equals(restored.queryForObject("SELECT current_database()", String.class)), "Destination database mismatch");
        restored.execute("REVOKE ALL ON DATABASE " + restoredName + " FROM PUBLIC");
        restored.execute("GRANT CONNECT ON DATABASE " + restoredName + " TO tna_app,tna_auth,tna_operator,tna_migration");
        started = System.nanoTime();
        execute(container, "pg_restore", "--username=" + container.getUsername(), "--single-transaction",
                "--exit-on-error", "--dbname=" + restoredName, remoteDump);
        long restoreMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        Map<String,Object> after = manifest(restored);
        Files.writeString(directory.resolve("phase6-recovery-source-manifest.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(before));
        Files.writeString(directory.resolve("phase6-recovery-restored-manifest.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(after));
        Map<String,Object> comparableBefore = comparable(before), comparableAfter = comparable(after);
        check(comparableBefore.equals(comparableAfter), "Restored manifest differs in: " + before.keySet().stream()
                .filter(key -> !java.util.Objects.equals(comparableBefore.get(key), comparableAfter.get(key))).toList());
        check(before.equals(manifest(source)), "Source changed during the dump/restore probe");
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> security = (List<Map<String,Object>>) after.get("rowSecurity");
        check(security.size() == 46 && security.stream().allMatch(row -> Boolean.TRUE.equals(row.get("relrowsecurity"))
                && Boolean.TRUE.equals(row.get("relforcerowsecurity"))), "Expected 46 FORCE RLS business tables");
        verifyRuntimeRoles(restored);
        verifyIsolation(restoredUrl);
        verifyAuthenticationPurposes(restoredUrl);

        var report = new LinkedHashMap<String,Object>();
        report.put("measuredAt", Instant.now().toString());
        report.put("scope", "Same-cluster logical restore with existing global roles; excludes cluster loss, role re-creation and operational RPO/RTO");
        report.put("sourceDatabase", DatabaseFixture.DATABASE_NAME);
        report.put("restoredDatabase", restoredName);
        report.put("dumpFile", dump.toString());
        report.put("dumpSha256", sha(Files.readAllBytes(dump)));
        report.put("dumpMillis", dumpMillis);
        report.put("restoreMillis", restoreMillis);
        report.put("sourceUnchanged", true);
        report.put("runtimeIsolation", "No context: zero rows; A/B own rows only; auth/operator cannot SELECT raw attendance");
        report.put("policyComparison", "Only INVITE/RESET and ACCOUNT_RESET_REQUEST/ACCOUNT_RESET_COMPLETE varchar-array-to-text casts are normalized in their known authentication policies; raw manifests are retained");
        report.put("authenticationPurposes", "Actual tna_auth login reads INVITE/RESET within A/B only; unknown purpose insertion is rejected");
        report.put("restoredManifest", after);
        Path output = directory.resolve("phase6-recovery-result.json");
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("QUALITY_RECOVERY_RESULT=" + output);
        // The owned fixture's existing shutdown hook removes its temporary container and both databases.
    }

    private static void seed(DatabaseFixture fixture, JdbcTemplate db) throws Exception {
        fixture.seedIdentity(PasswordPolicy.passwordEncoder().encode("Synthetic recovery credential 43!"));
        seedEvidence(db, DatabaseFixture.TENANT_A, DatabaseFixture.EMPLOYEE_A, DatabaseFixture.ACCOUNT_A);
        seedEvidence(db, DatabaseFixture.TENANT_B, DatabaseFixture.EMPLOYEE_B, DatabaseFixture.ACCOUNT_B);
    }

    private static void seedEvidence(JdbcTemplate db, UUID tenant, UUID employee, UUID account) throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 23);
        UUID network = UUID.randomUUID(), in = UUID.randomUUID(), out = UUID.randomUUID();
        UUID run = UUID.randomUUID(), result = UUID.randomUUID(), snapshot = UUID.randomUUID();
        var photoOutput = new java.io.ByteArrayOutputStream();
        check(javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", photoOutput), "Synthetic photo encoding failed");
        db.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,grade_name,photo_content_type,photo_bytes,version,updated_by) VALUES (?,?,DATE '1990-01-01','합성 복원 직급','image/png',?,1,?)", tenant, employee, photoOutput.toByteArray(), account);
        db.update("INSERT INTO tna.employee_employment_status(tenant_id,id,employee_id,employment_status,effective_from,revision,reason,created_by) VALUES (?,?,?,'WORKING',?,1,'합성 인사 복원 근거',?)", tenant, UUID.randomUUID(), employee, date, account);
        for (String purpose : List.of("INVITE", "RESET")) {
            db.update("INSERT INTO tna.account_token(tenant_id,id,account_id,purpose,token_hash,email,issued_auth_version,expires_at,created_by) VALUES (?,?,?,?,?,?,1,?,?)",
                    tenant, UUID.randomUUID(), account, purpose, sha((tenant + ":" + purpose).getBytes(StandardCharsets.UTF_8)),
                    DatabaseFixture.EMAIL, timestamp(date.plusDays(1), 12), account);
        }
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 복원 허용망',?,'합성 복원 근거',?)",
                tenant, network, timestamp(date, 0), account);
        for (String kind : List.of("IN", "OUT")) {
            Timestamp time = timestamp(date, kind.equals("IN") ? 9 : 18);
            db.update("INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)",
                    tenant, kind.equals("IN") ? in : out, employee, kind, time, time, UUID.randomUUID(), network, date);
        }
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,2,1)", tenant, employee, date);
        db.update("INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version,status,started_at,finished_at) VALUES (?,?,?,?,?,?,?,?,'CALC-1','COMPLETED',?,?)",
                tenant, run, employee, account, date, date, date.minusDays(4), date.plusDays(2), timestamp(date, 18), timestamp(date, 19));
        String refs = JSON.writeValueAsString(List.of(Map.of("purpose", "SYNTHETIC_RECOVERY", "employeeId", employee,
                "workDate", date, "rawEventIds", List.of(in, out), "actualWorkMillis", 28_800_000L)));
        db.update("INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,source_fingerprint,rule_version,status,scheduled_millis,presence_millis,actual_work_millis,confirmed_break_millis,leave_days,reason_codes,input_refs) VALUES (?,?,?,?,?,1,2,1,?,'CALC-1','CALCULATED',28800000,32400000,28800000,3600000,0,'[]'::jsonb,CAST(? AS jsonb))",
                tenant, result, run, employee, date, "a".repeat(64), refs);
        byte[] workbook = ReviewWorkbook.create(List.of(List.of("합성 복원 검토용 REVIEW", "근무일", "확인 실근로(ms)", "결과 ID"),
                List.of(employee.toString(), date.toString(), 28_800_000L, result.toString())));
        db.update("INSERT INTO tna.report_snapshot(tenant_id,id,requested_by,employee_id,from_date,to_date,filters,result_refs,workbook) VALUES (?,?,?,?,?,?,'{\"purpose\":\"SYNTHETIC_RECOVERY\"}'::jsonb,CAST(? AS jsonb),?)",
                tenant, snapshot, account, employee, date, date, JSON.writeValueAsString(List.of(Map.of("resultId", result))), workbook);
        db.update("INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,reason,before_state,after_state,request_trace_id) VALUES (?,?,?,?,'SYNTHETIC_RECOVERY','REPORT_SNAPSHOT',?,?,'합성 복원 증거','{}'::jsonb,CAST(? AS jsonb),?)",
                tenant, UUID.randomUUID(), UUID.randomUUID(), account, snapshot, "b".repeat(64),
                JSON.writeValueAsString(Map.of("resultId", result, "snapshotId", snapshot)), UUID.randomUUID());
    }

    private static Map<String,Object> manifest(JdbcTemplate db) throws Exception {
        var counts = new LinkedHashMap<String,Long>();
        for (var table : db.queryForList("SELECT schemaname,tablename FROM pg_tables WHERE schemaname IN ('tna','platform') OR(schemaname='public' AND tablename='flyway_schema_history') ORDER BY schemaname,tablename")) {
            String name = table.get("schemaname") + "." + table.get("tablename");
            check(name.matches("[a-z_]+\\.[a-z_]+"), "Unexpected table identifier");
            counts.put(name, db.queryForObject("SELECT count(*) FROM " + name, Long.class));
        }
        var hashes = new LinkedHashMap<String,String>();
        for (String table : List.of("attendance_event", "day_result", "report_snapshot", "org_audit_event")) {
            for (var row : db.queryForList("SELECT tenant_id,id,to_jsonb(t)::text AS body FROM tna." + table + " t ORDER BY tenant_id,id")) {
                hashes.put(table + "/" + row.get("tenant_id") + "/" + row.get("id"), sha(row.get("body").toString().getBytes(StandardCharsets.UTF_8)));
            }
        }
        for (var row : db.queryForList("SELECT tenant_id,id,input_refs::text AS input_refs FROM tna.day_result ORDER BY tenant_id,id")) {
            hashes.put("input_refs/" + row.get("id"), sha(row.get("input_refs").toString().getBytes(StandardCharsets.UTF_8)));
        }
        db.query("SELECT id,workbook FROM tna.report_snapshot ORDER BY tenant_id,id", row -> {
            try { hashes.put("workbook/" + row.getObject("id"), sha(row.getBytes("workbook"))); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        var personnelHashes = new LinkedHashMap<String,String>();
        for (String table : List.of("employee_personnel", "employee_employment_status")) {
            String identifier = table.equals("employee_personnel") ? "employee_id" : "id";
            for (var row : db.queryForList("SELECT tenant_id," + identifier + " AS id,to_jsonb(t)::text AS body FROM tna." + table + " t ORDER BY tenant_id," + identifier)) {
                personnelHashes.put(table + "/" + row.get("tenant_id") + "/" + row.get("id"), sha(row.get("body").toString().getBytes(StandardCharsets.UTF_8)));
            }
        }
        return Map.of("tableCounts", counts, "immutableSha256", hashes, "personnelSha256", personnelHashes,
                "rowSecurity", db.queryForList("SELECT n.nspname,c.relname,c.relrowsecurity,c.relforcerowsecurity,pg_get_userbyid(c.relowner) AS owner FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('tna','platform') AND c.relkind IN ('r','p') ORDER BY n.nspname,c.relname"),
                "policies", db.queryForList("SELECT schemaname,tablename,policyname,permissive,roles::text,cmd,qual,with_check FROM pg_policies WHERE schemaname IN ('tna','platform') ORDER BY schemaname,tablename,policyname"),
                "definerOwners", db.queryForList("SELECT n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) AS args,pg_get_userbyid(p.proowner) AS owner FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname IN ('tna','platform') AND p.prosecdef ORDER BY n.nspname,p.proname,args"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> comparable(Map<String,Object> manifest) {
        var comparable = new LinkedHashMap<>(manifest);
        var policies = ((List<Map<String,Object>>) manifest.get("policies")).stream().map(policy -> {
            var copy = new LinkedHashMap<>(policy);
            boolean token = "account_token".equals(policy.get("tablename"))
                    && List.of("authentication_token_read", "authentication_token_update").contains(policy.get("policyname"));
            boolean audit = "org_audit_event".equals(policy.get("tablename"))
                    && "authentication_reset_audit".equals(policy.get("policyname"));
            if (token || audit) {
                for (String field : List.of("qual", "with_check")) {
                    if (copy.get(field) instanceof String expression) {
                        copy.put(field, canonicalCast(expression, token ? "INVITE" : "ACCOUNT_RESET_REQUEST",
                                token ? "RESET" : "ACCOUNT_RESET_COMPLETE"));
                    }
                }
            }
            return copy;
        }).toList();
        comparable.put("policies", policies);
        return comparable;
    }

    private static String canonicalCast(String expression, String first, String second) {
        String array = "(ARRAY['" + first + "'::character varying, '" + second + "'::character varying])::text[]";
        String elements = "ARRAY[('" + first + "'::character varying)::text, ('" + second + "'::character varying)::text]";
        return expression.replace(array, elements);
    }

    private static void verifyRuntimeRoles(JdbcTemplate db) {
        for (String role : RUNTIME_ROLES) {
            check(Boolean.TRUE.equals(db.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolinherit FROM pg_roles WHERE rolname=?", Boolean.class, role)), "Unsafe runtime role: " + role);
        }
        check(!Boolean.TRUE.equals(db.queryForObject("SELECT has_table_privilege('tna_app','tna.attendance_event','UPDATE') OR has_table_privilege('tna_app','tna.attendance_event','DELETE')", Boolean.class)), "Raw-event mutation granted");
    }

    private static void verifyIsolation(String url) {
        var configuration = new HikariConfig();
        configuration.setJdbcUrl(url);
        configuration.setUsername(DatabaseFixture.APP_USER);
        configuration.setPassword(DatabaseFixture.APP_PASSWORD);
        configuration.setMaximumPoolSize(1);
        configuration.setMinimumIdle(0);
        try (var dataSource = new HikariDataSource(configuration)) {
            var app = new JdbcTemplate(dataSource);
            var transactions = new TenantTransactions(dataSource);
            check("tna_app".equals(app.queryForObject("SELECT current_user", String.class)), "Expected actual app login");
            check(app.queryForObject("SELECT count(*) FROM tna.employee", Integer.class) == 0, "Missing context exposed employees");
            for (UUID tenant : List.of(DatabaseFixture.TENANT_A, DatabaseFixture.TENANT_B)) {
                UUID other = tenant.equals(DatabaseFixture.TENANT_A) ? DatabaseFixture.TENANT_B : DatabaseFixture.TENANT_A;
                transactions.execute(tenant, () -> {
                    check(app.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=?", Integer.class, tenant) == 1, "Own employee missing");
                    check(app.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=?", Integer.class, tenant) == 2, "Own raw events missing");
                    check(app.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=?", Integer.class, tenant) == 1, "Own result missing");
                    check(app.queryForObject("SELECT count(*) FROM tna.report_snapshot WHERE tenant_id=?", Integer.class, tenant) == 1, "Own workbook missing");
                    check(app.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=?", Integer.class, other) == 0, "Foreign employee exposed");
                    check(app.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=?", Integer.class, other) == 0, "Foreign raw events exposed");
                    return null;
                });
            }
            check(app.queryForObject("SELECT count(*) FROM tna.employee", Integer.class) == 0, "Tenant context leaked on the reused connection");
        }
        denied(new JdbcTemplate(new DriverManagerDataSource(url, DatabaseFixture.AUTH_USER, DatabaseFixture.AUTH_PASSWORD)), "tna_auth");
        denied(new JdbcTemplate(new DriverManagerDataSource(url, "tna_operator", "synthetic-operator-db-password-only")), "tna_operator");
    }

    private static void denied(JdbcTemplate db, String expectedRole) {
        check(expectedRole.equals(db.queryForObject("SELECT current_user", String.class)), "Expected actual restricted-role login");
        try { db.queryForObject("SELECT count(*) FROM tna.attendance_event", Integer.class); }
        catch (DataAccessException expected) {
            if (expected.getMostSpecificCause() instanceof java.sql.SQLException sql && "42501".equals(sql.getSQLState())) return;
            throw expected;
        }
        throw new AssertionError("Authentication/operator role could read raw attendance");
    }

    private static void verifyAuthenticationPurposes(String url) {
        var source = new DriverManagerDataSource(url, DatabaseFixture.AUTH_USER, DatabaseFixture.AUTH_PASSWORD);
        var auth = new JdbcTemplate(source);
        var transactions = new TenantTransactions(source);
        check(auth.queryForObject("SELECT count(*) FROM tna.account_token", Integer.class) == 0, "Authentication token context missing");
        for (UUID tenant : List.of(DatabaseFixture.TENANT_A, DatabaseFixture.TENANT_B)) {
            UUID other = tenant.equals(DatabaseFixture.TENANT_A) ? DatabaseFixture.TENANT_B : DatabaseFixture.TENANT_A;
            transactions.execute(tenant, () -> {
                check(auth.queryForList("SELECT purpose FROM tna.account_token ORDER BY purpose", String.class)
                        .equals(List.of("INVITE", "RESET")), "Authentication token purpose set changed");
                check(auth.queryForObject("SELECT count(*) FROM tna.account_token WHERE tenant_id=?", Integer.class, other) == 0,
                        "Authentication token from another tenant exposed");
                return null;
            });
        }
        try {
            transactions.execute(DatabaseFixture.TENANT_A, () -> {
                auth.update("INSERT INTO tna.account_token(tenant_id,id,account_id,purpose,token_hash,email,issued_auth_version,expires_at,created_by) VALUES (?,?,?,'OTHER',?,?,1,?,?)",
                        DatabaseFixture.TENANT_A, UUID.randomUUID(), DatabaseFixture.ACCOUNT_A, "c".repeat(64),
                        DatabaseFixture.EMAIL, timestamp(LocalDate.of(2026, 10, 24), 12), DatabaseFixture.ACCOUNT_A);
                throw new AssertionError("Unknown authentication token purpose accepted");
            });
        } catch (DataAccessException expected) {
            if (expected.getMostSpecificCause() instanceof java.sql.SQLException sql
                    && List.of("42501", "23514").contains(sql.getSQLState())) return;
            throw expected;
        }
    }

    private static void execute(PostgreSQLContainer container, String... command) throws Exception {
        var outcome = container.execInContainer(command);
        check(outcome.getExitCode() == 0, command[0] + " failed: " + outcome.getStderr());
    }

    private static Timestamp timestamp(LocalDate date, int hour) {
        return Timestamp.from(date.atTime(hour, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static boolean supplied(String value) { return value != null && !value.isBlank(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
