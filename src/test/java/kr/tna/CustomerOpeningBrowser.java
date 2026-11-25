package kr.tna;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.PasswordPolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Starts an empty local installation and passes ephemeral credentials to the real-browser check. */
public final class CustomerOpeningBrowser {
    private static final String MAILPIT = "axllent/mailpit:v1.34.0";

    public static void main(String[] args) throws Exception {
        if (System.getenv("TNA_TEST_JDBC_URL") != null || System.getProperty("tna.test.jdbc-url") != null) {
            throw new IllegalArgumentException("This check requires its own empty database container.");
        }
        var db = DatabaseFixture.start();
        String operatorName = "opening-" + UUID.randomUUID();
        String password = "Operator-" + UUID.randomUUID();
        new JdbcTemplate(db.adminDataSource()).update(
                "INSERT INTO platform.operator_account(id,login_id,password_hash) VALUES (?,?,?)",
                UUID.randomUUID(), operatorName, PasswordPolicy.passwordEncoder().encode(password));

        try (var mailbox = new GenericContainer<>(DockerImageName.parse(MAILPIT))
                .withExposedPorts(1025, 8025)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPublishAllPorts(false)
                        .withPortBindings(loopback(1025), loopback(8025)))
                .waitingFor(Wait.forHttp("/readyz").forPort(8025))) {
            mailbox.start();
            int appPort;
            try (var port = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                appPort = port.getLocalPort();
            }
            String origin = "http://127.0.0.1:" + appPort;
            var application = new SpringApplication(TnaApplication.class);
            application.addInitializers(context -> TestPropertyValues.of(
                    "spring.datasource.url=" + db.jdbcUrl(), "spring.datasource.username=" + DatabaseFixture.APP_USER,
                    "spring.datasource.password=" + DatabaseFixture.APP_PASSWORD, "tna.auth.url=" + db.jdbcUrl(),
                    "tna.auth.username=" + DatabaseFixture.AUTH_USER, "tna.auth.password=" + DatabaseFixture.AUTH_PASSWORD,
                    "tna.operator.url=" + db.jdbcUrl(), "tna.operator.password=synthetic-operator-db-password-only",
                    "tna.mail.mode=smtp", "tna.mail.public-origin=" + origin, "tna.mail.from=worklog@example.test",
                    "spring.mail.host=127.0.0.1", "spring.mail.port=" + mailbox.getMappedPort(1025),
                    "spring.mail.properties[mail.smtp.starttls.enable]=false",
                    "spring.mail.properties[mail.smtp.starttls.required]=false").applyTo(context));
            try (var app = application.run("--spring.config.location=classpath:/",
                    "--spring.profiles.active=local", "--tna.operator.enabled=true",
                    "--server.address=127.0.0.1", "--server.port=" + appPort, "--tna.daily-batch.enabled=false")) {
                var browser = new ProcessBuilder("node", "scripts/verify-customer-opening-browser.cjs")
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
                try (var input = browser.getOutputStream()) {
                    input.write(new JsonMapper().writeValueAsBytes(Map.of(
                            "origin", origin, "mailbox", "http://127.0.0.1:" + mailbox.getMappedPort(8025),
                            "companyDate", LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),
                            "operator", operatorName, "operatorPassword", password)));
                }
                if (browser.waitFor() != 0) throw new IllegalStateException("Customer opening browser check failed");
            }
        }
    }

    private static PortBinding loopback(int port) {
        return new PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(port));
    }
}
