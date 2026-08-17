package io.github.flimkit.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import qupath.lib.color.ColorModelFactory;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerBuilder.ServerBuilder;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.List;

public class FlimKitImageServer extends AbstractTileableImageServer {

    private final URI uri;
    private final String[] args;
    private final BridgeClient client;
    private final String datasetId;
    private final ImageServerMetadata metadata;

    FlimKitImageServer(URI uri, Discovery.Details details, String... args) throws IOException,
            InterruptedException {
        super();
        this.uri = uri;
        this.args = args == null ? new String[0] : args.clone();
        this.client = new BridgeClient(details.url(), details.token());
        String path = Paths.get(uri).toAbsolutePath().toString();
        JsonObject opened = JsonParser.parseString(client.openDataset(path)).getAsJsonObject();
        this.datasetId = opened.get("id").getAsString();
        this.metadata = buildMetadata(opened, path);
    }

    private ImageServerMetadata buildMetadata(JsonObject opened, String path) {
        int width = opened.get("width").getAsInt();
        int height = opened.get("height").getAsInt();
        var builder = new ImageServerMetadata.Builder()
                .width(width)
                .height(height)
                .pixelType(PixelType.UINT16)
                .channels(List.of(ImageChannel.getInstance(
                        "Intensity (photons)", ImageChannel.getDefaultChannelColor(0))))
                .rgb(false)
                .name(Paths.get(path).getFileName().toString())
                .levelsFromDownsamples(1.0)
                .preferredTileSize(width, height);
        if (!opened.get("pixel_size_um").isJsonNull()) {
            double size = opened.get("pixel_size_um").getAsDouble();
            if (size > 0)
                builder.pixelSizeMicrons(size, size);
        }
        return builder.build();
    }

    @Override
    protected BufferedImage readTile(TileRequest request) throws IOException {
        byte[] tiff;
        try {
            tiff = client.planeTiff(datasetId, "intensity",
                    request.getImageX(), request.getImageY(),
                    request.getImageWidth(), request.getImageHeight());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        var opener = new ij.io.Opener();
        var imp = opener.openTiff(new ByteArrayInputStream(tiff), "tile");
        if (imp == null)
            throw new IOException("FLIMKit returned a TIFF QuPath could not decode");
        return imp.getBufferedImage();
    }

    @Override
    protected String createID() {
        return getClass().getName() + ": " + uri + " " + String.join(" ", args);
    }

    @Override
    protected ServerBuilder<BufferedImage> createServerBuilder() {
        return qupath.lib.images.servers.ImageServerBuilder.DefaultImageServerBuilder
                .createInstance(FlimKitServerBuilder.class, getMetadata(), uri, args);
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return metadata;
    }

    @Override
    public String getServerType() {
        return "FLIMKit bridge";
    }

    @Override
    public java.util.Collection<URI> getURIs() {
        return List.of(uri);
    }

    String getDatasetId() {
        return datasetId;
    }

    @Override
    public void close() throws Exception {
        try {
            client.closeDataset(datasetId);
        } catch (Exception ignored) {
        }
        super.close();
    }
}
