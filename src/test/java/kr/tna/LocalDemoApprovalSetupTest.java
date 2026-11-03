package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import kr.tna.console.LocalDemoApprovalSetup;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LocalDemoApprovalSetupTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T15:00:00Z"), ZoneOffset.UTC);

    @Test
    void configurationIsLazyUsesMigrationAndPreservesTheRawPassword() {
        for (String profile : List.of("local,local-demo", "local-demo, local", "public-demo")) {
            var env = environment();
            env.put("SPRING_PROFILES_ACTIVE", profile);
            for (String host : List.of("127.0.0.1:5432", "[::1]:6543")) {
                env.put("TNA_JDBC_URL", "jdbc:postgresql://" + host + "/worklog_demo");
                var source = assertInstanceOf(DriverManagerDataSource.class, assertDoesNotThrow(() -> LocalDemoApprovalSetup.configuration(env)));
                assertEquals(env.get("TNA_JDBC_URL"), source.getUrl());
                assertEquals("tna_migration", source.getUsername());
                assertEquals("  synthetic-only  ", source.getPassword());
            }
        }
    }

    @Test
    void onlyTheSinglePublicProfileCanUseTheDedicatedComposeDatabaseHost() {
        var env = environment();
        env.put("TNA_JDBC_URL", "jdbc:postgresql://db:5432/worklog_demo");
        assertThrows(IllegalArgumentException.class, () -> LocalDemoApprovalSetup.configuration(env));
        env.put("SPRING_PROFILES_ACTIVE", "public-demo");
        assertNotNull(LocalDemoApprovalSetup.configuration(env));
        env.put("TNA_JDBC_URL", "jdbc:postgresql://db:5433/worklog_demo");
        assertThrows(IllegalArgumentException.class, () -> LocalDemoApprovalSetup.configuration(env));
    }

    @Test
    void extraMissingDuplicateAndMixedProfilesAreRejected() {
        for (String profile : List.of("", "local", "local-demo", "default", "public-demo,local",
                "local,local-demo,public-demo", "local,local-demo,extra", "public-demo,public-demo", "local,local-demo,")) {
            var env = environment();
            env.put("SPRING_PROFILES_ACTIVE", profile);
            assertThrows(IllegalArgumentException.class, () -> LocalDemoApprovalSetup.configuration(env));
        }
    }

    @Test
    void onlyTheExactDatabaseAndLiteralLoopbackWithoutJdbcPropertiesAreAllowed() {
        for (String url : List.of("jdbc:postgresql://localhost:5432/worklog_demo",
                "jdbc:postgresql://198.18.0.1:5432/worklog_demo", "jdbc:postgresql://127.0.0.2:5432/worklog_demo",
                "jdbc:postgresql://127.0.0.1/worklog_demo", "jdbc:postgresql://127.0.0.1:0/worklog_demo",
                "jdbc:postgresql://127.0.0.1:65536/worklog_demo", "jdbc:postgresql://127.0.0.1:5432/other",
                "jdbc:postgresql://user@127.0.0.1:5432/worklog_demo", "jdbc:postgresql://127.0.0.1:5432/worklog_demo?ssl=false",
                "jdbc:postgresql://127.0.0.1:5432/worklog_demo#fragment", "jdbc:postgresql:worklog_demo")) {
            var env = environment();
            env.put("TNA_JDBC_URL", url);
            assertThrows(IllegalArgumentException.class, () -> LocalDemoApprovalSetup.configuration(env));
        }
    }

    @Test
    void missingValuesAreRejectedWithoutEchoingSecrets() {
        for (String key : environment().keySet()) {
            var env = environment();
            env.remove(key);
            assertThrows(IllegalArgumentException.class, () -> LocalDemoApprovalSetup.configuration(env));
            env.put(key, " ");
            assertThrows(IllegalArgumentException.class, () -> LocalDemoApprovalSetup.configuration(env));
        }
        var env = environment();
        env.put("TNA_JDBC_URL", "jdbc:postgresql://secret-marker@127.0.0.1:5432/worklog_demo");
        var failure = assertThrows(IllegalArgumentException.class, () -> LocalDemoApprovalSetup.configuration(env));
        assertFalse(failure.getMessage().contains("secret-marker"));
        assertFalse(failure.getMessage().contains(env.get("TNA_MIGRATION_DB_PASSWORD")));
    }

    @Test
    void theFixedSeoulDayAndExplicitExpectedDateAreCheckedBeforeConnecting() {
        DataSource source = mock(DataSource.class);
        for (LocalDate date : List.of(TODAY.minusDays(1), TODAY.plusDays(1))) {
            assertThrows(IllegalArgumentException.class, () -> LocalDemoApprovalSetup.setup(source, date, CLOCK));
        }
        for (String instant : List.of("2026-11-02T14:59:59Z", "2026-11-03T15:00:00Z")) {
            Clock wrongDay = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
            assertThrows(IllegalArgumentException.class, () -> LocalDemoApprovalSetup.setup(source, TODAY, wrongDay));
        }
        verifyNoInteractions(source);
    }

    @Test
    void theCommandRejectsMissingOrWrongConsentAndDateBeforeReadingEnvironment() {
        for (String[] args : List.of(new String[0], new String[] {"worklog-demo-approval-20261103"},
                new String[] {"wrong", "2026-11-03"}, new String[] {"worklog-demo-approval-20261103", "2026-11-04"},
                new String[] {"worklog-demo-approval-20261103", "not-a-date"},
                new String[] {"worklog-demo-approval-20261103", "2026-11-03", "extra"})) {
            assertThrows(IllegalArgumentException.class, () -> LocalDemoApprovalSetup.main(args));
        }
    }

    private Map<String, String> environment() {
        return new HashMap<>(Map.of("SPRING_PROFILES_ACTIVE", "local,local-demo",
                "TNA_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/worklog_demo",
                "TNA_MIGRATION_DB_PASSWORD", "  synthetic-only  "));
    }
}
