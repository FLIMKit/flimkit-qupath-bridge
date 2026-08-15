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

import javafx.scene.control.MenuItem;

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
                menuItem("Connect...", () -> promptForConnection(qupath)),
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
