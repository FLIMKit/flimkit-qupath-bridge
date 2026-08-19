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

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(FlimKitImageServer.class);

    private final URI uri;
    private final String[] args;
    private final BridgeClient client;
    private final String datasetId;
    private final String sourcePath;
    private final java.util.List<String> planes;
    private final JsonObject opened;
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
        this.sourcePath = opened.has("path") && !opened.get("path").isJsonNull()
                ? opened.get("path").getAsString()
                : path;
        this.opened = opened;
        this.planes = fittedPlanes();
        this.metadata = buildMetadata(opened, path);
    }

    private java.util.List<String> fittedPlanes() {
        var found = new java.util.ArrayList<String>();
        found.add("intensity");
        try {
            var listed = JsonParser.parseString(client.planes(datasetId))
                    .getAsJsonObject().getAsJsonArray("planes");
            for (var element : listed) {
                String name = element.getAsJsonObject().get("id").getAsString();
                if (name.startsWith("tau") && !found.contains(name))
                    found.add(name);
            }
        } catch (Exception e) {
            logger.debug("No fitted planes to add as channels: {}", e.getMessage());
        }
        return java.util.List.copyOf(found);
    }

    private static final int TILE = 512;

    static double[] downsamplesFor(int width, int height) {
        var levels = new java.util.ArrayList<Double>();
        double level = 1.0;
        while (true) {
            levels.add(level);
            if (width / level <= TILE * 2 && height / level <= TILE * 2)
                break;
            if (levels.size() >= 8)
                break;
            level *= 2;
        }
        double[] found = new double[levels.size()];
        for (int i = 0; i < levels.size(); i++)
            found[i] = levels.get(i);
        return found;
    }

    private ImageServerMetadata buildMetadata(JsonObject opened, String path) {
        int width = opened.get("width").getAsInt();
        int height = opened.get("height").getAsInt();
        boolean small = width <= TILE && height <= TILE;
        var builder = new ImageServerMetadata.Builder()
                .width(width)
                .height(height)
                .pixelType(planes.size() > 1 ? PixelType.FLOAT32 : PixelType.UINT16)
                .channels(channelsFor())
                .rgb(false)
                .name(Paths.get(path).getFileName().toString())
                .levelsFromDownsamples(small
                        ? new double[] {1.0}
                        : downsamplesFor(width, height))
                .preferredTileSize(small ? width : TILE, small ? height : TILE);
        if (!opened.get("pixel_size_um").isJsonNull()) {
            double size = opened.get("pixel_size_um").getAsDouble();
            if (size > 0)
                builder.pixelSizeMicrons(size, size);
        }
        return builder.build();
    }

    private List<ImageChannel> channelsFor() {
        var found = new java.util.ArrayList<ImageChannel>();
        for (int i = 0; i < planes.size(); i++) {
            String name = planes.get(i);
            String label = name.equals("intensity")
                    ? "Intensity (photons)"
                    : name + " (ns)";
            found.add(ImageChannel.getInstance(
                    label, ImageChannel.getDefaultChannelColor(i)));
        }
        return List.copyOf(found);
    }

    @Override
    protected BufferedImage readTile(TileRequest request) throws IOException {
        byte[] tiff;
        try {
            tiff = planes.size() > 1
                    ? client.planeStackTiff(datasetId, String.join(",", planes),
                            request.getImageX(), request.getImageY(),
                            request.getImageWidth(), request.getImageHeight(),
                            (int) Math.round(request.getDownsample()),
                            request.getTileWidth(), request.getTileHeight())
                    : client.planeTiff(datasetId, "intensity",
                            request.getImageX(), request.getImageY(),
                            request.getImageWidth(), request.getImageHeight(),
                            (int) Math.round(request.getDownsample()),
                            request.getTileWidth(), request.getTileHeight());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        var opener = new ij.io.Opener();
        var imp = opener.openTiff(new ByteArrayInputStream(tiff), "tile");
        if (imp == null)
            throw new IOException("FLIMKit returned a TIFF QuPath could not decode");
        if (planes.size() == 1)
            return imp.getBufferedImage();
        int width = imp.getWidth();
        int height = imp.getHeight();
        var stack = imp.getStack();
        if (stack.getSize() != planes.size())
            throw new IOException("FLIMKit returned " + stack.getSize()
                    + " channels, expected " + planes.size());
        var raster = java.awt.image.WritableRaster.createBandedRaster(
                java.awt.image.DataBuffer.TYPE_FLOAT, width, height,
                planes.size(), null);
        for (int channel = 0; channel < planes.size(); channel++) {
            var processor = stack.getProcessor(channel + 1).convertToFloat();
            raster.setSamples(0, 0, width, height, channel,
                    (float[]) processor.getPixels());
        }
        return new BufferedImage(
                ColorModelFactory.createColorModel(PixelType.FLOAT32,
                        metadata.getChannels()),
                raster, false, null);
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

    JsonObject getOpenedMetadata() {
        return opened;
    }

    String getDatasetId() {
        return datasetId;
    }

    String getSourcePath() {
        return sourcePath;
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
