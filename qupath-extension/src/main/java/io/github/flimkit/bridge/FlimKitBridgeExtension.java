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
                menuItem("Fit ROI decays...", () -> fitRois(qupath)),
                null,
                menuItem("Send annotations to FLIMKit", () -> sendAnnotations(qupath)),
                menuItem("Fetch ROIs from FLIMKit", () -> fetchAnnotations(qupath)));
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
        qupath.refreshProject();
        String message = "Added " + String.join(", ", added);
        if (!skipped.isEmpty())
            message += "\nNot available: " + String.join(", ", skipped);
        Dialogs.showInfoNotification(getName(), message);
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
            Dialogs.showErrorMessage(getName(), "Draw an annotation first.");
            return;
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
            var reply = JsonParser.parseString(
                    client.fitRois(bridged.getDatasetId(), body.toString()))
                    .getAsJsonObject();
            int applied = FitResults.applyToObjects(reply, selected);
            imageData.getHierarchy().fireObjectMeasurementsChangedEvent(this, selected);
            var failures = FitResults.errors(reply);
            String message = "Fitted " + applied + " region(s)";
            if (!failures.isEmpty())
                message += "\n\nNot fitted:\n" + String.join("\n", failures);
            Dialogs.showInfoNotification(getName(), message);
        } catch (Exception e) {
            logger.error("ROI fitting failed", e);
            Dialogs.showErrorMessage(getName(), "Could not fit\n\n" + e.getMessage());
        }
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
