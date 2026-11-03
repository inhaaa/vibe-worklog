package kr.tna.console;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.org.OrgCommands;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Fixed synthetic follow-up only; never invoked during application startup or by HTTP. */
public final class LocalDemoApprovalSetup {
    private static final String ACTION = "LOCAL_DEMO_APPROVAL_SETUP_20261103";
    private static final String CONFIRMATION = "worklog-demo-approval-20261103";
    private static final String REASON = "명시적 합성 데모 독립 승인자 준비; 기존 신청·일정·연차·인사자료 보존";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 3);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final UUID TENANT = LocalDemoLogin.TENANT_ID;
    private static final UUID ADMIN = LocalDemoLogin.ACCOUNT_ID;
    private static final UUID EMPLOYEE = LocalDemoLogin.EMPLOYEE_ID;
    private static final UUID APPROVER = LocalDemoLogin.APPROVER_EMPLOYEE_ID;
    private static final UUID ACCOUNT = LocalDemoLogin.APPROVER_ACCOUNT_ID;
    private static final UUID ASSIGNMENT = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0221");
    private static final UUID COMMAND = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0222");
    private static final UUID AUDIT = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0223");
    private static final Pattern LOOPBACK = Pattern.compile("jdbc:postgresql://(?:127\\.0\\.0\\.1|\\[::1\\]):([0-9]{1,5})/worklog_demo");

    private LocalDemoApprovalSetup() { }

    public static DataSource configuration(Map<String, String> environment) {
        var profiles = Arrays.stream(required(environment, "SPRING_PROFILES_ACTIVE").split(",", -1)).map(String::strip).toList();
        boolean publicDemo = profiles.equals(List.of("public-demo"));
        if (!publicDemo && !(profiles.size() == 2 && Set.copyOf(profiles).equals(Set.of("local", "local-demo")))) throw invalid();
        String url = required(environment, "TNA_JDBC_URL");
        var local = LOOPBACK.matcher(url);
        if (!(publicDemo && url.equals("jdbc:postgresql://db:5432/worklog_demo"))
                && (!local.matches() || Integer.parseInt(local.group(1)) < 1 || Integer.parseInt(local.group(1)) > 65535)) throw invalid();
        return new DriverManagerDataSource(url, "tna_migration", required(environment, "TNA_MIGRATION_DB_PASSWORD"));
    }

    public static boolean setup(DataSource source, LocalDate expectedToday, Clock clock) throws SQLException {
        checkDate(expectedToday, clock);
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                verifyDatabase(jdbc);
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))", Object.class, TENANT);
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, TENANT.toString());
                var admin = verifyAdmin(jdbc);
                long approverVersion = verifyApproverEmployee(jdbc);
                verifyRoles(jdbc, ADMIN, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
                verifyBootstrap(jdbc, admin.hash());
                var audits = jdbc.queryForList("""
                        SELECT id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                               before_state->>'employeeVersion' AS before_version
                        FROM tna.org_audit_event WHERE tenant_id=? AND (id=? OR command_id=? OR action=?)
                        """, TENANT, AUDIT, COMMAND, ACTION);
                boolean created = audits.isEmpty();
                if (created) {
                    if (accountCollisions(jdbc) != 0 || count(jdbc, "SELECT count(*) FROM tna.approver_assignment WHERE tenant_id=?", TENANT) != 0
                            || count(jdbc, "SELECT count(*) FROM tna.approval_route_assignment WHERE tenant_id=?", TENANT) != 0) throw conflict();
                    String hash = PasswordPolicy.passwordEncoder().encode(LocalDemoLogin.APPROVER_PASSWORD);
                    create(jdbc, admin, approverVersion, hash);
                } else {
                    if (audits.size() != 1) throw conflict();
                    verifyReplay(jdbc, audits.getFirst(), admin, approverVersion);
                }
                connection.commit();
                return created;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); }
                if (failure instanceof DataAccessException database) throw new SQLException("합성 데모 준비 DB 작업을 완료하지 못했습니다.", database);
                throw failure;
            }
        }
    }

    private static void verifyDatabase(JdbcTemplate jdbc) {
        if (count(jdbc, """
                SELECT count(*) FROM pg_roles r WHERE rolname=current_user AND current_user='tna_migration'
                    AND session_user=current_user AND current_database()='worklog_demo'
                    AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb
                    AND NOT rolreplication AND NOT rolinherit AND rolcanlogin
                    AND current_setting('row_security')='on'
                    AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=r.oid)
                """) != 1 || count(jdbc, """
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE (n.nspname,c.relname) IN (('platform','tenant_registry'),('tna','employee'),
                    ('tna','tenant_account'),('tna','role_grant'),('tna','approver_assignment'),
                    ('tna','approval_route_assignment'),('tna','employee_employment_status'),('tna','org_audit_event'))
                    AND c.relrowsecurity AND c.relforcerowsecurity
                """) != 8) throw conflict();
    }

    private static Identity verifyAdmin(JdbcTemplate jdbc) {
        var rows = jdbc.query("""
                SELECT a.password_hash,e.version,a.auth_version FROM platform.tenant_registry r
                JOIN tna.tenant_account a ON a.tenant_id=r.id
                JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                WHERE r.id=? AND r.company_code=? AND r.active AND a.id=? AND a.employee_id=?
                    AND a.email=? AND a.active AND a.credential_set AND a.auth_version>=1
                    AND e.employee_no='LOCAL-DEMO' AND e.active AND e.joined_on<=?
                    AND (e.left_on IS NULL OR e.left_on>=?) FOR UPDATE OF r,a,e
                """, (row, i) -> new Identity(row.getString(1), row.getLong(2), row.getLong(3)),
                TENANT, LocalDemoLogin.COMPANY_CODE, ADMIN, EMPLOYEE, LocalDemoLogin.EMAIL, DATE, DATE);
        if (rows.size() != 1 || !matches(LocalDemoLogin.PASSWORD, rows.getFirst().hash())) throw conflict();
        return rows.getFirst();
    }

    private static long verifyApproverEmployee(JdbcTemplate jdbc) {
        var rows = jdbc.queryForList("""
                SELECT version FROM tna.employee WHERE tenant_id=? AND id=? AND employee_no='DEMO-001'
                    AND active AND joined_on<=? AND (left_on IS NULL OR left_on>=?) FOR UPDATE
                """, Long.class, TENANT, APPROVER, DATE, DATE);
        var statuses = jdbc.queryForList("""
                SELECT employment_status FROM tna.employee_employment_status WHERE tenant_id=? AND employee_id=?
                    AND effective_from<=? ORDER BY effective_from DESC,revision DESC LIMIT 1
                """, String.class, TENANT, APPROVER, DATE);
        if (rows.size() != 1 || !statuses.equals(List.of("WORKING"))) throw conflict();
        return rows.getFirst();
    }

    private static void verifyRoles(JdbcTemplate jdbc, UUID account, Set<String> expected) {
        var rows = jdbc.query("SELECT role,active FROM tna.role_grant WHERE tenant_id=? AND account_id=? FOR UPDATE",
                (row, i) -> Map.entry(row.getString(1), row.getBoolean(2)), TENANT, account);
        if (rows.size() != expected.size() || rows.stream().anyMatch(row -> !row.getValue())
                || !Set.copyOf(rows.stream().map(Map.Entry::getKey).toList()).equals(expected)) throw conflict();
    }

    private static void verifyBootstrap(JdbcTemplate jdbc, String hash) {
        String fingerprint = OrgCommands.fingerprint("LOCAL_DEMO_BOOTSTRAP", TENANT, EMPLOYEE, ADMIN,
                LocalDemoLogin.COMPANY_CODE, LocalDemoLogin.EMAIL, hash);
        if (count(jdbc, "SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='LOCAL_DEMO_BOOTSTRAP'", TENANT) != 1
                || count(jdbc, """
                SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='LOCAL_DEMO_BOOTSTRAP'
                    AND command_id=? AND actor_account_id=? AND target_kind='TENANT' AND target_id=? AND input_hash=?
                """, TENANT, TENANT, ADMIN, TENANT, fingerprint) != 1) throw conflict();
    }

    private static int accountCollisions(JdbcTemplate jdbc) {
        return count(jdbc, "SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND (id=? OR employee_id=? OR email=?)",
                TENANT, ACCOUNT, APPROVER, LocalDemoLogin.APPROVER_EMAIL);
    }

    private static void create(JdbcTemplate jdbc, Identity admin, long approverVersion, String hash) {
        long nextVersion = Math.addExact(admin.employeeVersion(), 1);
        jdbc.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash,active,auth_version)
                VALUES (?,?,?,?,?,true,1)
                """, TENANT, ACCOUNT, APPROVER, LocalDemoLogin.APPROVER_EMAIL, hash);
        for (String role : List.of("EMPLOYEE", "APPROVER")) jdbc.update(
                "INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", TENANT, ACCOUNT, role);
        jdbc.update("""
                INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, TENANT, ASSIGNMENT, EMPLOYEE, APPROVER, DATE, REASON, ADMIN, ADMIN);
        if (jdbc.update("""
                UPDATE tna.employee SET version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=?
                WHERE tenant_id=? AND id=? AND version=?
                """, ADMIN, TENANT, EMPLOYEE, admin.employeeVersion()) != 1) throw conflict();
        jdbc.update("""
                INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                    reason,before_state,after_state,request_trace_id)
                VALUES (?,?,?,?,?,'EMPLOYEE',?,?,?,
                    jsonb_build_object('employeeVersion',CAST(? AS bigint),'setupDate',CAST(? AS text)),
                    jsonb_build_object('employeeVersion',CAST(? AS bigint),'approverEmployeeId',CAST(? AS text),
                        'approverAccountId',CAST(? AS text),'assignmentId',CAST(? AS text),'setupDate',CAST(? AS text)),?)
                """, TENANT, AUDIT, COMMAND, ADMIN, ACTION, EMPLOYEE, fingerprint(admin, approverVersion, hash, admin.employeeVersion()),
                REASON, admin.employeeVersion(), DATE.toString(), nextVersion, APPROVER.toString(), ACCOUNT.toString(), ASSIGNMENT.toString(), DATE.toString(), COMMAND);
    }

    private static void verifyReplay(JdbcTemplate jdbc, Map<String, Object> audit, Identity admin, long approverVersion) {
        long before;
        try { before = Long.parseLong((String) audit.get("before_version")); }
        catch (RuntimeException invalid) { throw conflict(); }
        if (before < 0 || before == Long.MAX_VALUE || admin.employeeVersion() != before + 1
                || !AUDIT.equals(audit.get("id")) || !COMMAND.equals(audit.get("command_id"))
                || !ADMIN.equals(audit.get("actor_account_id")) || !ACTION.equals(audit.get("action"))
                || !"EMPLOYEE".equals(audit.get("target_kind")) || !EMPLOYEE.equals(audit.get("target_id"))
                || accountCollisions(jdbc) != 1) throw conflict();
        var hashes = jdbc.queryForList("""
                SELECT password_hash FROM tna.tenant_account WHERE tenant_id=? AND id=? AND employee_id=?
                    AND email=? AND active AND credential_set AND auth_version=1 FOR UPDATE
                """, String.class, TENANT, ACCOUNT, APPROVER, LocalDemoLogin.APPROVER_EMAIL);
        if (hashes.size() != 1 || !matches(LocalDemoLogin.APPROVER_PASSWORD, hashes.getFirst())
                || !fingerprint(admin, approverVersion, hashes.getFirst(), before).equals(audit.get("input_hash"))) throw conflict();
        verifyRoles(jdbc, ACCOUNT, Set.of("EMPLOYEE", "APPROVER"));
        if (count(jdbc, "SELECT count(*) FROM tna.approver_assignment WHERE tenant_id=?", TENANT) != 1
                || count(jdbc, "SELECT count(*) FROM tna.approval_route_assignment WHERE tenant_id=?", TENANT) != 0
                || count(jdbc, """
                SELECT count(*) FROM tna.approver_assignment WHERE tenant_id=? AND id=? AND employee_id=? AND approver_employee_id=?
                    AND valid_from=? AND valid_to IS NULL AND superseded_at IS NULL AND version=0 AND reason=?
                    AND created_by=? AND updated_by=? AND updated_at=created_at
                """, TENANT, ASSIGNMENT, EMPLOYEE, APPROVER, DATE, REASON, ADMIN, ADMIN) != 1
                || count(jdbc, """
                SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND id=? AND reason=? AND request_trace_id=?
                    AND before_state=jsonb_build_object('employeeVersion',CAST(? AS bigint),'setupDate',CAST(? AS text))
                    AND after_state=jsonb_build_object('employeeVersion',CAST(? AS bigint),'approverEmployeeId',CAST(? AS text),
                        'approverAccountId',CAST(? AS text),'assignmentId',CAST(? AS text),'setupDate',CAST(? AS text))
                """, TENANT, AUDIT, REASON, COMMAND, before, DATE.toString(), before + 1, APPROVER.toString(), ACCOUNT.toString(), ASSIGNMENT.toString(), DATE.toString()) != 1) throw conflict();
    }

    private static String fingerprint(Identity admin, long approverVersion, String approverHash, long beforeVersion) {
        return OrgCommands.fingerprint(ACTION, TENANT, ADMIN, EMPLOYEE, APPROVER, ACCOUNT, ASSIGNMENT, COMMAND, AUDIT,
                DATE, beforeVersion, admin.authVersion(), approverVersion, admin.hash(), approverHash,
                LocalDemoLogin.EMAIL, LocalDemoLogin.APPROVER_EMAIL, "EMPLOYEE,APPROVER", REASON);
    }

    private static boolean matches(String password, String hash) {
        try { return hash != null && PasswordPolicy.passwordEncoder().matches(password, hash); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... values) { return jdbc.queryForObject(sql, Integer.class, values); }
    private static String required(Map<String, String> environment, String key) {
        String value = environment == null ? null : environment.get(key);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }
    private static void checkDate(LocalDate expected, Clock clock) {
        if (!DATE.equals(expected) || clock == null || !DATE.equals(LocalDate.now(clock.withZone(SEOUL)))) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("고정 합성 승인자 준비의 확인값·서울 날짜·프로필·전용 DB 주소를 확인해 주세요."); }
    private static IllegalStateException conflict() { return new IllegalStateException("예약한 합성 데모 계정·사원·배정·감사 근거와 일치하지 않아 변경하지 않았습니다."); }
    private record Identity(String hash, long employeeVersion, long authVersion) { }

    public static void main(String[] args) throws SQLException {
        if (args.length != 2 || !CONFIRMATION.equals(args[0])) throw invalid();
        LocalDate expected;
        try { expected = LocalDate.parse(args[1]); }
        catch (RuntimeException malformed) { throw invalid(); }
        checkDate(expected, Clock.systemUTC());
        var source = configuration(System.getenv());
        try {
            boolean created = setup(source, expected, Clock.systemUTC());
            System.out.println(created ? "합성 데모 승인자 계정·역할·배정·감사 5행을 준비했습니다."
                    : "기존 합성 데모 승인자 준비가 정확히 일치합니다. 변경하지 않았습니다.");
        } catch (SQLException | RuntimeException failure) {
            throw new IllegalStateException("합성 데모 승인자 준비를 거부했습니다. 기존 자료·역할·감사 근거를 확인해 주세요.");
        }
    }
}
