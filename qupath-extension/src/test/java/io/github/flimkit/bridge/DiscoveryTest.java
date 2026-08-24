package io.github.flimkit.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryTest {

    private static Path write(Path directory, String text) throws IOException {
        Path file = directory.resolve("qupath-bridge.json");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void readsAPublishedAddress(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                {"protocol": "flimkit-qupath", "url": "http://127.0.0.1:8123",
                 "token": "abc", "pid": 4321}
                """);

        var details = Discovery.read(file);

        assertEquals("http://127.0.0.1:8123", details.url());
        assertEquals("abc", details.token());
        assertEquals(4321L, details.pid());
    }

    @Test
    void aFileWithoutAPidStillReads(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                {"protocol": "flimkit-qupath", "url": "http://127.0.0.1:8123",
                 "token": "abc"}
                """);

        assertEquals(-1L, Discovery.read(file).pid());
    }

    @Test
    void saysSoWhenFlimkitHasNotPublishedOne(@TempDir Path directory) {
        Path missing = directory.resolve("qupath-bridge.json");

        var raised = assertThrows(IOException.class, () -> Discovery.read(missing));

        assertTrue(raised.getMessage().contains("has not published"), raised.getMessage());
    }

    @Test
    void rejectsAFileFromSomethingElse(@TempDir Path directory) throws IOException {
        Path file = write(directory, "{\"protocol\": \"something-else\"}");

        var raised = assertThrows(IOException.class, () -> Discovery.read(file));

        assertTrue(raised.getMessage().contains("not a FLIMKit bridge file"),
                raised.getMessage());
    }

    @Test
    void rejectsAFileMissingTheToken(@TempDir Path directory) throws IOException {
        Path file = write(directory, """
                {"protocol": "flimkit-qupath", "url": "http://127.0.0.1:8123"}
                """);

        var raised = assertThrows(IOException.class, () -> Discovery.read(file));

        assertTrue(raised.getMessage().contains("missing the address or token"),
                raised.getMessage());
    }

    @Test
    void rejectsTextThatIsNotJson(@TempDir Path directory) throws IOException {
        Path file = write(directory, "not json at all");

        assertThrows(IOException.class, () -> Discovery.read(file));
    }

    @Test
    void anAddressFromALiveProcessIsNotStale() {
        var alive = new Discovery.Details("http://127.0.0.1:1", "t",
                ProcessHandle.current().pid());
        var unknown = new Discovery.Details("http://127.0.0.1:1", "t", -1);

        assertFalse(alive.stale());
        assertFalse(unknown.stale());
    }

    @Test
    void theDefaultPathIsUnderTheHomeDirectory() {
        var path = Discovery.defaultPath();

        assertEquals("qupath-bridge.json", path.getFileName().toString());
        assertEquals(".flimkit", path.getParent().getFileName().toString());
    }
}
