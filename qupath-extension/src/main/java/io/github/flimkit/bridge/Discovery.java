package io.github.flimkit.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Discovery {

    private Discovery() {}

    static final String FILENAME = "bridge.json";
    static final String LEGACY_FILENAME = "qupath-bridge.json";

    public static Path defaultPath() {
        return discoveryDir().resolve(FILENAME);
    }

    static Path discoveryDir() {
        return Paths.get(System.getProperty("user.home"), ".flimkit");
    }

    public static Details read() throws IOException {
        return readFrom(discoveryDir());
    }

    static Details readFrom(Path directory) throws IOException {
        Path current = directory.resolve(FILENAME);
        if (Files.exists(current))
            return read(current);
        Path legacy = directory.resolve(LEGACY_FILENAME);
        if (Files.exists(legacy))
            return read(legacy);
        throw new IOException("FLIMKit has not published a bridge address at "
                + current + " or " + legacy
                + ". Start FLIMKit, or run flimkit-bridge.");
    }

    public static Details read(Path path) throws IOException {
        if (!Files.exists(path))
            throw new IOException("FLIMKit has not published a bridge address at " + path);
        String text = Files.readString(path, StandardCharsets.UTF_8);
        JsonObject object;
        try {
            object = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("could not read " + path + ": " + e.getMessage());
        }
        String protocol = object.has("protocol")
                ? object.get("protocol").getAsString() : "";
        if (!"flimkit-bridge".equals(protocol) && !"flimkit-qupath".equals(protocol))
            throw new IOException(path + " is not a FLIMKit bridge file");
        if (!object.has("url") || !object.has("token"))
            throw new IOException(path + " is missing the address or token");
        return new Details(
                object.get("url").getAsString(),
                object.get("token").getAsString(),
                object.has("pid") ? object.get("pid").getAsLong() : -1);
    }

    public static boolean processAlive(long pid) {
        if (pid <= 0)
            return true;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public record Details(String url, String token, long pid) {

        public boolean stale() {
            return !processAlive(pid);
        }
    }
}
