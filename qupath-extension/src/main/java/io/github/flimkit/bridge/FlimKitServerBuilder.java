package io.github.flimkit.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

public class FlimKitServerBuilder implements ImageServerBuilder<BufferedImage> {

    private static final Logger logger = LoggerFactory.getLogger(FlimKitServerBuilder.class);

    static final String DISABLE_PROPERTY = "flimkit.bridge.imageserver";

    private static final Set<String> AMBIGUOUS = Set.of(
            ".tif", ".tiff", ".ome.tif", ".json", ".bin");

    @Override
    public String getName() {
        return "FLIMKit bridge builder";
    }

    @Override
    public String getDescription() {
        return "Opens FLIM files by asking a running FLIMKit to read them";
    }

    @Override
    public Class<BufferedImage> getImageType() {
        return BufferedImage.class;
    }

    @Override
    public UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args) {
        try {
            if (!"file".equalsIgnoreCase(uri.getScheme()))
                return null;
            if ("false".equalsIgnoreCase(System.getProperty(DISABLE_PROPERTY)))
                return null;
            Path path = Paths.get(uri);
            if (!Files.isRegularFile(path))
                return null;
            var details = Discovery.read();
            if (details.stale())
                return null;
            var client = new BridgeClient(details.url(), details.token());
            JsonObject reply = JsonParser.parseString(
                    client.identify(path.toAbsolutePath().toString())).getAsJsonObject();
            if (!reply.get("recognised").getAsBoolean())
                return null;
            float level = reply.get("ambiguous").getAsBoolean() ? 1f : 4f;
            var builder = ImageServerBuilder.DefaultImageServerBuilder.createInstance(
                    FlimKitServerBuilder.class, uri, args);
            return UriImageSupport.createInstance(FlimKitServerBuilder.class, level, builder);
        } catch (Throwable t) {
            logger.debug("FLIMKit bridge declined {}: {}", uri, t.getMessage());
            return null;
        }
    }

    @Override
    public ImageServer<BufferedImage> buildServer(URI uri, String... args) throws Exception {
        var details = Discovery.read();
        if (details.stale())
            throw new java.io.IOException(
                    "FLIMKit published a bridge address but that FLIMKit is no longer running");
        return new FlimKitImageServer(uri, details, args);
    }

    static boolean isAmbiguous(String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        for (String ext : AMBIGUOUS) {
            if (lowered.endsWith(ext))
                return true;
        }
        return false;
    }

    static Set<String> ambiguousExtensions() {
        return Collections.unmodifiableSet(AMBIGUOUS);
    }
}
