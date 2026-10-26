package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.tna.console.LocalDemoBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LocalDemoBootstrapTest {
    @Test
    void configurationIsLazyAndPreservesRawMigrationPassword() {
        for (String url : List.of("jdbc:postgresql://127.0.0.1:5432/demo_local",
                "jdbc:postgresql://[::1]:6543/demo-local")) {
            var environment = environment();
            environment.put("TNA_JDBC_URL", url);
            environment.put("TNA_MIGRATION_DB_PASSWORD", "  synthetic-local-password  ");
            var source = assertInstanceOf(DriverManagerDataSource.class,
                    LocalDemoBootstrap.configuration(environment));
            assertEquals(url, source.getUrl());
            assertEquals("tna_migration", source.getUsername());
            assertTrue("  synthetic-local-password  ".equals(source.getPassword()));
        }
    }

    @Test
    void bothLocalProfilesMustBeExplicitlyActive() {
        for (String profiles : List.of("", "local", "local-demo", "default", "LOCAL,local-demo")) {
            var environment = environment();
            environment.put("SPRING_PROFILES_ACTIVE", profiles);
            assertThrows(IllegalArgumentException.class,
                    () -> LocalDemoBootstrap.configuration(environment));
        }
        var environment = environment();
        environment.put("SPRING_PROFILES_ACTIVE", "local-demo, local");
        assertNotNull(LocalDemoBootstrap.configuration(environment));
    }

    @Test
    void onlyLiteralLoopbackWithExplicitPortAndPlainDatabaseNameIsAllowed() {
        for (String url : List.of("jdbc:postgresql://localhost:5432/demo",
                "jdbc:postgresql://198.18.0.1:5432/demo", "jdbc:postgresql://127.0.0.2:5432/demo",
                "jdbc:postgresql://127.0.0.1/demo", "jdbc:postgresql://127.0.0.1:0/demo",
                "jdbc:postgresql://127.0.0.1:65536/demo", "jdbc:postgresql://127.0.0.1:5432/",
                "jdbc:postgresql://user@127.0.0.1:5432/demo",
                "jdbc:postgresql://127.0.0.1:5432/demo?ssl=false",
                "jdbc:postgresql://127.0.0.1:5432/demo#fragment",
                "jdbc:postgresql://127.0.0.1:5432/demo/other",
                "jdbc:postgresql://127.0.0.1:5432/demo%2Fother",
                "jdbc:postgresql:demo", "not-a-jdbc-url")) {
            var environment = environment();
            environment.put("TNA_JDBC_URL", url);
            assertThrows(IllegalArgumentException.class,
                    () -> LocalDemoBootstrap.configuration(environment));
        }
    }

    @Test
    void missingOrBlankRequiredValuesAreRejectedWithoutEchoingSecrets() {
        for (String key : List.of("SPRING_PROFILES_ACTIVE", "TNA_JDBC_URL", "TNA_MIGRATION_DB_PASSWORD")) {
            var environment = environment();
            environment.remove(key);
            assertThrows(IllegalArgumentException.class,
                    () -> LocalDemoBootstrap.configuration(environment));
            environment.put(key, "  ");
            assertThrows(IllegalArgumentException.class,
                    () -> LocalDemoBootstrap.configuration(environment));
        }
        var environment = environment();
        environment.put("TNA_JDBC_URL", "jdbc:postgresql://secret-marker@127.0.0.1:5432/demo");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> LocalDemoBootstrap.configuration(environment));
        assertFalse(String.valueOf(failure.getMessage()).contains("secret-marker"));
        assertFalse(String.valueOf(failure.getMessage()).contains(environment.get("TNA_MIGRATION_DB_PASSWORD")));
    }

    @Test
    void commandRejectsAdditionalArgumentsBeforeReadingEnvironmentOrConnecting() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalDemoBootstrap.main(new String[] {"unexpected"}));
    }

    private Map<String, String> environment() {
        return new HashMap<>(Map.of("SPRING_PROFILES_ACTIVE", "local,local-demo",
                "TNA_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/demo_local",
                "TNA_MIGRATION_DB_PASSWORD", "synthetic-migration-password"));
    }
}
