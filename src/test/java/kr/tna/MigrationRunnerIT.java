package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import kr.tna.common.config.MigrationRunner;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationRunnerIT {
    private static DatabaseFixture database;

    @BeforeAll
    static void database() {
        database = DatabaseFixture.start();
    }

    @Test
    void validatesAllClasspathMigrationsWithOnlyTheMigrationCredentials() throws Exception {
        var environment = Map.of("TNA_JDBC_URL", migrationUrl(),
                "TNA_MIGRATION_DB_PASSWORD", DatabaseFixture.MIGRATION_PASSWORD);
        var result = MigrationRunner.migrate(environment);

        assertTrue(result.success);
        assertEquals(0, result.migrationsExecuted);
        JdbcTemplate migration = new JdbcTemplate(database.migrationDataSource());
        assertEquals(46, migration.queryForObject(
                "SELECT count(*) FROM public.flyway_schema_history WHERE success", Integer.class));
        assertEquals(1, migration.queryForObject("""
                SELECT count(*) FROM public.flyway_schema_history
                WHERE version = '46' AND installed_by = 'tna_migration' AND success
                """, Integer.class));
        try (var connection = MigrationRunner.configuration(environment).getDataSource().getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT current_user")) {
            assertTrue(rows.next());
            assertEquals("tna_migration", rows.getString(1));
        }
    }

    @Test
    void neverFallsBackToApplicationAuthenticationOrOperatorCredentials() {
        var environment = Map.of("TNA_JDBC_URL", migrationUrl(),
                "TNA_APP_DB_PASSWORD", DatabaseFixture.APP_PASSWORD,
                "TNA_AUTH_DB_PASSWORD", DatabaseFixture.AUTH_PASSWORD,
                "TNA_OPERATOR_DB_PASSWORD", "synthetic-operator-db-password-only");

        assertThrows(IllegalArgumentException.class, () -> MigrationRunner.migrate(environment));
    }

    @Test
    void roleOverridesCannotAuthenticateUsingTheApplicationPassword() {
        var environment = Map.of("TNA_JDBC_URL", migrationUrl(),
                "TNA_MIGRATION_DB_PASSWORD", DatabaseFixture.APP_PASSWORD,
                "TNA_MIGRATION_DB_USER", DatabaseFixture.APP_USER,
                "FLYWAY_USER", DatabaseFixture.APP_USER);

        assertThrows(FlywayException.class, () -> MigrationRunner.migrate(environment));
    }

    private static String migrationUrl() {
        String url = database.jdbcUrl();
        return url.endsWith("?loggerLevel=OFF") ? url.substring(0, url.indexOf('?')) : url;
    }
}
