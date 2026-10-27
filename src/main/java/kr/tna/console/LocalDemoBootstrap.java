package kr.tna.console;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.org.OrgCommands;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit synthetic setup; never called by automatic application startup. */
public final class LocalDemoBootstrap {
    private static final String ACTION = "LOCAL_DEMO_BOOTSTRAP";
    private static final Pattern LOCAL_URL = Pattern.compile(
            "jdbc:postgresql://(?:127\\.0\\.0\\.1|\\[::1\\]):([0-9]{1,5})/[A-Za-z0-9_-]+");

    private LocalDemoBootstrap() { }

    public static DataSource configuration(Map<String, String> environment) {
        var profiles = Arrays.stream(required(environment, "SPRING_PROFILES_ACTIVE").split(","))
                .map(String::strip).toList();
        if (!profiles.contains("local") || !profiles.contains("local-demo")) {
            throw new IllegalArgumentException("local과 local-demo 프로필을 모두 명시해 주세요.");
        }
        return dataSource(environment, false);
    }

    public static DataSource publicConfiguration(Map<String, String> environment) {
        var profiles = Arrays.stream(required(environment, "SPRING_PROFILES_ACTIVE").split(",", -1))
                .map(String::strip).toList();
        if (!profiles.equals(java.util.List.of("public-demo"))) {
            throw new IllegalArgumentException("public-demo 프로필만 명시해 주세요.");
        }
        if (!"worklog-demo".equals(required(environment, "TNA_PUBLIC_DEMO_CONFIRM"))) {
            throw new IllegalArgumentException("공개 합성 데모 준비 확인값이 일치하지 않습니다.");
        }
        return dataSource(environment, true);
    }

    private static DataSource dataSource(Map<String, String> environment, boolean publicDemo) {
        String url = required(environment, "TNA_JDBC_URL");
        String password = required(environment, "TNA_MIGRATION_DB_PASSWORD");
        var address = LOCAL_URL.matcher(url);
        if (!address.matches() || Integer.parseInt(address.group(1)) < 1
                || Integer.parseInt(address.group(1)) > 65535
                || publicDemo && !url.endsWith("/worklog_demo")) {
            throw new IllegalArgumentException("명시적인 loopback PostgreSQL 주소·포트·DB명만 지원합니다.");
        }
        return new DriverManagerDataSource(url, "tna_migration", password);
    }

    public static UUID bootstrap(DataSource source) throws SQLException {
        return bootstrap(source, false);
    }

    public static UUID bootstrapPublic(DataSource source) throws SQLException {
        return bootstrap(source, true);
    }

    private static UUID bootstrap(DataSource source, boolean publicDemo) throws SQLException {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement();
                        var role = statement.executeQuery("SELECT session_user='tna_migration'")) {
                    if (!role.next() || !role.getBoolean(1)) throw conflict();
                }
                try (var statement = connection.prepareStatement(
                        "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))")) {
                    statement.setString(1, LocalDemoLogin.TENANT_ID.toString());
                    statement.execute();
                }
                if (publicDemo) verifyPublicDatabase(connection);
                try (var statement = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                    statement.setString(1, LocalDemoLogin.TENANT_ID.toString());
                    statement.execute();
                }
                if (count(connection, "platform.tenant_registry", "id") == 0) {
                    seed(connection);
                } else {
                    verify(connection);
                }
                connection.commit();
                return LocalDemoLogin.TENANT_ID;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    private static void verifyPublicDatabase(Connection connection) throws SQLException {
        // Deployment preflight checks all tenants as the DB owner; this role retains tenant RLS.
        try (var statement = connection.createStatement();
                var row = statement.executeQuery("SELECT current_database()='worklog_demo'")) {
            if (!row.next() || !row.getBoolean(1)) throw conflict();
        }
    }

    private static void seed(Connection connection) throws SQLException {
        String hash = PasswordPolicy.passwordEncoder().encode(LocalDemoLogin.PASSWORD);
        update(connection, "INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'로컬 합성 데모')",
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.COMPANY_CODE);
        update(connection, """
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,created_at,updated_at)
                VALUES (?,?,'LOCAL-DEMO','로컬 데모 담당자',DATE '2026-01-01',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, LocalDemoLogin.TENANT_ID, LocalDemoLogin.EMPLOYEE_ID);
        update(connection, """
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash,active,auth_version)
                VALUES (?,?,?,?,?,true,1)
                """, LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID,
                LocalDemoLogin.EMPLOYEE_ID, LocalDemoLogin.EMAIL, hash);
        update(connection, "UPDATE tna.employee SET created_by=?,updated_by=? WHERE tenant_id=? AND id=?",
                LocalDemoLogin.ACCOUNT_ID, LocalDemoLogin.ACCOUNT_ID,
                LocalDemoLogin.TENANT_ID, LocalDemoLogin.EMPLOYEE_ID);
        for (String role : Set.of("EMPLOYEE", "ATTENDANCE_ADMIN")) {
            update(connection, "INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",
                    LocalDemoLogin.TENANT_ID, LocalDemoLogin.ACCOUNT_ID, role);
        }
        update(connection, """
                INSERT INTO tna.org_audit_event
                (tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                 reason,before_state,after_state,request_trace_id)
                VALUES (?,?,?,?,?,'TENANT',?,?,'명시적 로컬 합성 데모 자료 준비','{}'::jsonb,
                        jsonb_build_object('localDemo',true,'employeeId',CAST(? AS text),'accountId',CAST(? AS text)),?)
                """, LocalDemoLogin.TENANT_ID, UUID.randomUUID(), LocalDemoLogin.TENANT_ID,
                LocalDemoLogin.ACCOUNT_ID, ACTION, LocalDemoLogin.TENANT_ID, fingerprint(hash),
                LocalDemoLogin.EMPLOYEE_ID, LocalDemoLogin.ACCOUNT_ID, UUID.randomUUID());
    }

    private static void verify(Connection connection) throws SQLException {
        if (count(connection, "tna.employee", "tenant_id") != 1
                || count(connection, "tna.tenant_account", "tenant_id") != 1) throw conflict();
        String hash;
        try (var statement = connection.prepareStatement("""
                SELECT r.company_code,r.active,a.employee_id,a.email,a.password_hash,a.active,
                       e.active,e.joined_on,e.left_on
                FROM platform.tenant_registry r
                JOIN tna.tenant_account a ON a.tenant_id=r.id
                JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                WHERE r.id=? AND a.id=?
                """)) {
            statement.setObject(1, LocalDemoLogin.TENANT_ID);
            statement.setObject(2, LocalDemoLogin.ACCOUNT_ID);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw conflict();
                hash = row.getString(5);
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
                LocalDate left = row.getObject(9, LocalDate.class);
                if (!LocalDemoLogin.COMPANY_CODE.equals(row.getString(1)) || !row.getBoolean(2)
                        || !LocalDemoLogin.EMPLOYEE_ID.equals(row.getObject(3, UUID.class))
                        || !LocalDemoLogin.EMAIL.equals(row.getString(4)) || !row.getBoolean(6)
                        || !row.getBoolean(7) || row.getObject(8, LocalDate.class).isAfter(today)
                        || left != null && left.isBefore(today) || !matchesDemoPassword(hash)) throw conflict();
                if (row.next()) throw conflict();
            }
        }
        Set<String> roles = new HashSet<>();
        try (var statement = connection.prepareStatement(
                "SELECT role,active FROM tna.role_grant WHERE tenant_id=? AND account_id=?")) {
            statement.setObject(1, LocalDemoLogin.TENANT_ID);
            statement.setObject(2, LocalDemoLogin.ACCOUNT_ID);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!rows.getBoolean(2)) throw conflict();
                    roles.add(rows.getString(1));
                }
            }
        }
        if (!roles.equals(Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"))) throw conflict();
        try (var statement = connection.prepareStatement("""
                SELECT actor_account_id,target_kind,target_id,input_hash,command_id
                FROM tna.org_audit_event WHERE tenant_id=? AND action=?
                """)) {
            statement.setObject(1, LocalDemoLogin.TENANT_ID);
            statement.setString(2, ACTION);
            try (var row = statement.executeQuery()) {
                if (!row.next() || !LocalDemoLogin.ACCOUNT_ID.equals(row.getObject(1, UUID.class))
                        || !"TENANT".equals(row.getString(2))
                        || !LocalDemoLogin.TENANT_ID.equals(row.getObject(3, UUID.class))
                        || !fingerprint(hash).equals(row.getString(4))
                        || !LocalDemoLogin.TENANT_ID.equals(row.getObject(5, UUID.class)) || row.next()) throw conflict();
            }
        }
    }

    private static boolean matchesDemoPassword(String hash) {
        try { return hash != null && PasswordPolicy.passwordEncoder().matches(LocalDemoLogin.PASSWORD, hash); }
        catch (IllegalArgumentException failure) { return false; }
    }

    private static String fingerprint(String hash) {
        return OrgCommands.fingerprint(ACTION, LocalDemoLogin.TENANT_ID, LocalDemoLogin.EMPLOYEE_ID,
                LocalDemoLogin.ACCOUNT_ID, LocalDemoLogin.COMPANY_CODE, LocalDemoLogin.EMAIL, hash);
    }

    private static int count(Connection connection, String table, String key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + key + "=?")) {
            statement.setObject(1, LocalDemoLogin.TENANT_ID);
            try (var row = statement.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("예약한 로컬 데모 자료와 일치하지 않아 변경하지 않았습니다.");
    }

    public static void main(String[] args) throws SQLException {
        if (args.length != 0) {
            throw new IllegalArgumentException("local-demo-bootstrap 명령은 추가 인수를 허용하지 않습니다.");
        }
        UUID tenant = bootstrap(configuration(System.getenv()));
        System.out.println("로컬 합성 데모 자료를 확인했습니다: " + tenant);
    }

    public static void publicMain(String[] args) throws SQLException {
        if (args.length != 0) {
            throw new IllegalArgumentException("public-demo-bootstrap 명령은 추가 인수를 허용하지 않습니다.");
        }
        UUID tenant = bootstrapPublic(publicConfiguration(System.getenv()));
        System.out.println("공개 합성 데모 자료를 확인했습니다: " + tenant);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment == null ? null : environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("로컬 데모 실행에 필요한 환경값이 없습니다: " + name);
        }
        return value;
    }
}
