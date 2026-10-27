package kr.tna;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.MountableFile;

class DatabaseFixtureImageTest {
    @Test
    void ownedPostgresBuildUsesOnlyTheSharedPatchedDockerfileWithoutResolvingDocker() throws Exception {
        var image = DatabaseFixture.postgresImage();

        assertEquals(Set.of("Dockerfile"), image.getTransferables().keySet());
        var dockerfile = assertInstanceOf(MountableFile.class, image.getTransferables().get("Dockerfile"));
        assertEquals(Path.of("infra/postgres/Dockerfile").toRealPath(), Path.of(dockerfile.getResolvedPath()).toRealPath());
        assertTrue(image.isDeleteOnExit());
        assertFalse(image.isDone());
    }

    @Test
    void explicitJdbcTargetsStillRequireTheDedicatedLoopbackDatabase() {
        assertDoesNotThrow(() -> DatabaseFixture.validateLocalTestUrl(
                "jdbc:postgresql://127.0.0.1:15432/" + DatabaseFixture.DATABASE_NAME));
        assertDoesNotThrow(() -> DatabaseFixture.validateLocalTestUrl(
                "jdbc:postgresql://localhost:15432/" + DatabaseFixture.DATABASE_NAME + "?loggerLevel=OFF"));
        for (var target : List.of("jdbc:postgresql://example.test:5432/" + DatabaseFixture.DATABASE_NAME,
                "jdbc:postgresql://127.0.0.1:5432/postgres",
                "jdbc:postgresql://user@127.0.0.1:5432/" + DatabaseFixture.DATABASE_NAME)) {
            assertThrows(IllegalArgumentException.class, () -> DatabaseFixture.validateLocalTestUrl(target));
        }
    }
}
