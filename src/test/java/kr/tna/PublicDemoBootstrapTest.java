package kr.tna;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.tna.console.LocalDemoBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class PublicDemoBootstrapTest {
    @Test
    void configurationUsesOnlyExplicitPublicDemoLoopbackDatabaseAndMigrationCredentials() {
        for (String url : List.of("jdbc:postgresql://127.0.0.1:5432/worklog_demo",
                "jdbc:postgresql://[::1]:6543/worklog_demo")) {
            var environment = environment();
            environment.put("TNA_JDBC_URL", url);
            environment.put("TNA_MIGRATION_DB_PASSWORD", "  synthetic-public-demo-password  ");
            var source = assertInstanceOf(DriverManagerDataSource.class,
                    LocalDemoBootstrap.publicConfiguration(environment));
            assertEquals(url, source.getUrl());
            assertEquals("tna_migration", source.getUsername());
            assertEquals("  synthetic-public-demo-password  ", source.getPassword());
        }
    }

    @Test
    void profileMustBePublicDemoAloneAndConfirmationMustMatchExactly() {
        for (String profiles : List.of("", "local", "local,local-demo", "public-demo,local",
                "public-demo,local-demo", "production,public-demo", "public-demo,public-demo")) {
            var environment = environment();
            environment.put("SPRING_PROFILES_ACTIVE", profiles);
            assertThrows(IllegalArgumentException.class,
                    () -> LocalDemoBootstrap.publicConfiguration(environment), profiles);
        }
        for (String confirmation : List.of("", "worklog", "WORKLOG-DEMO", " worklog-demo ")) {
            var environment = environment();
            environment.put("TNA_PUBLIC_DEMO_CONFIRM", confirmation);
            assertThrows(IllegalArgumentException.class,
                    () -> LocalDemoBootstrap.publicConfiguration(environment));
        }
    }

    @Test
    void otherDatabasesHostsPortsAndJdbcOptionsAreRejected() {
        for (String url : List.of("jdbc:postgresql://127.0.0.1:5432/tna",
                "jdbc:postgresql://127.0.0.1:5432/worklog_demo_copy",
                "jdbc:postgresql://localhost:5432/worklog_demo",
                "jdbc:postgresql://db:5432/worklog_demo",
                "jdbc:postgresql://198.18.0.1:5432/worklog_demo",
                "jdbc:postgresql://127.0.0.2:5432/worklog_demo",
                "jdbc:postgresql://127.0.0.1/worklog_demo",
                "jdbc:postgresql://127.0.0.1:0/worklog_demo",
                "jdbc:postgresql://127.0.0.1:65536/worklog_demo",
                "jdbc:postgresql://user@127.0.0.1:5432/worklog_demo",
                "jdbc:postgresql://127.0.0.1:5432/worklog_demo?user=postgres",
                "jdbc:postgresql://127.0.0.1:5432/worklog_demo#fragment",
                "jdbc:postgresql://127.0.0.1:5432/worklog_demo/other")) {
            var environment = environment();
            environment.put("TNA_JDBC_URL", url);
            assertThrows(IllegalArgumentException.class,
                    () -> LocalDemoBootstrap.publicConfiguration(environment), url);
        }
    }

    @Test
    void everyRequiredValueMustExistAndErrorsDoNotEchoSecrets() {
        for (String key : List.of("SPRING_PROFILES_ACTIVE", "TNA_PUBLIC_DEMO_CONFIRM",
                "TNA_JDBC_URL", "TNA_MIGRATION_DB_PASSWORD")) {
            var environment = environment();
            environment.remove(key);
            assertThrows(IllegalArgumentException.class,
                    () -> LocalDemoBootstrap.publicConfiguration(environment));
            environment.put(key, "  ");
            assertThrows(IllegalArgumentException.class,
                    () -> LocalDemoBootstrap.publicConfiguration(environment));
        }
        var environment = environment();
        environment.put("TNA_JDBC_URL", "jdbc:postgresql://secret-marker@127.0.0.1:5432/worklog_demo");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> LocalDemoBootstrap.publicConfiguration(environment));
        assertFalse(failure.toString().contains("secret-marker"));
        assertFalse(failure.toString().contains(environment.get("TNA_MIGRATION_DB_PASSWORD")));
    }

    @Test
    void publicCommandRejectsAdditionalArgumentsBeforeReadingEnvironment() {
        assertThrows(IllegalArgumentException.class,
                () -> LocalDemoBootstrap.publicMain(new String[] {"unexpected"}));
        assertThrows(IllegalArgumentException.class,
                () -> TnaApplication.main(new String[] {"public-demo-bootstrap", "unexpected"}));
    }

    private Map<String, String> environment() {
        return new HashMap<>(Map.of("SPRING_PROFILES_ACTIVE", "public-demo",
                "TNA_PUBLIC_DEMO_CONFIRM", "worklog-demo",
                "TNA_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/worklog_demo",
                "TNA_MIGRATION_DB_PASSWORD", "synthetic-migration-password"));
    }
}
