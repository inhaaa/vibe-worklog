package kr.tna;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.PasswordPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Two consecutive Seoul business dates in one real web app and one disposable PostgreSQL database. */
public final class AttendanceMonthCycleBrowser {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || !Set.of("pc", "mobile", "--serve").contains(args[0])
                || "--serve".equals(args[0]) && args.length != 2) {
            throw new IllegalArgumentException("Usage: AttendanceMonthCycleBrowser <pc|mobile|--serve> <fresh-run-directory> [browser-output]");
        }
        if (System.getenv("TNA_TEST_JDBC_URL") != null || System.getProperty("tna.test.jdbc-url") != null) {
            throw new IllegalArgumentException("The browser check requires a fixture-owned database.");
        }
        Path run = Path.of(args[1]).toAbsolutePath().normalize();
        try (var app = start(DatabaseFixture.start(), run, "CYCLE")) {
            Path metadata = run.resolve("fixture.json");
            System.out.println("MONTH_CYCLE_BROWSER_READY " + metadata);
            if ("--serve".equals(args[0])) {
                long limit = System.nanoTime() + TimeUnit.HOURS.toNanos(2);
                while (!Files.exists(run.resolve("stop")) && System.nanoTime() < limit) Thread.sleep(1000);
            } else {
                Path output = args.length == 3 ? Path.of(args[2]).toAbsolutePath() : run.resolve("browser");
                var browser = new ProcessBuilder("node", "scripts/verify-attendance-month-cycle.cjs", metadata.toString(),
                        args[0], output.toString()).inheritIO().start();
                if (browser.waitFor() != 0) throw new IllegalStateException("월마감 브라우저 검증 실패");
            }
        }
    }

    static ConfigurableApplicationContext start(DatabaseFixture database, Path directory, String companyCode) throws Exception {
        Path run = directory.toAbsolutePath().normalize();
        Files.createDirectories(run);
        Path advance = run.resolve("next-month");
        Path manifest = run.resolve("fixture.json");
        if (Files.exists(advance) || Files.exists(manifest) || Files.exists(run.resolve("stop"))) {
            throw new IllegalArgumentException("Choose a fresh run directory for this month cycle.");
        }
        InstantSource timeline = () -> Files.exists(advance) ? AttendanceMonthCycleData.NEXT_MONTH : AttendanceMonthCycleData.MONTH_END;
        var clock = timeline.withZone(ZoneOffset.UTC);
        var application = new SpringApplication(TnaApplication.class);
        application.addInitializers(context -> {
            ((GenericApplicationContext) context).registerBean("monthCycleClock", java.time.Clock.class,
                    () -> clock, definition -> definition.setPrimary(true));
            TestPropertyValues.of("spring.datasource.url=" + database.jdbcUrl(),
                    "spring.datasource.username=" + DatabaseFixture.APP_USER, "spring.datasource.password=" + DatabaseFixture.APP_PASSWORD,
                    "tna.auth.url=" + database.jdbcUrl(), "tna.auth.username=" + DatabaseFixture.AUTH_USER,
                    "tna.auth.password=" + DatabaseFixture.AUTH_PASSWORD).applyTo(context);
        });
        String password = "Cycle-" + UUID.randomUUID();
        var data = new AttendanceMonthCycleData(new JdbcTemplate(database.adminDataSource()));
        var referenceData = new TransactionTemplate(new DataSourceTransactionManager(database.adminDataSource()));
        referenceData.executeWithoutResult(tx -> data.seed(companyCode, PasswordPolicy.passwordEncoder().encode(password)));
        var app = application.run("--spring.config.location=classpath:/", "--spring.profiles.active=local",
                "--server.address=127.0.0.1", "--server.port=0", "--tna.operator.enabled=false", "--tna.daily-batch.enabled=false");
        try {
            data.confirmComparisonDay(app.getBean(ClockService.class));
            String baseUrl = "http://127.0.0.1:" + app.getEnvironment().getRequiredProperty("local.server.port");
            var fixture = data.metadata(baseUrl, companyCode, password, advance);
            Files.writeString(manifest, new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(fixture),
                    StandardOpenOption.CREATE_NEW);
            return app;
        } catch (Exception failure) {
            app.close();
            throw failure;
        }
    }
}
