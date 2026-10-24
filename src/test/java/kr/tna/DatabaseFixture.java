package kr.tna;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Synthetic data in a dedicated local database; never targets an existing service. */
public final class DatabaseFixture {
    public static final String DATABASE_NAME = "tna_phase4_test";
    public static final String MIGRATION_USER = "tna_migration";
    public static final String MIGRATION_PASSWORD = "Synthetic-Migration-Only-43!";
    public static final String APP_USER = "tna_app";
    public static final String APP_PASSWORD = "Synthetic-App-Only-43!";
    public static final String AUTH_USER = "tna_auth";
    public static final String AUTH_PASSWORD = "Synthetic-Auth-Only-43!";
    public static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    public static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    public static final UUID EMPLOYEE_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
    public static final UUID EMPLOYEE_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1");
    public static final UUID ACCOUNT_A = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2");
    public static final UUID ACCOUNT_B = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb2");
    public static final String COMPANY_A = "TNA-ALPHA";
    public static final String COMPANY_B = "TNA-BETA";
    public static final String EMAIL = "employee@example.test";

    private static DatabaseFixture instance;
    private final String jdbcUrl;
    private final DataSource admin;
    private final DataSource migration;
    private final DataSource authentication;
    private final HikariDataSource application;

    private DatabaseFixture() throws SQLException {
        String suppliedUrl = System.getProperty("tna.test.jdbc-url", System.getenv("TNA_TEST_JDBC_URL"));
        PostgreSQLContainer container = null;
        String adminUser;
        String adminPassword;
        if (suppliedUrl == null || suppliedUrl.isBlank()) {
            container = new PostgreSQLContainer("postgres:18.6-bookworm")
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername("tna_test_admin")
                    .withPassword("Synthetic-Test-Admin-Only-43!");
            container.start();
            suppliedUrl = container.getJdbcUrl();
            adminUser = container.getUsername();
            adminPassword = container.getPassword();
        } else {
            adminUser = requiredEnvironment("TNA_TEST_DB_USER");
            adminPassword = requiredEnvironment("TNA_TEST_DB_PASSWORD");
        }
        validateLocalTestUrl(suppliedUrl);
        jdbcUrl = suppliedUrl;
        admin = dataSource(adminUser, adminPassword);
        prepareRoles();
        migration = dataSource(MIGRATION_USER, MIGRATION_PASSWORD);
        authentication = dataSource(AUTH_USER, AUTH_PASSWORD);
        Flyway.configure().dataSource(migration).locations("classpath:db/migration").load().migrate();
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(jdbcUrl);
        pool.setUsername(APP_USER);
        pool.setPassword(APP_PASSWORD);
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(0);
        pool.setPoolName("tenant-isolation-test");
        application = new HikariDataSource(pool);
        PostgreSQLContainer ownedContainer = container;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            application.close();
            if (ownedContainer != null) {
                ownedContainer.stop();
            }
        }));
    }

    public static synchronized DatabaseFixture start() {
        if (instance == null) {
            try {
                instance = new DatabaseFixture();
            } catch (SQLException exception) {
                throw new IllegalStateException("Dedicated local database initialization failed", exception);
            }
        }
        return instance;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public DataSource appDataSource() {
        return application;
    }

    public DataSource authDataSource() {
        return authentication;
    }

    public DataSource adminDataSource() {
        return admin;
    }

    public DataSource migrationDataSource() {
        return migration;
    }

    public void seedIdentity(String passwordHash) {
        JdbcTemplate jdbc = new JdbcTemplate(admin);
        seedTenant(jdbc, TENANT_A, EMPLOYEE_A, ACCOUNT_A, COMPANY_A, "직원 A", passwordHash);
        seedTenant(jdbc, TENANT_B, EMPLOYEE_B, ACCOUNT_B, COMPANY_B, "직원 B", passwordHash);
    }

    private void seedTenant(JdbcTemplate jdbc, UUID tenant, UUID employee, UUID account,
            String companyCode, String displayName, String passwordHash) {
        jdbc.update("""
                INSERT INTO platform.tenant_registry (id, company_code, display_name, active)
                VALUES (?, ?, ?, true)
                ON CONFLICT (id) DO UPDATE SET active = true
                """, tenant, companyCode, companyCode);
        jdbc.update("""
                INSERT INTO tna.employee (tenant_id, id, employee_no, display_name, joined_on, active)
                VALUES (?, ?, 'E001', ?, DATE '2026-01-01', true)
                ON CONFLICT (tenant_id, id) DO UPDATE SET active = true, left_on = NULL
                """, tenant, employee, displayName);
        jdbc.update("""
                INSERT INTO tna.tenant_account
                    (tenant_id, id, employee_id, email, password_hash, active, auth_version)
                VALUES (?, ?, ?, ?, ?, true, 1)
                ON CONFLICT (tenant_id, id) DO UPDATE
                    SET password_hash = excluded.password_hash, active = true, auth_version = 1
                """, tenant, account, employee, EMAIL, passwordHash);
        jdbc.update("DELETE FROM tna.role_grant WHERE tenant_id = ? AND account_id = ?", tenant, account);
        jdbc.update("INSERT INTO tna.role_grant (tenant_id, account_id, role) VALUES (?, ?, 'EMPLOYEE')",
                tenant, account);
    }

    private DataSource dataSource(String username, String password) {
        return new DriverManagerDataSource(jdbcUrl, username, password);
    }

    private void prepareRoles() throws SQLException {
        try (Connection connection = admin.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("REVOKE ALL ON DATABASE " + DATABASE_NAME + " FROM PUBLIC");
            createRole(connection, MIGRATION_USER, MIGRATION_PASSWORD);
            createRole(connection, APP_USER, APP_PASSWORD);
            createRole(connection, AUTH_USER, AUTH_PASSWORD);
            createRole(connection, "tna_operator", "synthetic-operator-db-password-only");
            statement.execute("GRANT CONNECT ON DATABASE " + DATABASE_NAME
                    + " TO tna_migration, tna_app, tna_auth, tna_operator");
            statement.execute("GRANT CREATE ON DATABASE " + DATABASE_NAME + " TO tna_migration");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO tna_migration");
        }
        verifyRoleLogin(MIGRATION_USER, MIGRATION_PASSWORD);
        verifyRoleLogin(APP_USER, APP_PASSWORD);
        verifyRoleLogin(AUTH_USER, AUTH_PASSWORD);
        verifyRoleLogin("tna_operator", "synthetic-operator-db-password-only");
    }

    private void createRole(Connection connection, String role, String password) throws SQLException {
        boolean exists;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT rolsuper, rolbypassrls, rolcreatedb, rolcreaterole, rolreplication, rolcanlogin, rolinherit
                FROM pg_roles WHERE rolname = ?
                """)) {
            query.setString(1, role);
            try (ResultSet result = query.executeQuery()) {
                exists = result.next();
                if (exists && (result.getBoolean(1) || result.getBoolean(2) || result.getBoolean(3)
                        || result.getBoolean(4) || result.getBoolean(5) || !result.getBoolean(6)
                        || result.getBoolean(7))) {
                    throw new IllegalStateException("Existing test role has unsafe flags: " + role);
                }
            }
        }
        if (exists) {
            try (PreparedStatement memberships = connection.prepareStatement("""
                    SELECT count(*) FROM pg_auth_members
                    WHERE member = (SELECT oid FROM pg_roles WHERE rolname = ?)
                    """)) {
                memberships.setString(1, role);
                try (ResultSet result = memberships.executeQuery()) {
                    result.next();
                    if (result.getInt(1) != 0) {
                        throw new IllegalStateException("Existing test role has unexpected memberships: " + role);
                    }
                }
            }
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                        + "NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD '" + password + "'");
            }
        }
    }

    private void verifyRoleLogin(String role, String password) throws SQLException {
        try (Connection ignored = DriverManager.getConnection(jdbcUrl, role, password)) {
            // Refuse an unrelated existing role instead of changing its password.
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required for explicit local test database: " + name);
        }
        return value;
    }

    static void validateLocalTestUrl(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("A dedicated local PostgreSQL JDBC URL is required");
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        if (!("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))
                || !("/" + DATABASE_NAME).equals(uri.getPath()) || uri.getUserInfo() != null
                || uri.getFragment() != null
                || (uri.getQuery() != null && !"loggerLevel=OFF".equals(uri.getQuery()))) {
            throw new IllegalArgumentException("Only localhost/127.0.0.1 with database " + DATABASE_NAME
                    + " is allowed");
        }
    }
}
