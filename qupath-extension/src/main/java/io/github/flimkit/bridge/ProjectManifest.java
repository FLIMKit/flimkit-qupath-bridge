package io.github.flimkit.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ProjectManifest {

    private static final Logger logger = LoggerFactory.getLogger(ProjectManifest.class);

    static final String FILENAME = "manifest.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final JsonObject root;

    private ProjectManifest(Path file, JsonObject root) {
        this.file = file;
        this.root = root;
    }

    public static Path folder(Project<BufferedImage> project) {
        Path projectPath = project == null ? null : project.getPath();
        if (projectPath == null)
            return null;
        return projectPath.getParent().resolve("flimkit");
    }

    public static ProjectManifest open(Project<BufferedImage> project) {
        Path directory = folder(project);
        if (directory == null)
            return null;
        Path file = directory.resolve(FILENAME);
        JsonObject root = read(file);
        return new ProjectManifest(file, root);
    }

    static JsonObject read(Path file) {
        if (Files.exists(file)) {
            try {
                var parsed = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8));
                if (parsed.isJsonObject())
                    return prepare(parsed.getAsJsonObject());
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not read {}, starting a new one: {}", file, e.getMessage());
            }
        }
        return prepare(new JsonObject());
    }

    private static JsonObject prepare(JsonObject root) {
        root.addProperty("written_by", "flimkit-qupath-bridge");
        if (!root.has("version"))
            root.addProperty("version", 1);
        if (!root.has("images") || !root.get("images").isJsonArray())
            root.add("images", new JsonArray());
        if (!root.has("fits") || !root.get("fits").isJsonArray())
            root.add("fits", new JsonArray());
        return root;
    }

    public void recordImage(String imageId, String storedFile, String unit, String source) {
        var entry = new JsonObject();
        entry.addProperty("image_id", imageId);
        entry.addProperty("file", storedFile);
        entry.addProperty("unit", unit);
        if (source != null)
            entry.addProperty("source", source);
        entry.addProperty("added", Instant.now().toString());
        replace(root.getAsJsonArray("images"), "image_id", imageId, entry);
    }

    public void recordFit(String source, JsonObject params,
                          Collection<PathObject> annotations, JsonObject reply) {
        JsonArray results = reply != null && reply.has("results")
                && reply.get("results").isJsonArray()
                ? reply.getAsJsonArray("results")
                : new JsonArray();
        var named = new ArrayList<>(annotations);
        var entry = new JsonObject();
        entry.addProperty("source", source);
        entry.addProperty("fitted", Instant.now().toString());
        entry.add("params", params == null ? new JsonObject() : params);
        if (reply != null && reply.has("dataset"))
            entry.add("dataset", reply.get("dataset"));
        var regions = new JsonArray();
        for (int i = 0; i < results.size() && i < named.size(); i++) {
            var result = results.get(i).getAsJsonObject();
            if (result.has("error"))
                continue;
            var region = new JsonObject();
            region.addProperty("annotation", String.valueOf(named.get(i).getID()));
            region.addProperty("name", named.get(i).getName() == null
                    ? "" : named.get(i).getName());
            for (String key : List.of("tau_mean_ns", "chi2_r", "photon_count", "n_pixels")) {
                if (result.has(key) && !result.get(key).isJsonNull())
                    region.add(key, result.get(key));
            }
            regions.add(region);
        }
        entry.add("regions", regions);
        root.getAsJsonArray("fits").add(entry);
    }

    private static void replace(JsonArray array, String key, String value, JsonObject entry) {
        for (int i = 0; i < array.size(); i++) {
            var held = array.get(i).getAsJsonObject();
            if (held.has(key) && value.equals(held.get(key).getAsString())) {
                array.set(i, entry);
                return;
            }
        }
        array.add(entry);
    }

    public List<String> sources() {
        var found = new ArrayList<String>();
        for (var element : root.getAsJsonArray("images")) {
            var entry = element.getAsJsonObject();
            if (entry.has("source") && !entry.get("source").isJsonNull()) {
                String source = entry.get("source").getAsString();
                if (!source.isBlank() && !found.contains(source))
                    found.add(source);
            }
        }
        for (var element : root.getAsJsonArray("fits")) {
            var entry = element.getAsJsonObject();
            if (entry.has("source") && !entry.get("source").isJsonNull()) {
                String source = entry.get("source").getAsString();
                if (!source.isBlank() && !found.contains(source))
                    found.add(source);
            }
        }
        return found;
    }

    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    }

    public Path path() {
        return file;
    }
}
