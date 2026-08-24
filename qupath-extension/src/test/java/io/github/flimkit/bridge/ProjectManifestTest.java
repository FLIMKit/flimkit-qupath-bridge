package io.github.flimkit.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectManifestTest {

    @Test
    void aMissingFileStartsAnEmptyManifest(@TempDir Path directory) {
        var root = ProjectManifest.read(directory.resolve(ProjectManifest.FILENAME));

        assertEquals("flimkit-qupath-bridge", root.get("written_by").getAsString());
        assertEquals(1, root.get("version").getAsInt());
        assertEquals(0, root.getAsJsonArray("images").size());
        assertEquals(0, root.getAsJsonArray("fits").size());
    }

    @Test
    void anExistingManifestKeepsItsEntries(@TempDir Path directory) throws IOException {
        Path file = directory.resolve(ProjectManifest.FILENAME);
        Files.writeString(file, """
                {"version": 1, "images": [{"id": "d1"}], "fits": []}
                """, StandardCharsets.UTF_8);

        var root = ProjectManifest.read(file);

        assertEquals(1, root.getAsJsonArray("images").size());
        assertEquals("d1",
                root.getAsJsonArray("images").get(0).getAsJsonObject()
                        .get("id").getAsString());
    }

    @Test
    void aCorruptManifestIsReplacedRatherThanThrown(@TempDir Path directory)
            throws IOException {
        Path file = directory.resolve(ProjectManifest.FILENAME);
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        var root = ProjectManifest.read(file);

        assertTrue(root.has("images"));
        assertEquals(0, root.getAsJsonArray("images").size());
    }

    @Test
    void aManifestWithTheWrongShapeIsRepaired(@TempDir Path directory)
            throws IOException {
        Path file = directory.resolve(ProjectManifest.FILENAME);
        Files.writeString(file, "{\"images\": \"not an array\"}", StandardCharsets.UTF_8);

        var root = ProjectManifest.read(file);

        assertTrue(root.get("images").isJsonArray());
        assertEquals(0, root.getAsJsonArray("images").size());
    }

    @Test
    void everyManifestIsStampedWithItsWriter(@TempDir Path directory)
            throws IOException {
        Path file = directory.resolve(ProjectManifest.FILENAME);
        Files.writeString(file, "{\"written_by\": \"something else\"}",
                StandardCharsets.UTF_8);

        var root = ProjectManifest.read(file);

        assertEquals("flimkit-qupath-bridge", root.get("written_by").getAsString());
    }
}
