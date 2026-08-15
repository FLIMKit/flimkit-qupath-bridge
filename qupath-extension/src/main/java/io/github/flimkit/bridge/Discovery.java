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

    public static Path defaultPath() {
        return Paths.get(System.getProperty("user.home"), ".flimkit", "qupath-bridge.json");
    }

    public static Details read() throws IOException {
        return read(defaultPath());
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
        if (!object.has("protocol")
                || !"flimkit-qupath".equals(object.get("protocol").getAsString()))
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
