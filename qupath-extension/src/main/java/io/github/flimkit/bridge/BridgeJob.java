package io.github.flimkit.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class BridgeJob {

    private static final Logger logger = LoggerFactory.getLogger(BridgeJob.class);

    private static final long POLL_MILLIS = 500;

    private final BridgeClient client;
    private final String jobId;
    private final String title;

    private Stage stage;
    private Label message;
    private ProgressBar bar;
    private volatile boolean cancelling;

    public BridgeJob(BridgeClient client, String jobId, String title) {
        this.client = client;
        this.jobId = jobId;
        this.title = title;
    }

    public void watch(Consumer<JsonObject> onDone, Consumer<String> onFailed) {
        show();
        var thread = new Thread(() -> poll(onDone, onFailed), "flimkit-job-" + jobId);
        thread.setDaemon(true);
        thread.start();
    }

    private void show() {
        stage = new Stage();
        stage.setTitle(title);
        message = new Label("Starting...");
        message.setWrapText(true);
        message.setMinWidth(360);
        bar = new ProgressBar();
        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setMaxWidth(Double.MAX_VALUE);
        var cancel = new Button("Cancel");
        cancel.setOnAction(event -> requestCancel(cancel));
        var box = new VBox(10, message, bar, cancel);
        box.setPadding(new Insets(14));
        stage.setScene(new Scene(box));
        stage.setOnCloseRequest(event -> requestCancel(cancel));
        stage.show();
    }

    private void requestCancel(Button cancel) {
        if (cancelling)
            return;
        cancelling = true;
        cancel.setDisable(true);
        message.setText("Cancelling, this finishes the tile in progress...");
        var thread = new Thread(() -> {
            try {
                client.cancelJob(jobId);
            } catch (Exception e) {
                logger.warn("Could not cancel {}: {}", jobId, e.getMessage());
            }
        }, "flimkit-cancel-" + jobId);
        thread.setDaemon(true);
        thread.start();
    }

    private void poll(Consumer<JsonObject> onDone, Consumer<String> onFailed) {
        while (true) {
            JsonObject status;
            try {
                status = JsonParser.parseString(client.jobStatus(jobId)).getAsJsonObject();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                finish(() -> onFailed.accept("Lost contact with FLIMKit\n\n" + e.getMessage()));
                return;
            }
            String state = text(status, "state", "running");
            Platform.runLater(() -> render(status));
            if (state.equals("done")) {
                finish(() -> onDone.accept(status));
                return;
            }
            if (state.equals("cancelled")) {
                finish(() -> onFailed.accept(null));
                return;
            }
            if (state.equals("error")) {
                finish(() -> onFailed.accept(errorText(status)));
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void render(JsonObject status) {
        JsonObject progress = status.has("progress") && status.get("progress").isJsonObject()
                ? status.getAsJsonObject("progress")
                : null;
        double current = number(progress, "current");
        double total = number(progress, "total");
        String note = text(status, "message", "");
        if (total > 0) {
            bar.setProgress(number(progress, "fraction"));
            message.setText(note.isBlank()
                    ? String.format("%.0f of %.0f", current, total)
                    : String.format("%s  (%.0f of %.0f)", note, current, total));
        } else if (!note.isBlank()) {
            message.setText(note);
        }
    }

    private void finish(Runnable callback) {
        Platform.runLater(() -> {
            if (stage != null)
                stage.close();
            callback.run();
        });
    }

    private static String errorText(JsonObject status) {
        if (!status.has("error") || status.get("error").isJsonNull())
            return "FLIMKit reported an error with no detail";
        var error = status.getAsJsonObject("error");
        return text(error, "type", "Error") + ": " + text(error, "message", "");
    }

    private static String text(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
            return fallback;
        return object.get(key).getAsString();
    }

    private static double number(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull())
            return 0;
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
