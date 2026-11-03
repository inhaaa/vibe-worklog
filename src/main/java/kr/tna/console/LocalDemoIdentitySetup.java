package kr.tna.console;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.org.EmployeeNumbers;
import kr.tna.org.OrgCommands;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Explicit, synthetic-only follow-up; never called by web requests or startup. */
public final class LocalDemoIdentitySetup {
    private static final UUID TENANT = LocalDemoLogin.TENANT_ID;
    private static final UUID ACTOR = LocalDemoLogin.ACCOUNT_ID;
    private static final UUID COMMAND = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddd0301");
    private static final String ACTION = "LOCAL_DEMO_IDENTITY_SETUP_20261103";
    private static final String REASON = "합성 데모 사번·테스트 계정 준비; 기존 UUID와 신청·결재·인사 이력 보존";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 3);
    private static final String CONFIRMATION = "worklog-demo-identities-20261103";
    private static final Pattern LOCAL = Pattern.compile("jdbc:postgresql://(?:127\\.0\\.0\\.1|\\[::1\\]):([0-9]{1,5})/(?:tna|worklog_demo)");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private LocalDemoIdentitySetup() { }

    public static DataSource configuration(Map<String, String> environment) {
        var profiles = Arrays.stream(required(environment, "SPRING_PROFILES_ACTIVE").split(",", -1)).map(String::strip).toList();
        boolean publicDemo = profiles.equals(List.of("public-demo"));
        if (!publicDemo && !(profiles.size() == 2 && Set.copyOf(profiles).equals(Set.of("local", "local-demo")))) throw invalid();
        String url = required(environment, "TNA_JDBC_URL");
        var local = LOCAL.matcher(url);
        if (!(publicDemo && url.equals("jdbc:postgresql://db:5432/worklog_demo"))
                && (!local.matches() || Integer.parseInt(local.group(1)) < 1 || Integer.parseInt(local.group(1)) > 65535)) throw invalid();
        return new DriverManagerDataSource(url, "tna_migration", required(environment, "TNA_MIGRATION_DB_PASSWORD"));
    }

    public static boolean setup(DataSource source, LocalDate expectedToday, Clock clock) throws SQLException {
        if (!DATE.equals(expectedToday) || !DATE.equals(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))) throw invalid();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                if (count(jdbc, """
                        SELECT count(*) FROM pg_roles r WHERE rolname=current_user AND current_user='tna_migration'
                          AND session_user=current_user AND current_database() IN ('tna','worklog_demo')
                          AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb
                          AND NOT rolreplication AND NOT rolinherit AND rolcanlogin AND current_setting('row_security')='on'
                          AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=r.oid)
                        """) != 1 || count(jdbc, """
                        SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                        WHERE (n.nspname,c.relname) IN (('platform','tenant_registry'),('tna','employee'),
                          ('tna','tenant_account'),('tna','role_grant'),('tna','org_audit_event'))
                          AND c.relrowsecurity AND c.relforcerowsecurity
                        """) != 5) throw conflict();
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))", Object.class, TENANT);
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, TENANT.toString());
                if (count(jdbc, "SELECT count(*) FROM platform.tenant_registry WHERE id=? AND company_code=? AND active",
                        TENANT, LocalDemoLogin.COMPANY_CODE) != 1
                        || count(jdbc, "SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='LOCAL_DEMO_BOOTSTRAP' AND actor_account_id=? AND target_id=?",
                        TENANT, ACTOR, TENANT) != 1) throw conflict();
                var original = employees(jdbc);
                var fixed = LocalDemoLogin.personas();
                if (!original.containsKey(LocalDemoLogin.EMPLOYEE_ID)) throw conflict();
                for (var persona : fixed) verifyAccount(jdbc, persona, persona.accountId().equals(ACTOR));
                var audits = jdbc.queryForList("SELECT id,command_id,input_hash,after_state::text AS state FROM tna.org_audit_event WHERE tenant_id=? AND (command_id=? OR action=?)",
                        TENANT, COMMAND, ACTION);
                if (!audits.isEmpty()) {
                    if (audits.size() != 1 || !COMMAND.equals(audits.getFirst().get("id"))
                            || !COMMAND.equals(audits.getFirst().get("command_id"))
                            || !fingerprint().equals(audits.getFirst().get("input_hash"))) throw conflict();
                    var recorded = JSON.readTree((String) audits.getFirst().get("state"));
                    for (var persona : fixed) {
                        var current = original.get(persona.employeeId());
                        if (current == null || !current.number().equals(recorded.path(persona.employeeId().toString()).asString())
                                || persona.accountId().equals(ACTOR) && !"담당자".equals(current.name())) throw conflict();
                        verifyAccount(jdbc, persona, true);
                    }
                    connection.commit();
                    return false;
                }
                var ids = fixed.stream().map(LocalDemoLogin.Persona::employeeId).collect(java.util.stream.Collectors.toSet());
                var reserved = new ArrayList<>(original.values().stream().filter(employee -> !ids.contains(employee.id())).map(Employee::number).toList());
                if (reserved.contains("99999")) throw conflict();
                reserved.add("99999");
                var numbers = new LinkedHashMap<String, String>();
                for (var persona : fixed) {
                    Employee old = original.get(persona.employeeId());
                    if (old != null && (!old.active() || old.joined().isAfter(DATE) || old.left() != null)) throw conflict();
                    if (old != null && !(old.number().equals("LOCAL-DEMO") || old.number().matches("DEMO-00[1-3]")
                            || old.number().matches("[0-9]{5}"))) throw conflict();
                    LocalDate joined = old != null ? old.joined() : persona.login().equals("dual")
                            ? LocalDate.of(2025, 3, 1) : LocalDate.of(2026, 1, 1);
                    String number = persona.accountId().equals(ACTOR) ? "99999" : EmployeeNumbers.next(joined, reserved);
                    reserved.add(number);
                    numbers.put(persona.employeeId().toString(), number);
                    if (old == null) {
                        jdbc.update("""
                                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,created_at,updated_at,created_by,updated_by)
                                VALUES (?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
                                """, TENANT, persona.employeeId(), number, name(persona), joined, ACTOR, ACTOR);
                    } else if (!old.number().equals(number) || persona.accountId().equals(ACTOR) && !old.name().equals("담당자")) {
                        if (jdbc.update("""
                                UPDATE tna.employee SET employee_no=?,display_name=?,version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=?
                                WHERE tenant_id=? AND id=? AND version=?
                                """, number, persona.accountId().equals(ACTOR) ? "담당자" : old.name(), ACTOR, TENANT, old.id(), old.version()) != 1) throw conflict();
                    }
                    if (!verifyAccount(jdbc, persona, false)) {
                        jdbc.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash,active,auth_version) VALUES (?,?,?,?,?,true,1)",
                                TENANT, persona.accountId(), persona.employeeId(), persona.email(), PasswordPolicy.passwordEncoder().encode(persona.password()));
                        for (String role : persona.roles()) jdbc.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", TENANT, persona.accountId(), role);
                    }
                }
                jdbc.update("""
                        INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                          reason,before_state,after_state,request_trace_id)
                        VALUES (?,?,?,?,?,'TENANT',?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
                        """, TENANT, COMMAND, COMMAND, ACTOR, ACTION, TENANT, fingerprint(), REASON,
                        JSON.writeValueAsString(original.values().stream().filter(employee -> ids.contains(employee.id())).toList()), JSON.writeValueAsString(numbers), COMMAND);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    private static Map<UUID, Employee> employees(JdbcTemplate jdbc) {
        var employees = new LinkedHashMap<UUID, Employee>();
        jdbc.query("SELECT id,employee_no,display_name,joined_on,left_on,active,version FROM tna.employee WHERE tenant_id=? ORDER BY id FOR UPDATE",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    UUID id = row.getObject("id", UUID.class);
                    employees.put(id, new Employee(id, row.getString("employee_no"), row.getString("display_name"),
                            row.getObject("joined_on", LocalDate.class), row.getObject("left_on", LocalDate.class), row.getBoolean("active"), row.getLong("version")));
                }, TENANT);
        return employees;
    }

    private static boolean verifyAccount(JdbcTemplate jdbc, LocalDemoLogin.Persona persona, boolean required) {
        var rows = jdbc.queryForList("SELECT id,employee_id,email,password_hash,active,credential_set FROM tna.tenant_account WHERE tenant_id=? AND (id=? OR employee_id=? OR email=?) FOR UPDATE",
                TENANT, persona.accountId(), persona.employeeId(), persona.email());
        if (rows.isEmpty() && !required) return false;
        if (rows.size() != 1) throw conflict();
        var row = rows.getFirst();
        boolean matches;
        try { matches = PasswordPolicy.passwordEncoder().matches(persona.password(), (String) row.get("password_hash")); }
        catch (IllegalArgumentException invalidHash) { throw conflict(); }
        if (!persona.accountId().equals(row.get("id")) || !persona.employeeId().equals(row.get("employee_id"))
                || !persona.email().equals(row.get("email")) || !Boolean.TRUE.equals(row.get("active"))
                || !Boolean.TRUE.equals(row.get("credential_set")) || !matches) throw conflict();
        var roles = jdbc.queryForList("SELECT role FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND active", String.class, TENANT, persona.accountId());
        if (!Set.copyOf(roles).equals(persona.roles())) throw conflict();
        return true;
    }

    private static String name(LocalDemoLogin.Persona persona) {
        return switch (persona.login()) {
            case "admin" -> "담당자";
            case "approver" -> "테스트 승인자";
            case "employee" -> "테스트 직원";
            case "representative" -> "테스트 대표";
            case "dual" -> "테스트 겸임자";
            default -> throw conflict();
        };
    }

    private static String fingerprint() {
        return OrgCommands.fingerprint(ACTION, TENANT, ACTOR, COMMAND, DATE, LocalDemoLogin.personas());
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static IllegalStateException conflict() { return new IllegalStateException("고정 합성 데모 자료와 일치하지 않아 변경하지 않았습니다."); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("명시한 합성 데모 준비 범위와 서울 기준 실행일을 확인해 주세요."); }
    private static String required(Map<String, String> environment, String name) {
        String value = environment == null ? null : environment.get(name);
        if (value == null || value.isBlank()) throw invalid();
        return value;
    }

    public static void main(String[] args) throws SQLException {
        if (args.length != 2 || !CONFIRMATION.equals(args[0])) throw invalid();
        boolean changed = setup(configuration(System.getenv()), LocalDate.parse(args[1]), Clock.systemUTC());
        System.out.println(changed ? "합성 데모 사번과 테스트 계정을 준비했습니다." : "합성 데모 사번과 테스트 계정이 이미 준비돼 있습니다.");
    }

    private record Employee(UUID id, String number, String name, LocalDate joined, LocalDate left, boolean active, long version) { }
}
