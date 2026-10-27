package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class PostgresImageSecurityIT {
    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startOwnedImage() {
        var supplied = System.getProperty("tna.test.jdbc-url", System.getenv("TNA_TEST_JDBC_URL"));
        assumeTrue(supplied == null || supplied.isBlank(), "Image checks require an owned Docker database.");
        postgres = new PostgreSQLContainer(DockerImageName.parse(DatabaseFixture.postgresImage().get())
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("tna_image_security_test")
                .withUsername("image_probe")
                .withPassword("Synthetic-Image-Probe-Only-43!")
                .withEnv("POSTGRES_INITDB_ARGS", "--auth-host=scram-sha-256")
                .withLabel("tna.task", "postgres-image-security-test")
                .withCreateContainerCmdModifier(DatabaseFixture::bindOwnedPostgresToLoopback);
        postgres.start();
    }

    @AfterAll
    static void stopOwnedImage() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void runtimeExcludesUnusedGpgAndSqliteWhileRetainingSignedAptAndDatabaseTools() throws Exception {
        var result = postgres.execInContainer("sh", "-ec", """
                if command -v gpg; then echo 'Unused GPG remains installed' >&2; exit 1; fi
                if dpkg-query -W -f='${db:Status-Status}\\n' gnupg gpg libsqlite3-0 2>/dev/null | grep -qx installed; then
                    echo 'Unused GPG or SQLite package remains installed' >&2; exit 1
                fi
                test ! -e /usr/lib/x86_64-linux-gnu/libsqlite3.so.0
                command -v gpgv
                gosu nobody true
                test "$(LC_ALL=C.UTF-8 locale charmap)" = UTF-8
                for tool in postgres initdb psql pg_dump pg_restore createdb; do
                    binary="/usr/lib/postgresql/18/bin/$tool"
                    test -x "$binary"
                    dependencies="$(ldd "$binary")"
                    if printf '%s\\n' "$dependencies" | grep -q 'not found'; then exit 1; fi
                done
                """);
        assertEquals(0, result.getExitCode(), result.getStdout() + result.getStderr());
    }

    @Test
    void customFormatBackupRestoresUtf8DataInAnIndependentDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.image_security_probe(id int PRIMARY KEY, label text NOT NULL)");
            statement.execute("INSERT INTO public.image_security_probe VALUES (1, '합성 백업 자료'), (2, 'UTF-8 확인')");
        }
        var result = postgres.execInContainer("sh", "-ec", """
                pg_dump -U image_probe -d tna_image_security_test -Fc -t public.image_security_probe -f /tmp/image-security.dump
                pg_restore --list /tmp/image-security.dump >/dev/null
                createdb -U image_probe tna_image_security_restore
                pg_restore --exit-on-error --no-owner -U image_probe -d tna_image_security_restore /tmp/image-security.dump
                """);
        assertEquals(0, result.getExitCode(), result.getStdout() + result.getStderr());
        var restoredUrl = postgres.getJdbcUrl().replace("/tna_image_security_test", "/tna_image_security_restore");
        try (var connection = DriverManager.getConnection(restoredUrl, postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT id,label FROM public.image_security_probe ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals("합성 백업 자료", rows.getString(2));
            assertTrue(rows.next());
            assertEquals("UTF-8 확인", rows.getString(2));
            assertFalse(rows.next());
        }
    }

    @Test
    void scramInitializationRemainsAvailableWhileXmlParsingIsBlocked() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SHOW password_encryption")) {
                assertTrue(rows.next());
                assertEquals("scram-sha-256", rows.getString(1));
            }
            var error = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT xmlparse(document '<probe>ok</probe>')::text"));
            assertEquals("0A000", error.getSQLState());
        }
    }

    @Test
    void xmlParserInputsAreBlockedForBothAdministrativeAndOrdinaryRoles() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE image_xml_probe NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
            for (var role : List.of(postgres.getUsername(), "image_xml_probe")) {
                statement.execute("SET ROLE " + role);
                for (var sql : List.of(
                        "SELECT XMLPARSE(DOCUMENT '<probe/>')",
                        "SELECT XMLPARSE(CONTENT '<probe/>')",
                        "SELECT '<probe/>'::xml",
                        "SELECT xml '<probe/>'",
                        "SELECT xml_is_well_formed('<probe/>')",
                        "SELECT xml_is_well_formed_document('<probe/>')",
                        "SELECT xml_is_well_formed_content('<probe/>')",
                        "SELECT xpath('/probe', '<probe/>'::xml)",
                        "SELECT xpath_exists('/probe', '<probe/>'::xml)",
                        "SELECT XMLEXISTS('/probe' PASSING ('<probe/>'::xml))",
                        "SELECT * FROM XMLTABLE('/probe' PASSING ('<probe/>'::xml) COLUMNS value text PATH '.') AS x")) {
                    var error = assertThrows(SQLException.class, () -> statement.executeQuery(sql), sql);
                    assertEquals("0A000", error.getSQLState(), sql);
                }
            }
        }
    }

    @Test
    void runtimeContainsNoXmlXsltOrLlvmParserConsumers() throws Exception {
        var result = postgres.execInContainer("sh", "-ec", """
                for package in libxml2 libxslt1.1 libllvm19 postgresql-18-jit; do
                    if dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null | grep -qx installed; then
                        echo "Forbidden runtime package: $package" >&2; exit 1
                    fi
                done
                for library in libxml2.so.2 libxslt.so.1 libLLVM.so.19.1; do
                    test ! -e "/usr/lib/x86_64-linux-gnu/$library"
                done
                test ! -e /usr/lib/postgresql/18/lib/llvmjit.so
                test ! -e /usr/lib/postgresql/18/lib/pgxml.so
                test ! -e /usr/share/postgresql/18/extension/xml2.control
                dependencies="$(ldd /usr/lib/postgresql/18/bin/postgres)"
                if printf '%s\\n' "$dependencies" | grep -E 'libxml|libxslt|libLLVM|not found'; then exit 1; fi
                """);
        assertEquals(0, result.getExitCode(), result.getStdout() + result.getStderr());
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT pg_jit_available()")) {
            assertTrue(rows.next());
            assertFalse(rows.getBoolean(1));
        }
    }

    @Test
    void binaryCopyCannotReachXmlReceiveParser() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.write(new byte[] {'P', 'G', 'C', 'O', 'P', 'Y', '\n', (byte) 0xff, '\r', '\n', 0});
            output.writeInt(0);
            output.writeInt(0);
            output.writeShort(1);
            var xml = "<probe/>".getBytes(StandardCharsets.UTF_8);
            output.writeInt(xml.length);
            output.write(xml);
            output.writeShort(-1);
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.image_xml_binary_probe (payload xml)");
            try {
                var copies = connection.unwrap(PGConnection.class).getCopyAPI();
                var error = assertThrows(SQLException.class, () -> copies.copyIn(
                        "COPY public.image_xml_binary_probe FROM STDIN BINARY", new ByteArrayInputStream(bytes.toByteArray())));
                assertEquals("0A000", error.getSQLState());
            } finally {
                statement.execute("DROP TABLE public.image_xml_binary_probe");
            }
        }
    }
}
