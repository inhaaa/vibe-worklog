package kr.tna;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import kr.tna.console.PublicDemoPersonnelFill;
import kr.tna.org.EmployeePhoto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class PublicDemoPersonnelFillTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    static final List<String> IDS = List.of("dddddddd-dddd-4ddd-8ddd-ddddddddddd1",
            "dddddddd-dddd-4ddd-8ddd-dddddddd0201", "dddddddd-dddd-4ddd-8ddd-dddddddd0202",
            "dddddddd-dddd-4ddd-8ddd-dddddddd0203");
    static final List<String> FILES = List.of("local-demo", "demo-001", "demo-002", "demo-003");
    @TempDir Path directory;

    @Test
    void onlyTheDedicatedPrivateOrExplicitLoopbackDatabaseAndAppRoleAreAccepted() {
        for (String url : List.of("jdbc:postgresql://db:5432/worklog_demo",
                "jdbc:postgresql://127.0.0.1:6543/worklog_demo", "jdbc:postgresql://[::1]:6543/worklog_demo")) {
            var values = environment();
            values.put("TNA_JDBC_URL", url);
            var source = assertInstanceOf(DriverManagerDataSource.class, PublicDemoPersonnelFill.configuration(values));
            assertEquals(url, source.getUrl());
            assertEquals("tna_app", source.getUsername());
            assertEquals("  synthetic-app-password  ", source.getPassword());
        }
    }

    @Test
    void otherHostsDatabasesOptionsAndProfilesAreRejectedWithoutEchoingCredentials() {
        for (String url : List.of("jdbc:postgresql://db:5433/worklog_demo", "jdbc:postgresql://other:5432/worklog_demo",
                "jdbc:postgresql://127.0.0.1:5432/other", "jdbc:postgresql://db:5432/worklog_demo?user=postgres",
                "jdbc:postgresql://secret@db:5432/worklog_demo", "jdbc:postgresql://localhost:5432/worklog_demo",
                "jdbc:postgresql://127.0.0.1:0/worklog_demo", "jdbc:postgresql://127.0.0.1:65536/worklog_demo")) {
            var values = environment();
            values.put("TNA_JDBC_URL", url);
            var failure = assertThrows(IllegalArgumentException.class, () -> PublicDemoPersonnelFill.configuration(values));
            assertFalse(failure.toString().contains("secret"));
            assertFalse(failure.toString().contains(values.get("TNA_APP_DB_PASSWORD")));
        }
        for (String profile : List.of("local", "public-demo,local", "public-demo,public-demo", "")) {
            var values = environment();
            values.put("SPRING_PROFILES_ACTIVE", profile);
            assertThrows(IllegalArgumentException.class, () -> PublicDemoPersonnelFill.configuration(values));
        }
    }

    @Test
    void missingApplicationCredentialsCannotFallBackToMigrationOrOperatorCredentials() {
        for (String name : environment().keySet()) {
            var values = environment();
            values.remove(name);
            values.put("TNA_MIGRATION_DB_PASSWORD", "unrelated-secret");
            assertThrows(IllegalArgumentException.class, () -> PublicDemoPersonnelFill.configuration(values));
        }
    }

    @Test
    void explicitCommandRejectsWrongConfirmationAndArgumentsBeforeEnvironmentOrWebStartup() {
        for (String[] args : List.of(new String[0], new String[]{"wrong", "directory", "2026-11-02"},
                new String[]{"worklog-demo-personnel-20261102", "directory", "bad-date"},
                new String[]{"worklog-demo-personnel-20261102", "directory", "2026-11-02", "extra"})) {
            assertThrows(IllegalArgumentException.class, () -> PublicDemoPersonnelFill.main(args));
            var appArgs = new String[args.length + 1];
            appArgs[0] = "public-demo-personnel-fill";
            System.arraycopy(args, 0, appArgs, 1, args.length);
            assertThrows(IllegalArgumentException.class, () -> TnaApplication.main(appArgs));
        }
    }

    @Test
    void changedMetadataOrMissingNormalizedHashIsRejectedBeforeOpeningTheDatabase() throws Exception {
        var manifest = assets(directory);
        employees(manifest).get(2).put("employmentStatus", "WORKING");
        writeManifest(directory, manifest);
        assertInvalidAssets();
        manifest = assets(directory);
        employees(manifest).get(2).remove("normalizedSha256");
        writeManifest(directory, manifest);
        assertInvalidAssets();
    }

    @Test
    void changedFileHashTraversalAndPhotoBytesAreRejectedBeforeOpeningTheDatabase() throws Exception {
        var manifest = assets(directory);
        employees(manifest).get(3).put("photoFile", "../outside.jpg");
        writeManifest(directory, manifest);
        assertInvalidAssets();
        manifest = assets(directory);
        employees(manifest).get(3).put("normalizedSha256", "0".repeat(64));
        writeManifest(directory, manifest);
        assertInvalidAssets();
        assets(directory);
        Files.write(directory.resolve("photos/demo-003.jpg"), new byte[]{1, 2, 3});
        assertInvalidAssets();
    }

    @Test
    void theManifestDateExpectedDateAndSeoulTodayMustAllAgree() throws Exception {
        assets(directory);
        assertThrows(IllegalArgumentException.class, () -> PublicDemoPersonnelFill.fill(neverConnect(), directory,
                TODAY.plusDays(1), CLOCK));
        assertThrows(IllegalArgumentException.class, () -> PublicDemoPersonnelFill.fill(neverConnect(), directory,
                TODAY, Clock.offset(CLOCK, java.time.Duration.ofDays(1))));
        var manifest = assets(directory);
        manifest.put("referenceDate", "2026-11-01");
        writeManifest(directory, manifest);
        assertInvalidAssets();
    }

    @Test
    void aLargeVersionCannotWrapToTheRequiredExactZero() throws Exception {
        var manifest = assets(directory);
        employees(manifest).get(0).put("expectedVersion", java.math.BigInteger.ONE.shiftLeft(64));
        writeManifest(directory, manifest);
        assertInvalidAssets();
    }

    private void assertInvalidAssets() {
        assertThrows(IllegalArgumentException.class, () -> PublicDemoPersonnelFill.fill(neverConnect(), directory, TODAY, CLOCK));
    }

    private DataSource neverConnect() {
        return new AbstractDataSource() {
            @Override public Connection getConnection() { throw new AssertionError("입력 거부 전에 DB에 접근하면 안 됩니다."); }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
    }

    private Map<String, String> environment() {
        return new HashMap<>(Map.of("SPRING_PROFILES_ACTIVE", "public-demo",
                "TNA_JDBC_URL", "jdbc:postgresql://db:5432/worklog_demo",
                "TNA_APP_DB_PASSWORD", "  synthetic-app-password  "));
    }

    static Map<String, Object> assets(Path directory) throws Exception {
        Files.createDirectories(directory.resolve("photos"));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> births = List.of("1988-07-14", "1992-03-11", "1986-09-22", "1996-12-05");
        List<String> grades = List.of("책임", "선임", "수석", "사원");
        List<String> positions = List.of("운영팀장", "팀원", "개발팀장", "팀원");
        List<String> jobs = List.of("인사 운영", "서비스 기획", "소프트웨어 개발", "품질 관리");
        List<String> statuses = List.of("WORKING", "WORKING", "ON_LEAVE", "SUSPENDED");
        for (int index = 0; index < 4; index++) {
            var pixels = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
            pixels.setRGB(1, 1, 0x224466 + index * 0x111111);
            var output = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(pixels, "png", output));
            byte[] bytes = output.toByteArray();
            String file = "photos/" + FILES.get(index) + ".jpg";
            Files.write(directory.resolve(file), bytes);
            var normalized = EmployeePhoto.decode(Base64.getEncoder().encodeToString(bytes));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeId", IDS.get(index));
            row.put("employeeNo", index == 0 ? "LOCAL-DEMO" : "DEMO-00" + index);
            row.put("birthDate", births.get(index));
            row.put("gradeName", grades.get(index));
            row.put("positionName", positions.get(index));
            row.put("jobName", jobs.get(index));
            row.put("employmentStatus", statuses.get(index));
            row.put("expectedVersion", 0);
            row.put("photoFile", file);
            row.put("sourceSha256", sha(bytes));
            row.put("normalizedSha256", sha(normalized.bytes()));
            rows.add(row);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("referenceDate", TODAY.toString());
        manifest.put("employees", rows);
        writeManifest(directory, manifest);
        return manifest;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> employees(Map<String, Object> manifest) {
        return (List<Map<String, Object>>) manifest.get("employees");
    }

    static void writeManifest(Path directory, Map<String, Object> manifest) throws Exception {
        Files.writeString(directory.resolve("manifest.json"), JsonMapper.builder().build().writeValueAsString(manifest));
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
