package io.github.flimkit.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class PhasorWindow {

    private static final Logger logger = LoggerFactory.getLogger(PhasorWindow.class);

    private static final double G_MIN = -0.05;
    private static final double G_MAX = 1.05;
    private static final double S_MIN = -0.05;
    private static final double S_MAX = 0.65;
    private static final int BINS = 256;
    private static final Color[] COLOURS = {
        Color.web("#FF6B6B"), Color.web("#4ECDC4"), Color.web("#FFE66D"),
        Color.web("#95E1D3"), Color.web("#C7CEEA"), Color.web("#FF8C42"),
    };

    private final QuPathGUI qupath;
    private final BridgeClient client;
    private final String datasetId;
    private final ImageData<BufferedImage> imageData;

    private final Canvas canvas = new Canvas(520, 380);
    private final ListView<String> cursorList = new ListView<>();
    private final List<Cursor> cursors = new ArrayList<>();

    private int[] counts = new int[0];
    private int maxCount = 1;
    private int binning = 1;
    private Cursor dragging;

    public PhasorWindow(QuPathGUI qupath, BridgeClient client, String datasetId,
                        ImageData<BufferedImage> imageData) {
        this.qupath = qupath;
        this.client = client;
        this.datasetId = datasetId;
        this.imageData = imageData;
    }

    public void show() throws Exception {
        var summary = JsonParser.parseString(
                client.phasorSummary(datasetId)).getAsJsonObject();
        binning = summary.get("binning").isJsonNull()
                ? 1 : summary.get("binning").getAsInt();
        loadDensity();

        var stage = new Stage();
        stage.setTitle("FLIMKit phasor");
        var root = new BorderPane();
        root.setCenter(canvas);

        var side = new VBox(6);
        side.setPadding(new Insets(8));
        side.getChildren().add(new Label(String.format(
                "%.2f MHz, binning %d", summary.get("frequency_hz").getAsDouble(),
                binning)));
        side.getChildren().add(cursorList);

        var add = new Button("Add cursor");
        add.setOnAction(e -> {
            if (cursors.size() >= COLOURS.length) {
                Dialogs.showErrorMessage("FLIMKit phasor",
                        "Six cursors is the limit, matching FLIMKit's palette.");
                return;
            }
            cursors.add(new Cursor("c" + (cursors.size() + 1), 0.5, 0.3, 0.05));
            refresh();
        });
        var remove = new Button("Remove selected");
        remove.setOnAction(e -> {
            int index = cursorList.getSelectionModel().getSelectedIndex();
            if (index >= 0 && index < cursors.size()) {
                cursors.remove(index);
                refresh();
            }
        });
        var create = new Button("Create annotations");
        create.setOnAction(e -> createAnnotations());
        side.getChildren().addAll(add, remove, create);
        root.setRight(side);

        canvas.setOnMousePressed(e -> {
            dragging = nearest(e.getX(), e.getY());
            if (dragging == null && cursors.size() < COLOURS.length) {
                dragging = new Cursor("c" + (cursors.size() + 1),
                        toG(e.getX()), toS(e.getY()), 0.05);
                cursors.add(dragging);
            }
            refresh();
        });
        canvas.setOnMouseDragged(e -> {
            if (dragging != null) {
                dragging.g = toG(e.getX());
                dragging.s = toS(e.getY());
                draw();
            }
        });
        canvas.setOnMouseReleased(e -> {
            dragging = null;
            refresh();
        });

        stage.setScene(new Scene(root));
        stage.show();
        refresh();
    }

    private void loadDensity() throws Exception {
        var payload = JsonParser.parseString(
                client.phasorPoints(datasetId, BINS)).getAsJsonObject();
        byte[] raw = Base64.getDecoder().decode(payload.get("counts").getAsString());
        counts = new int[raw.length / 4];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = (raw[i * 4] & 0xFF)
                    | ((raw[i * 4 + 1] & 0xFF) << 8)
                    | ((raw[i * 4 + 2] & 0xFF) << 16)
                    | ((raw[i * 4 + 3] & 0xFF) << 24);
        }
        maxCount = Math.max(1, payload.get("max_count").getAsInt());
    }

    private double toX(double g) {
        return (g - G_MIN) / (G_MAX - G_MIN) * canvas.getWidth();
    }

    private double toY(double s) {
        return canvas.getHeight() - (s - S_MIN) / (S_MAX - S_MIN) * canvas.getHeight();
    }

    private double toG(double x) {
        return G_MIN + x / canvas.getWidth() * (G_MAX - G_MIN);
    }

    private double toS(double y) {
        return S_MIN + (canvas.getHeight() - y) / canvas.getHeight() * (S_MAX - S_MIN);
    }

    private Cursor nearest(double x, double y) {
        for (var cursor : cursors) {
            double dx = toX(cursor.g) - x;
            double dy = toY(cursor.s) - y;
            if (Math.hypot(dx, dy) < 12)
                return cursor;
        }
        return null;
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#101418"));
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        double cellW = canvas.getWidth() / BINS;
        double cellH = canvas.getHeight() / BINS;
        double logMax = Math.log1p(maxCount);
        for (int row = 0; row < BINS; row++) {
            for (int col = 0; col < BINS; col++) {
                int count = counts[row * BINS + col];
                if (count == 0)
                    continue;
                double level = Math.log1p(count) / logMax;
                g.setFill(Color.hsb(240 - 240 * level, 0.85, 0.25 + 0.75 * level));
                g.fillRect(col * cellW, canvas.getHeight() - (row + 1) * cellH,
                        Math.ceil(cellW), Math.ceil(cellH));
            }
        }

        g.setStroke(Color.web("#8899AA"));
        g.setLineWidth(1.2);
        double previousX = toX(0), previousY = toY(0);
        for (int i = 1; i <= 180; i++) {
            double angle = Math.PI * i / 180.0;
            double gg = 0.5 + 0.5 * Math.cos(angle);
            double ss = 0.5 * Math.sin(angle);
            double x = toX(gg), y = toY(ss);
            g.strokeLine(previousX, previousY, x, y);
            previousX = x;
            previousY = y;
        }

        for (int i = 0; i < cursors.size(); i++) {
            var cursor = cursors.get(i);
            g.setStroke(COLOURS[i % COLOURS.length]);
            g.setLineWidth(2);
            double rx = cursor.radius / (G_MAX - G_MIN) * canvas.getWidth();
            double ry = cursor.radius / (S_MAX - S_MIN) * canvas.getHeight();
            g.strokeOval(toX(cursor.g) - rx, toY(cursor.s) - ry, rx * 2, ry * 2);
        }
    }

    private void refresh() {
        draw();
        cursorList.getItems().clear();
        if (cursors.isEmpty())
            return;
        try {
            var reply = JsonParser.parseString(
                    client.phasorMask(datasetId, requestBody(false))).getAsJsonObject();
            for (var element : reply.getAsJsonArray("cursors")) {
                var entry = element.getAsJsonObject();
                cursorList.getItems().add(entry.get("id").getAsString() + ": "
                        + entry.get("n_pixels").getAsInt() + " px");
            }
        } catch (Exception e) {
            logger.warn("Could not count phasor pixels", e);
        }
    }

    String requestBody(boolean labels) {
        var body = new JsonObject();
        var array = new JsonArray();
        for (var cursor : cursors) {
            var entry = new JsonObject();
            entry.addProperty("id", cursor.id);
            entry.addProperty("center_g", cursor.g);
            entry.addProperty("center_s", cursor.s);
            entry.addProperty("radius", cursor.radius);
            array.add(entry);
        }
        body.add("cursors", array);
        body.addProperty("min_photons", 1.0);
        if (labels)
            body.addProperty("output", "labels");
        return body.toString();
    }

    private void createAnnotations() {
        if (cursors.isEmpty()) {
            Dialogs.showErrorMessage("FLIMKit phasor", "Add a cursor first.");
            return;
        }
        try {
            var reply = JsonParser.parseString(
                    client.phasorMask(datasetId, requestBody(true))).getAsJsonObject();
            var names = new ArrayList<String>();
            for (var cursor : cursors)
                names.add("Phasor " + cursor.id);
            var objects = PhasorAnnotations.fromBase64(
                    reply.get("labels").getAsString(),
                    reply.get("width").getAsInt(),
                    reply.get("height").getAsInt(),
                    reply.get("binning").isJsonNull() ? 1 : reply.get("binning").getAsInt(),
                    names,
                    imageData.getServer().getPath());
            if (objects.isEmpty()) {
                Dialogs.showInfoNotification("FLIMKit phasor",
                        "No pixels fell inside the cursors.");
                return;
            }
            imageData.getHierarchy().addObjects(objects);
            Dialogs.showInfoNotification("FLIMKit phasor",
                    "Added " + objects.size() + " annotation(s)");
        } catch (Exception e) {
            logger.error("Could not create phasor annotations", e);
            Dialogs.showErrorMessage("FLIMKit phasor",
                    "Could not create annotations\n\n" + e.getMessage());
        }
    }

    static final class Cursor {
        final String id;
        double g;
        double s;
        double radius;

        Cursor(String id, double g, double s, double radius) {
            this.id = id;
            this.g = g;
            this.s = s;
            this.radius = radius;
        }
    }
}
