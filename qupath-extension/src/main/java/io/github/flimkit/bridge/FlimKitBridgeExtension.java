package io.github.flimkit.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.tools.MenuTools;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;

import qupath.lib.projects.Project;

import javafx.scene.control.MenuItem;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class FlimKitBridgeExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(FlimKitBridgeExtension.class);

    private static final String MENU = "Extensions>FLIMKit bridge";

    private String baseUrl = "http://127.0.0.1:8765";
    private String token = "";

    @Override
    public String getName() {
        return "FLIMKit bridge";
    }

    @Override
    public String getDescription() {
        return "Direct image and ROI exchange between FLIMKit and QuPath";
    }

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create(getName(), "FLIMKit", "flimkit-qupath-bridge");
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        MenuTools.addMenuItems(
                qupath.getMenu(MENU, true),
                menuItem("Connect", () -> promptForConnection(qupath)),
                menuItem("Connect to a different address...", () -> promptManually()),
                null,
                menuItem("Add FLIMKit images to project", () -> addImages(qupath)),
                null,
                menuItem("Stitch and fit a mosaic...", () -> stitchAndFit(qupath)),
                menuItem("Fit ROI decays...", () -> fitRois(qupath)),
                menuItem("Phasor plot...", () -> openPhasor(qupath)),
                null,
                menuItem("Send annotations to FLIMKit", () -> sendAnnotations(qupath)),
                menuItem("Fetch ROIs from FLIMKit", () -> fetchAnnotations(qupath)),
                null,
                menuItem("Reconnect this project to FLIMKit", () -> reconnect(qupath)));
    }

    private static MenuItem menuItem(String label, Runnable action) {
        var item = new MenuItem(label);
        item.setOnAction(event -> action.run());
        return item;
    }

    private void promptForConnection(QuPathGUI qupath) {
        try {
            var details = Discovery.read();
            if (details.stale()) {
                Dialogs.showErrorMessage(getName(),
                        "FLIMKit published a bridge address but that FLIMKit is no longer "
                                + "running.\n\nStart FLIMKit, then try again.");
                return;
            }
            baseUrl = details.url();
            token = details.token();
            new BridgeClient(baseUrl, token).status();
            Dialogs.showInfoNotification(getName(), "Connected to " + baseUrl);
            return;
        } catch (Exception e) {
            logger.info("No usable discovery file, asking instead: {}", e.getMessage());
        }
        promptManually();
    }

    private void promptManually() {
        String url = Dialogs.showInputDialog(
                getName(), "FLIMKit bridge address", baseUrl);
        if (url == null)
            return;
        String pairing = Dialogs.showInputDialog(
                getName(), "Pairing token", token);
        if (pairing == null)
            return;
        baseUrl = url.trim();
        token = pairing.trim();
        try {
            new BridgeClient(baseUrl, token).status();
            Dialogs.showInfoNotification(getName(), "Connected to " + baseUrl);
        } catch (Exception e) {
            logger.error("Could not reach the FLIMKit bridge", e);
            Dialogs.showErrorMessage(getName(), "Could not reach " + baseUrl
                    + "\n\n" + e.getMessage());
        }
    }

    private void addImages(QuPathGUI qupath) {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(getName(),
                    "No project is open. Create or open a project first, so the "
                            + "FLIMKit images can sit beside your brightfield image.");
            return;
        }
        var client = new BridgeClient(baseUrl, token);
        var manifest = ProjectManifest.open(project);
        var added = new ArrayList<String>();
        var skipped = new ArrayList<String>();
        for (String imageId : List.of("intensity", "lifetime")) {
            try {
                var fetched = client.fetchImage(imageId);
                Path stored = ProjectImporter.storeBesideProject(
                        project, imageId, fetched.file());
                var entry = ProjectImporter.addToProject(
                        project, stored, imageId, fetched.valueUnit());
                added.add(entry.getImageName());
                if (manifest != null)
                    manifest.recordImage(imageId, stored.getFileName().toString(),
                            fetched.valueUnit(), servedSource(client));
            } catch (Exception e) {
                logger.warn("Could not add {}", imageId, e);
                skipped.add(imageId);
            }
        }
        if (added.isEmpty()) {
            Dialogs.showErrorMessage(getName(),
                    "No FLIMKit images could be added. Is a dataset open in FLIMKit?");
            return;
        }
        try {
            project.syncChanges();
        } catch (IOException e) {
            logger.error("Could not save the project", e);
        }
        saveManifest(manifest);
        qupath.refreshProject();
        String message = "Added " + String.join(", ", added);
        if (!skipped.isEmpty())
            message += "\nNot available: " + String.join(", ", skipped);
        Dialogs.showInfoNotification(getName(), message);
    }

    private void openPhasor(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(getName(), "No image is open.");
            return;
        }
        if (!(imageData.getServer() instanceof FlimKitImageServer bridged)) {
            Dialogs.showErrorMessage(getName(),
                    "This image was not opened through the FLIMKit bridge, so "
                            + "there is no decay data to build a phasor from.\n\n"
                            + "Open the FLIM file itself with File > Open.");
            return;
        }
        try {
            new PhasorWindow(qupath, new BridgeClient(baseUrl, token),
                    bridged.getDatasetId(), imageData).show();
        } catch (Exception e) {
            logger.error("Could not open the phasor window", e);
            Dialogs.showErrorMessage(getName(),
                    "Could not open the phasor plot\n\n" + e.getMessage());
        }
    }

    private void fitRois(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(getName(), "No image is open.");
            return;
        }
        var server = imageData.getServer();
        if (!(server instanceof FlimKitImageServer bridged)) {
            Dialogs.showErrorMessage(getName(),
                    "This image was not opened through the FLIMKit bridge, so "
                            + "FLIMKit has no decay data for it.\n\n"
                            + "Open the FLIM file itself with File > Open.");
            return;
        }
        var selected = new ArrayList<>(
                imageData.getHierarchy().getSelectionModel().getSelectedObjects());
        selected.removeIf(o -> !o.isAnnotation());
        if (selected.isEmpty())
            selected.addAll(imageData.getHierarchy().getAnnotationObjects());
        if (selected.isEmpty()) {
            boolean whole = Dialogs.showConfirmDialog(getName(),
                    "No annotation is drawn.\n\nFit the whole image instead?");
            if (!whole)
                return;
            var full = ROIs.createRectangleROI(0, 0, server.getWidth(),
                    server.getHeight(), ImagePlane.getDefaultPlane());
            var annotation = PathObjects.createAnnotationObject(full);
            annotation.setName("Whole image");
            imageData.getHierarchy().addObject(annotation);
            selected.add(annotation);
        }
        try {
            var client = new BridgeClient(baseUrl, token);
            var defaults = JsonParser.parseString(client.fitDefaults()).getAsJsonObject();
            var chosen = new FitDialog(defaults).prompt("Fit ROI decays", "roi");
            if (chosen == null)
                return;
            var body = new JsonObject();
            body.add("params", chosen);
            body.add("rois", JsonParser.parseString(toFeatureCollection(selected)));
            inBackground("Fitting " + selected.size() + " region(s)",
                    () -> client.fitRois(bridged.getDatasetId(), body.toString()),
                    raw -> {
                        var reply = JsonParser.parseString(raw).getAsJsonObject();
                        int applied = FitResults.applyToObjects(reply, selected);
                        imageData.getHierarchy()
                                .fireObjectMeasurementsChangedEvent(this, selected);
                        rememberFit(qupath, bridged, chosen, selected, reply);
                        var failures = FitResults.errors(reply);
                        String message = "Fitted " + applied + " region(s)";
                        if (!failures.isEmpty())
                            message += "\n\nNot fitted:\n" + String.join("\n", failures);
                        Dialogs.showInfoNotification(getName(), message);
                    },
                    problem -> {
                        int suggested = suggestedBinning(problem);
                        if (suggested > 0 && Dialogs.showConfirmDialog(getName(),
                                "This stack is too large to decode whole.\n\n"
                                        + "Fit it again with binning " + suggested
                                        + "? That fits " + (suggested * suggested)
                                        + " pixels as one.")) {
                            chosen.addProperty("binning", suggested);
                            body.add("params", chosen);
                            refit(qupath, client, bridged, imageData, body, selected, chosen);
                            return;
                        }
                        Dialogs.showErrorMessage(getName(), "Could not fit\n\n" + problem);
                    });
        } catch (Exception e) {
            logger.error("ROI fitting failed", e);
            Dialogs.showErrorMessage(getName(), "Could not fit\n\n" + e.getMessage());
        }
    }

    private void refit(QuPathGUI qupath, BridgeClient client, FlimKitImageServer bridged,
                       qupath.lib.images.ImageData<BufferedImage> imageData,
                       JsonObject body, List<PathObject> selected, JsonObject chosen) {
        inBackground("Fitting " + selected.size() + " region(s)",
                () -> client.fitRois(bridged.getDatasetId(), body.toString()),
                raw -> {
                    var reply = JsonParser.parseString(raw).getAsJsonObject();
                    int applied = FitResults.applyToObjects(reply, selected);
                    imageData.getHierarchy()
                            .fireObjectMeasurementsChangedEvent(this, selected);
                    rememberFit(qupath, bridged, chosen, selected, reply);
                    Dialogs.showInfoNotification(getName(),
                            "Fitted " + applied + " region(s)");
                },
                again -> Dialogs.showErrorMessage(getName(), "Could not fit\n\n" + again));
    }

    static int suggestedBinning(String problem) {
        if (problem == null)
            return 0;
        var matcher = java.util.regex.Pattern.compile("binning=(\\d+)").matcher(problem);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private <T> void inBackground(String title, java.util.concurrent.Callable<T> work,
                                  java.util.function.Consumer<T> onDone,
                                  java.util.function.Consumer<String> onFailed) {
        var waiting = new javafx.stage.Stage();
        waiting.setTitle(title);
        var label = new javafx.scene.control.Label(title + "...");
        label.setMinWidth(320);
        var bar = new javafx.scene.control.ProgressBar();
        bar.setMaxWidth(Double.MAX_VALUE);
        var box = new javafx.scene.layout.VBox(10, label, bar);
        box.setPadding(new javafx.geometry.Insets(14));
        waiting.setScene(new javafx.scene.Scene(box));
        waiting.show();
        var thread = new Thread(() -> {
            try {
                T value = work.call();
                javafx.application.Platform.runLater(() -> {
                    waiting.close();
                    onDone.accept(value);
                });
            } catch (Exception e) {
                logger.error("{} failed", title, e);
                javafx.application.Platform.runLater(() -> {
                    waiting.close();
                    onFailed.accept(e.getMessage());
                });
            }
        }, "flimkit-bridge-call");
        thread.setDaemon(true);
        thread.start();
    }

    private static String servedSource(BridgeClient client) {
        try {
            var listed = JsonParser.parseString(client.datasets()).getAsJsonObject();
            var array = listed.getAsJsonArray("datasets");
            if (array != null && array.size() > 0)
                return array.get(0).getAsJsonObject().get("path").getAsString();
        } catch (Exception e) {
            logger.debug("Could not read the open datasets: {}", e.getMessage());
        }
        return null;
    }

    private void rememberFit(QuPathGUI qupath, FlimKitImageServer bridged,
                             JsonObject params, java.util.List<PathObject> annotations,
                             JsonObject reply) {
        var project = qupath.getProject();
        if (project == null)
            return;
        var manifest = ProjectManifest.open(project);
        if (manifest == null)
            return;
        manifest.recordFit(bridged.getSourcePath(), params, annotations, reply);
        saveManifest(manifest);
    }

    private void saveManifest(ProjectManifest manifest) {
        if (manifest == null)
            return;
        try {
            manifest.save();
        } catch (IOException e) {
            logger.warn("Could not write the FLIMKit manifest", e);
        }
    }

    private void reconnect(QuPathGUI qupath) {
        var project = qupath.getProject();
        if (project == null) {
            Dialogs.showErrorMessage(getName(), "No project is open.");
            return;
        }
        var manifest = ProjectManifest.open(project);
        if (manifest == null) {
            Dialogs.showErrorMessage(getName(),
                    "This project has not been saved anywhere, so there is nothing "
                            + "recorded to reconnect.");
            return;
        }
        var sources = manifest.sources();
        if (sources.isEmpty()) {
            Dialogs.showInfoNotification(getName(),
                    "Nothing recorded yet in " + manifest.path().getFileName());
            return;
        }
        var client = new BridgeClient(baseUrl, token);
        var opened = new ArrayList<String>();
        var missing = new ArrayList<String>();
        for (String source : sources) {
            try {
                client.openDataset(source);
                opened.add(java.nio.file.Paths.get(source).getFileName().toString());
            } catch (Exception e) {
                logger.warn("Could not reopen {}", source, e);
                missing.add(java.nio.file.Paths.get(source).getFileName().toString());
            }
        }
        String message = opened.isEmpty()
                ? "Nothing could be reopened"
                : "FLIMKit reopened " + String.join(", ", opened);
        if (!missing.isEmpty())
            message += "\n\nNot found:\n" + String.join("\n", missing)
                    + "\n\nThe files may have moved since the fits were recorded.";
        Dialogs.showInfoNotification(getName(), message);
    }

    private java.io.File askForTiles(java.io.File container) {
        boolean carryOn = Dialogs.showConfirmDialog(getName(),
                "FLIMKit could not find the tiles listed in " + container.getName()
                        + " near that file.\n\nChoose the folder holding them.");
        if (!carryOn)
            return null;
        var chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("Where are the tiles?");
        chooser.setInitialDirectory(container.getParentFile());
        return chooser.showDialog(null);
    }

    private void stitchAndFit(QuPathGUI qupath) {
        var chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Choose the file that holds the tile positions");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter(
                        "Leica tile metadata", "*.lif", "*.xlif"));
        var container = chooser.showOpenDialog(null);
        if (container == null)
            return;
        try {
            var client = new BridgeClient(baseUrl, token);
            var defaults = JsonParser.parseString(client.pipelineDefaults()).getAsJsonObject();
            var chosen = new FitDialog(defaults).prompt("Stitch and fit", "pipeline");
            if (chosen == null)
                return;
            var body = new JsonObject();
            body.addProperty("container", container.getAbsolutePath());
            body.add("params", chosen);
            String reply;
            try {
                reply = client.runPipeline(body.toString());
            } catch (java.io.IOException notFound) {
                if (!notFound.getMessage().contains("404"))
                    throw notFound;
                var folder = askForTiles(container);
                if (folder == null)
                    return;
                body.addProperty("tile_dir", folder.getAbsolutePath());
                reply = client.runPipeline(body.toString());
            }
            var started = JsonParser.parseString(reply).getAsJsonObject();
            String jobId = started.get("job").getAsString();
            int tiles = started.get("n_tiles").getAsInt();
            String outputDir = started.get("output_dir").getAsString();
            new BridgeJob(client, jobId, "FLIMKit: " + tiles + " tiles").watch(
                    status -> importProducts(qupath, client, jobId, outputDir),
                    problem -> {
                        if (problem == null)
                            Dialogs.showInfoNotification(getName(), "Cancelled");
                        else
                            Dialogs.showErrorMessage(getName(),
                                    "Stitch and fit failed\n\n" + problem);
                    });
        } catch (Exception e) {
            logger.error("Could not start the pipeline", e);
            Dialogs.showErrorMessage(getName(),
                    "Could not start stitching\n\n" + e.getMessage());
        }
    }

    private void importProducts(QuPathGUI qupath, BridgeClient client,
                                String jobId, String outputDir) {
        JsonArray products = new JsonArray();
        try {
            var result = JsonParser.parseString(client.jobResult(jobId)).getAsJsonObject();
            if (result.has("products") && result.get("products").isJsonArray())
                products = result.getAsJsonArray("products");
        } catch (Exception e) {
            logger.warn("Could not read the pipeline result", e);
        }
        var project = qupath.getProject();
        if (project == null || products.isEmpty()) {
            String why = project == null
                    ? "\n\nOpen a project and they can be added to it automatically."
                    : "";
            Dialogs.showInfoNotification(getName(),
                    "Finished. FLIMKit wrote its maps to\n" + outputDir + why);
            return;
        }
        var manifest = ProjectManifest.open(project);
        var added = new ArrayList<String>();
        var skipped = new ArrayList<String>();
        for (var element : products) {
            var product = element.getAsJsonObject();
            String imageId = product.get("image_id").getAsString();
            String unit = product.get("unit").getAsString();
            try {
                Path file = Path.of(product.get("file").getAsString());
                var entry = ProjectImporter.addToProject(project, file, imageId, unit);
                added.add(entry.getImageName());
                if (manifest != null)
                    manifest.recordImage(imageId, file.toString(), unit, outputDir);
            } catch (Exception e) {
                logger.warn("Could not add {}", imageId, e);
                skipped.add(imageId);
            }
        }
        try {
            project.syncChanges();
        } catch (IOException e) {
            logger.error("Could not save the project", e);
        }
        saveManifest(manifest);
        qupath.refreshProject();
        String message = added.isEmpty()
                ? "Finished, but nothing could be added from\n" + outputDir
                : "Added " + String.join(", ", added);
        if (!skipped.isEmpty())
            message += "\nNot added: " + String.join(", ", skipped);
        Dialogs.showInfoNotification(getName(), message);
    }

    private void sendAnnotations(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(getName(), "No image is open.");
            return;
        }
        var annotations = imageData.getHierarchy().getAnnotationObjects();
        if (annotations.isEmpty()) {
            Dialogs.showErrorMessage(getName(), "This image has no annotations to send.");
            return;
        }
        try {
            int received = new BridgeClient(baseUrl, token)
                    .postRois(toFeatureCollection(annotations));
            Dialogs.showInfoNotification(getName(),
                    "FLIMKit accepted " + received + " ROI(s)");
        } catch (Exception e) {
            logger.error("Could not send annotations", e);
            Dialogs.showErrorMessage(getName(), "Could not send annotations\n\n" + e.getMessage());
        }
    }

    private void fetchAnnotations(QuPathGUI qupath) {
        var imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(getName(), "No image is open.");
            return;
        }
        try {
            String geojson = new BridgeClient(baseUrl, token).fetchRois();
            List<PathObject> objects = GsonTools.parseObjectsFromGeoJSON(geojson);
            if (objects.isEmpty()) {
                Dialogs.showInfoNotification(getName(), "FLIMKit returned no ROIs");
                return;
            }
            imageData.getHierarchy().addObjects(objects);
            Dialogs.showInfoNotification(getName(),
                    "Added " + objects.size() + " ROI(s) from FLIMKit");
        } catch (Exception e) {
            logger.error("Could not fetch ROIs", e);
            Dialogs.showErrorMessage(getName(), "Could not fetch ROIs\n\n" + e.getMessage());
        }
    }

    static String toFeatureCollection(Collection<PathObject> annotations) {
        var gson = GsonTools.getInstance();
        var collection = new JsonObject();
        collection.addProperty("type", "FeatureCollection");
        var features = new JsonArray();
        for (PathObject annotation : annotations)
            features.add(JsonParser.parseString(gson.toJson(annotation)));
        collection.add("features", features);
        return gson.toJson(collection);
    }
}
