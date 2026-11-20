package kr.tna.console;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.BulkData;
import kr.tna.org.EmployeePhoto;
import kr.tna.org.OrgCommands;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Explicit, fixed synthetic maintenance; never a web or automatic startup path. */
public final class PublicDemoPersonnelFill {
    private static final String ACTION = "PUBLIC_DEMO_PERSONNEL_FILL";
    private static final String CONFIRMATION = "worklog-demo-personnel-20261102";
    private static final String REASON = "공개 데모 유지보수 합성 인사 등록; 실제 사원정보·일반 사용자 변경 이력이 아님";
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 11, 2);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final UUID TENANT = LocalDemoLogin.TENANT_ID;
    private static final UUID ACCOUNT = LocalDemoLogin.ACCOUNT_ID;
    private static final UUID COMMAND = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0911");
    private static final UUID OPERATIONS = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0101");
    private static final UUID DEVELOPMENT = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0102");
    private static final Pattern LOOPBACK = Pattern.compile("jdbc:postgresql://(?:127\\.0\\.0\\.1|\\[::1\\]):([0-9]{1,5})/worklog_demo");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<Definition> PEOPLE = List.of(
            person("dddddddd-dddd-4ddd-8ddd-ddddddddddd1", "LOCAL-DEMO", "로컬 데모 담당자", "1988-07-14", "책임", "운영팀장", "인사 운영", "WORKING", "local-demo"),
            person("dddddddd-dddd-4ddd-8ddd-dddddddd0201", "DEMO-001", "가상 사원 하나", "1992-03-11", "선임", "팀원", "서비스 기획", "WORKING", "demo-001"),
            person("dddddddd-dddd-4ddd-8ddd-dddddddd0202", "DEMO-002", "가상 사원 둘", "1986-09-22", "수석", "개발팀장", "소프트웨어 개발", "ON_LEAVE", "demo-002"),
            person("dddddddd-dddd-4ddd-8ddd-dddddddd0203", "DEMO-003", "가상 사원 셋", "1996-12-05", "사원", "팀원", "품질 관리", "SUSPENDED", "demo-003"));

    private PublicDemoPersonnelFill() { }

    public static DataSource configuration(Map<String, String> environment) {
        if (!"public-demo".equals(required(environment, "SPRING_PROFILES_ACTIVE"))) throw invalid();
        String url = required(environment, "TNA_JDBC_URL");
        var loopback = LOOPBACK.matcher(url);
        if (!url.equals("jdbc:postgresql://db:5432/worklog_demo")
                && (!loopback.matches() || Integer.parseInt(loopback.group(1)) < 1
                    || Integer.parseInt(loopback.group(1)) > 65535)) throw invalid();
        return new DriverManagerDataSource(url, "tna_app", required(environment, "TNA_APP_DB_PASSWORD"));
    }

    public static boolean fill(DataSource source, Path directory, LocalDate expectedToday, Clock clock) {
        Prepared prepared = prepare(directory, expectedToday, clock);
        var transactions = new TenantTransactions(source);
        var jdbc = new JdbcTemplate(source);
        var commands = new OrgCommands(transactions, source, clock);
        return transactions.execute(TENANT, () -> {
            verifyDatabase(jdbc);
            jdbc.execute("SET LOCAL TIME ZONE 'Asia/Seoul'");
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))", Object.class, TENANT);
            var actor = verifyIdentity(jdbc, clock);
            boolean replay = count(jdbc, "SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?", TENANT, COMMAND) != 0;
            if (replay) verifyRegistered(jdbc, prepared);
            else if (count(jdbc, "SELECT count(*) FROM tna.employee_personnel WHERE tenant_id=?", TENANT) != 0
                    || count(jdbc, "SELECT count(*) FROM tna.employee_employment_status WHERE tenant_id=?", TENANT) != 0
                    || count(jdbc, "SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action LIKE 'PUBLIC_DEMO_PERSONNEL_FILL%'", TENANT) != 0) throw conflict();

            // Public HTTP/service writes keep their identity guard; this explicit CLI only inserts the fixed empty fixture.
            commands.executeBatch(actor, COMMAND, ACTION, "EMPLOYEE", prepared.hash(), REASON, () -> {
                List<BulkData.Change> changes = new ArrayList<>();
                for (var entry : prepared.entries()) {
                    var person = entry.person();
                    jdbc.update("""
                            INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,grade_name,position_name,
                                job_name,photo_content_type,photo_bytes,version,updated_by)
                            VALUES (?,?,?,?,?,?,?,?,1,?)
                            """, TENANT, person.id(), person.birthDate(), person.grade(), person.position(), person.job(),
                            entry.photo().contentType(), entry.photo().bytes(), ACCOUNT);
                    jdbc.update("""
                            INSERT INTO tna.employee_employment_status(tenant_id,id,employee_id,employment_status,
                                effective_from,revision,reason,created_by) VALUES (?,?,?,?,?,1,?,?)
                            """, TENANT, statusId(person.id()), person.id(), person.status(), prepared.today(), REASON, ACCOUNT);
                    changes.add(new BulkData.Change(person.id().toString(), person.id(), 1, null, summary(person, prepared.today())));
                }
                return changes;
            });
            verifyRegistered(jdbc, prepared);
            return !replay;
        });
    }

    public static void main(String[] args) {
        if (args.length != 3 || !CONFIRMATION.equals(args[0])) throw invalid();
        LocalDate expected;
        try { expected = LocalDate.parse(args[2]); }
        catch (RuntimeException malformed) { throw invalid(); }
        var source = configuration(System.getenv());
        try {
            boolean created = fill(source, Path.of(args[1]), expected, Clock.systemUTC());
            System.out.println(created ? "공개 합성 인사 4건·상태 4건·감사 5건을 등록했습니다."
                    : "기존 공개 합성 인사 등록이 정확히 일치합니다. 변경하지 않았습니다.");
        } catch (RuntimeException failure) {
            // Connection/SQL causes may contain deployment details; the CLI emits no credentials or private data.
            throw new IllegalStateException("공개 합성 인사 등록을 거부했습니다. 기존 자료와 고정 자산·날짜를 확인해 주세요.");
        }
    }

    private static Prepared prepare(Path directory, LocalDate expectedToday, Clock clock) {
        try {
            LocalDate today = LocalDate.now(clock.withZone(SEOUL));
            if (!REFERENCE_DATE.equals(today) || !today.equals(expectedToday)) throw invalid();
            Path root = directory.toRealPath();
            Path manifest = root.resolve("manifest.json");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.size(manifest) > 16384
                    || !Files.isDirectory(root.resolve("photos"), LinkOption.NOFOLLOW_LINKS)) throw invalid();
            var tree = JSON.readTree(Files.readString(manifest));
            var rows = tree.path("employees");
            if (tree.size() != 2 || !REFERENCE_DATE.toString().equals(tree.path("referenceDate").asText())
                    || !rows.isArray() || rows.size() != PEOPLE.size()) throw invalid();
            List<Entry> entries = new ArrayList<>();
            List<String> hashes = new ArrayList<>();
            for (int index = 0; index < PEOPLE.size(); index++) {
                var person = PEOPLE.get(index);
                var row = rows.get(index);
                if (row.size() != 11 || !person.id().toString().equals(row.path("employeeId").asText())
                        || !person.number().equals(row.path("employeeNo").asText())
                        || !person.birthDate().toString().equals(row.path("birthDate").asText())
                        || !person.grade().equals(row.path("gradeName").asText())
                        || !person.position().equals(row.path("positionName").asText())
                        || !person.job().equals(row.path("jobName").asText())
                        || !person.status().equals(row.path("employmentStatus").asText())
                        || !row.path("expectedVersion").isIntegralNumber() || row.path("expectedVersion").asLong() != 0
                        || !person.file().equals(row.path("photoFile").asText())
                        || person.birthDate().isAfter(today.minusYears(18))) throw invalid();
                OrgCommands.requiredText(person.grade(), 120, "직급");
                OrgCommands.requiredText(person.position(), 120, "직책");
                OrgCommands.requiredText(person.job(), 120, "직무");
                String sourceHash = row.path("sourceSha256").asText();
                String normalizedHash = row.path("normalizedSha256").asText();
                Path photoPath = root.resolve(person.file());
                if (!sourceHash.matches("[0-9a-f]{64}") || !normalizedHash.matches("[0-9a-f]{64}")
                        || !Files.isRegularFile(photoPath, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(photoPath) < 1 || Files.size(photoPath) > EmployeePhoto.MAX_BYTES) throw invalid();
                byte[] bytes = Files.readAllBytes(photoPath);
                if (!sourceHash.equals(sha(bytes))) throw invalid();
                var photo = EmployeePhoto.decode(Base64.getEncoder().encodeToString(bytes));
                if (!normalizedHash.equals(sha(photo.bytes()))) throw invalid();
                entries.add(new Entry(person, photo, normalizedHash));
                hashes.add(OrgCommands.fingerprint(person, sourceHash, normalizedHash));
            }
            return new Prepared(today, List.copyOf(entries), OrgCommands.fingerprint(ACTION, TENANT, today, hashes));
        } catch (Exception invalidAssets) { throw invalid(); }
    }

    private static void verifyDatabase(JdbcTemplate jdbc) {
        if (count(jdbc, """
                SELECT count(*) FROM pg_roles WHERE rolname=current_user AND current_user='tna_app'
                    AND session_user=current_user AND current_database()='worklog_demo'
                    AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb
                    AND NOT rolreplication AND NOT rolinherit
                """) != 1 || count(jdbc, """
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='tna' AND c.relname IN ('employee_personnel','employee_employment_status','org_audit_event')
                    AND c.relrowsecurity AND c.relforcerowsecurity
                """) != 3) throw conflict();
    }

    private static TnaPrincipal verifyIdentity(JdbcTemplate jdbc, Clock clock) {
        if (count(jdbc, """
                SELECT count(*) FROM platform.tenant_registry WHERE id=? AND company_code='TNA-LOCAL-DEMO'
                    AND display_name='로컬 합성 데모' AND active
                """, TENANT) != 1 || count(jdbc, "SELECT count(*) FROM tna.employee WHERE tenant_id=?", TENANT) != 4
                || count(jdbc, "SELECT count(*) FROM tna.organization WHERE tenant_id=?", TENANT) != 2
                || count(jdbc, "SELECT count(*) FROM tna.tenant_account WHERE tenant_id=?", TENANT) != 1) throw conflict();
        for (var person : PEOPLE) {
            if (count(jdbc, """
                    SELECT count(*) FROM tna.employee WHERE tenant_id=? AND id=? AND employee_no=? AND display_name=?
                        AND active AND joined_on<=? AND left_on IS NULL
                    """, TENANT, person.id(), person.number(), person.name(), REFERENCE_DATE) != 1) throw conflict();
        }
        if (count(jdbc, """
                SELECT count(*) FROM tna.organization WHERE tenant_id=? AND active AND parent_id IS NULL
                    AND ((id=? AND code='DEMO-OPS' AND name='가상 운영팀') OR (id=? AND code='DEMO-DEV' AND name='가상 개발팀'))
                """, TENANT, OPERATIONS, DEVELOPMENT) != 2 || count(jdbc, """
                SELECT count(*) FROM tna.employee_assignment a JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                WHERE a.tenant_id=? AND a.valid_from<=? AND (a.valid_to IS NULL OR a.valid_to>?)
                    AND ((e.employee_no IN ('LOCAL-DEMO','DEMO-001') AND a.organization_id=?)
                      OR (e.employee_no IN ('DEMO-002','DEMO-003') AND a.organization_id=?))
                """, TENANT, REFERENCE_DATE, REFERENCE_DATE, OPERATIONS, DEVELOPMENT) != 4) throw conflict();
        var versions = jdbc.queryForList("""
                SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=? AND employee_id=?
                    AND active AND credential_set AND email='admin@local.example.test'
                """, Long.class, TENANT, ACCOUNT, LocalDemoLogin.EMPLOYEE_ID);
        var roles = jdbc.queryForList("SELECT role FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND active ORDER BY role",
                String.class, TENANT, ACCOUNT);
        if (versions.size() != 1 || versions.getFirst() < 1 || !roles.equals(List.of("ATTENDANCE_ADMIN", "EMPLOYEE"))
                || count(jdbc, """
                SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='PUBLIC_DEMO_SAMPLE_BOOTSTRAP'
                    AND actor_account_id=? AND target_kind='TENANT' AND target_id=?
                    AND after_state @> '{"synthetic":true,"organizations":2,"employees":4}'::jsonb
                """, TENANT, ACCOUNT, TENANT) != 1) throw conflict();
        return new TnaPrincipal(TENANT, ACCOUNT, LocalDemoLogin.EMPLOYEE_ID, versions.getFirst(), Set.copyOf(roles), clock.instant());
    }

    private static void verifyRegistered(JdbcTemplate jdbc, Prepared prepared) {
        if (count(jdbc, "SELECT count(*) FROM tna.employee_personnel WHERE tenant_id=?", TENANT) != 4
                || count(jdbc, "SELECT count(*) FROM tna.employee_employment_status WHERE tenant_id=?", TENANT) != 4
                || count(jdbc, "SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action LIKE 'PUBLIC_DEMO_PERSONNEL_FILL%'", TENANT) != 5) throw conflict();
        var markers = jdbc.query("""
                SELECT target_id,after_state::text FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?
                    AND action=? AND actor_account_id=? AND target_kind='BATCH' AND input_hash=? AND reason=?
                    AND before_state='null'::jsonb AND request_trace_id=target_id
                """, (row, i) -> Map.entry(row.getObject(1, UUID.class), row.getString(2)), TENANT, COMMAND, ACTION, ACCOUNT, prepared.hash(), REASON);
        if (markers.size() != 1) throw conflict();
        var marker = markers.getFirst();
        var result = JSON.readValue(marker.getValue(), BulkData.Result.class);
        var expectedRows = PEOPLE.stream().map(person -> new BulkData.RowResult(person.id().toString(), person.id(), 1)).toList();
        if (!marker.getKey().equals(result.batchId()) || !expectedRows.equals(result.rows())) throw conflict();
        for (var entry : prepared.entries()) {
            var person = entry.person();
            if (count(jdbc, """
                    SELECT count(*) FROM tna.employee_personnel WHERE tenant_id=? AND employee_id=? AND birth_date=?
                        AND grade_name=? AND position_name=? AND job_name=? AND photo_content_type=?
                        AND encode(sha256(photo_bytes),'hex')=? AND version=1 AND updated_by=?
                    """, TENANT, person.id(), person.birthDate(), person.grade(), person.position(), person.job(),
                    entry.photo().contentType(), entry.normalizedHash(), ACCOUNT) != 1
                    || count(jdbc, """
                    SELECT count(*) FROM tna.employee_employment_status WHERE tenant_id=? AND id=? AND employee_id=?
                        AND employment_status=? AND effective_from=? AND revision=1 AND reason=? AND created_by=?
                    """, TENANT, statusId(person.id()), person.id(), person.status(), prepared.today(), REASON, ACCOUNT) != 1
                    || count(jdbc, """
                    SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action=? AND target_kind='EMPLOYEE'
                        AND target_id=? AND actor_account_id=? AND input_hash=? AND reason=? AND before_state='null'::jsonb
                        AND after_state=CAST(? AS jsonb) AND request_trace_id=?
                    """, TENANT, ACTION + "_ROW", person.id(), ACCOUNT, prepared.hash(), REASON,
                    JSON.writeValueAsString(summary(person, prepared.today())), marker.getKey()) != 1) throw conflict();
        }
    }

    private static Summary summary(Definition person, LocalDate today) { return new Summary(1, true, true, person.status(), today); }
    private static UUID statusId(UUID employee) { return UUID.nameUUIDFromBytes((ACTION + ":" + employee).getBytes(StandardCharsets.UTF_8)); }
    private static int count(JdbcTemplate jdbc, String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static String required(Map<String, String> environment, String name) {
        String value = environment == null ? null : environment.get(name);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Definition person(String id, String number, String name, String birth, String grade, String position,
            String job, String status, String file) {
        return new Definition(UUID.fromString(id), number, name, LocalDate.parse(birth), grade, position, job, status, "photos/" + file + ".jpg");
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("고정 공개 데모 인수·환경·사진 manifest·서울 기준일이 일치해야 합니다."); }
    private static IllegalStateException conflict() { return new IllegalStateException("예약된 공개 합성 자료와 일치하지 않아 변경하지 않았습니다."); }
    private record Definition(UUID id, String number, String name, LocalDate birthDate, String grade, String position,
            String job, String status, String file) { }
    private record Entry(Definition person, EmployeePhoto.Photo photo, String normalizedHash) { }
    private record Prepared(LocalDate today, List<Entry> entries, String hash) { }
    private record Summary(long version, boolean birthDateRegistered, boolean photoRegistered, String employmentStatus,
            LocalDate effectiveFrom) { }
}
