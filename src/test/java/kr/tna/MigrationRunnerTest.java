package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.tna.common.config.MigrationRunner;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationRunnerTest {
    private static final String URL = "jdbc:postgresql://db:5432/tna";
    private static final String PASSWORD = "Synthetic-Migration-Only-43!";

    @Test
    void usesOnlyMigrationCredentialsAndClasspathScripts() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "TNA_JDBC_URL", URL, "TNA_MIGRATION_DB_PASSWORD", PASSWORD)) {
            @Override
            public String get(Object key) {
                if (!"TNA_JDBC_URL".equals(key) && !"TNA_MIGRATION_DB_PASSWORD".equals(key)) {
                    throw new AssertionError("Unrelated environment access: " + key);
                }
                return super.get(key);
            }
        };

        var configuration = MigrationRunner.configuration(environment);
        var dataSource = assertInstanceOf(DriverManagerDataSource.class, configuration.getDataSource());

        assertEquals(URL, dataSource.getUrl());
        assertEquals("tna_migration", dataSource.getUsername());
        assertEquals(PASSWORD, dataSource.getPassword());
        assertEquals(1, configuration.getLocations().length);
        assertEquals("classpath:db/migration", configuration.getLocations()[0].getDescriptor());
    }

    @Test
    void rejectsMissingOrBlankJdbcUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> MigrationRunner.configuration(Map.of("TNA_MIGRATION_DB_PASSWORD", PASSWORD)));
        for (String value : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class, () -> MigrationRunner.configuration(Map.of(
                    "TNA_JDBC_URL", value, "TNA_MIGRATION_DB_PASSWORD", PASSWORD)));
        }
    }

    @Test
    void refusesMissingMigrationPasswordEvenWhenOtherCredentialsExist() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "TNA_JDBC_URL", URL, "TNA_APP_DB_PASSWORD", PASSWORD,
                "TNA_AUTH_DB_PASSWORD", PASSWORD, "TNA_OPERATOR_DB_PASSWORD", PASSWORD));
        assertThrows(IllegalArgumentException.class, () -> MigrationRunner.configuration(environment));
        for (String value : List.of("", " ", "\t\n")) {
            environment.put("TNA_MIGRATION_DB_PASSWORD", value);
            assertThrows(IllegalArgumentException.class, () -> MigrationRunner.configuration(environment));
        }
    }

    @Test
    void rejectsJdbcCredentialOverridesAndOtherDatabaseDrivers() {
        for (String value : List.of(URL + "?user=tna_app", URL + "?password=other",
                "jdbc:postgresql://tna_app@db:5432/tna", URL + "#user=tna_app",
                "jdbc:h2:mem:tna", "jdbc:postgresql:tna")) {
            assertThrows(IllegalArgumentException.class, () -> MigrationRunner.configuration(Map.of(
                    "TNA_JDBC_URL", value, "TNA_MIGRATION_DB_PASSWORD", PASSWORD)));
        }
    }

    @Test
    void preservesPasswordWhitespaceAndIgnoresRequestedRoleOverrides() {
        var configuration = MigrationRunner.configuration(Map.of(
                "TNA_JDBC_URL", URL, "TNA_MIGRATION_DB_PASSWORD", " " + PASSWORD + " ",
                "TNA_MIGRATION_DB_USER", "postgres", "FLYWAY_USER", "postgres"));
        var dataSource = assertInstanceOf(DriverManagerDataSource.class, configuration.getDataSource());

        assertEquals(" " + PASSWORD + " ", dataSource.getPassword());
        assertEquals("tna_migration", dataSource.getUsername());
    }

    @Test
    void rejectsMalformedJdbcUrlsWithoutEchoingCredentials() {
        String marker = "Synthetic-Url-Secret-Marker-43";
        var failure = assertThrows(IllegalArgumentException.class,
                () -> MigrationRunner.configuration(Map.of("TNA_JDBC_URL",
                        URL + "?password=" + marker + " invalid", "TNA_MIGRATION_DB_PASSWORD", PASSWORD)));

        assertFalse(failure.getMessage().contains(marker));
        assertFalse(failure.toString().contains(marker));
        assertNull(failure.getCause());
    }
}
