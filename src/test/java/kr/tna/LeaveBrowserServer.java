package kr.tna;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kr.tna.common.auth.PasswordPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Runs a leave workflow in its own loopback application and disposable database. */
final class LeaveBrowserServer {
    record Member(String employeeNo, String name, String email, UUID employeeId, UUID accountId) {
        Member(String no, String name) {
            this(no, name, no.toLowerCase(Locale.ROOT) + "@example.test", UUID.randomUUID(), UUID.randomUUID());
        }
    }

    interface Scenario {
        Clock clock(Path run);
        void seed(JdbcTemplate db, String passwordHash);
        Map<String, Object> prepare(ConfigurableApplicationContext app, JdbcTemplate db, Path run);
    }

    static void run(String[] args, String script, String ready, Scenario scenario) throws Exception {
        if (args.length < 2 || args.length > 3 || !Set.of("pc", "mobile", "--serve").contains(args[0])) {
            throw new IllegalArgumentException("Use <pc|mobile|--serve> <fresh-run-directory> [evidence-directory]");
        }
        if (System.getenv("TNA_TEST_JDBC_URL") != null || System.getProperty("tna.test.jdbc-url") != null) {
            throw new IllegalArgumentException("연차 검증 전용 PostgreSQL 컨테이너가 필요합니다.");
        }
        Path run = Path.of(args[1]).toAbsolutePath().normalize();
        Path manifest = run.resolve("fixture.json");
        if (Files.exists(manifest) || Files.exists(run.resolve("stop"))) throw new IllegalArgumentException("새 검증 경로를 지정해 주세요.");
        Files.createDirectories(run);
        var clock = scenario.clock(run);
        var database = DatabaseFixture.start();
        var db = new JdbcTemplate(database.adminDataSource());
        String password = "Annual-" + UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(database.adminDataSource())).executeWithoutResult(
                tx -> scenario.seed(db, PasswordPolicy.passwordEncoder().encode(password)));
        var application = new SpringApplication(TnaApplication.class);
        application.addInitializers(context -> {
            ((GenericApplicationContext) context).registerBean("leaveBrowserClock", Clock.class, () -> clock, bean -> bean.setPrimary(true));
            TestPropertyValues.of("spring.datasource.url=" + database.jdbcUrl(),
                    "spring.datasource.username=" + DatabaseFixture.APP_USER, "spring.datasource.password=" + DatabaseFixture.APP_PASSWORD,
                    "tna.auth.url=" + database.jdbcUrl(), "tna.auth.username=" + DatabaseFixture.AUTH_USER,
                    "tna.auth.password=" + DatabaseFixture.AUTH_PASSWORD).applyTo(context);
        });
        try (var app = application.run("--spring.config.location=classpath:/", "--spring.profiles.active=local",
                "--server.address=127.0.0.1", "--server.port=0", "--tna.operator.enabled=false")) {
            var configuration = new LinkedHashMap<>(scenario.prepare(app, db, run));
            configuration.put("baseUrl", "http://127.0.0.1:" + app.getEnvironment().getRequiredProperty("local.server.port"));
            configuration.put("password", password);
            Files.writeString(manifest, new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(configuration),
                    StandardOpenOption.CREATE_NEW);
            System.out.println(ready + " " + manifest);
            if ("--serve".equals(args[0])) {
                long deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(2);
                while (!Files.exists(run.resolve("stop")) && System.nanoTime() < deadline) Thread.sleep(1000);
            } else {
                Path evidence = args.length == 3 ? Path.of(args[2]).toAbsolutePath() : run.resolve("browser");
                var browser = new ProcessBuilder("node", script, manifest.toString(), args[0], evidence.toString()).inheritIO().start();
                if (browser.waitFor() != 0) throw new IllegalStateException("연차 브라우저 검증 실패");
            }
        }
    }
}
