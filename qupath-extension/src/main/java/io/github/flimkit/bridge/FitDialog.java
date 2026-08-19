package io.github.flimkit.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.Node;

import javafx.stage.FileChooser;

import java.util.LinkedHashMap;
import java.util.Map;

public class FitDialog {

    private final Map<String, Node> controls = new LinkedHashMap<>();
    private final JsonObject values;
    private final JsonArray schema;

    public FitDialog(JsonObject defaults) {
        this.values = defaults.getAsJsonObject("values");
        this.schema = defaults.getAsJsonArray("schema");
    }

    public JsonObject prompt(String title, String mode) {
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle(title);
        dialog.setHeaderText("Fit settings inherited from FLIMKit; "
                + "only the fields below are overridden.");
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        int row = 0;
        for (var element : schema) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("advanced").getAsBoolean())
                continue;
            if (!appliesTo(entry, mode))
                continue;
            String key = entry.get("key").getAsString();
            Node control = controlFor(entry, key);
            if (control == null)
                continue;
            controls.put(key, control);
            grid.add(new Label(entry.get("label").getAsString()), 0, row);
            grid.add(control, 1, row);
            row++;
        }
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(
                new ButtonType("Fit", ButtonBar.ButtonData.OK_DONE),
                ButtonType.CANCEL);
        var chosen = dialog.showAndWait();
        if (chosen.isEmpty()
                || chosen.get().getButtonData() != ButtonBar.ButtonData.OK_DONE)
            return null;
        return collect();
    }

    static boolean appliesTo(JsonObject entry, String mode) {
        for (var applies : entry.getAsJsonArray("applies_to")) {
            if (applies.getAsString().equals(mode))
                return true;
        }
        return false;
    }

    private Node controlFor(JsonObject entry, String key) {
        if (!values.has(key) || values.get(key).isJsonNull())
            return null;
        String type = entry.get("type").getAsString();
        switch (type) {
            case "int" -> {
                int min = entry.has("min") ? entry.get("min").getAsInt() : 0;
                int max = entry.has("max") ? entry.get("max").getAsInt() : 1000;
                return typable(new Spinner<Integer>(min, max, values.get(key).getAsInt()));
            }
            case "float" -> {
                double min = entry.has("min") ? entry.get("min").getAsDouble() : 0;
                double max = entry.has("max") ? entry.get("max").getAsDouble() : 1000;
                return typable(new Spinner<Double>(min, max, values.get(key).getAsDouble(), 0.1));
            }
            case "bool" -> {
                var box = new CheckBox();
                box.setSelected(values.get(key).getAsBoolean());
                return box;
            }
            case "choice" -> {
                var box = new ChoiceBox<String>();
                for (var choice : entry.getAsJsonArray("choices"))
                    box.getItems().add(choice.getAsString());
                box.setValue(values.get(key).getAsString());
                return box;
            }
            case "path" -> {
                var field = new TextField(values.get(key).getAsString());
                field.setPromptText("FLIMKit's own default");
                field.setPrefColumnCount(24);
                HBox.setHgrow(field, Priority.ALWAYS);
                var browse = new Button("Choose...");
                browse.setOnAction(event -> {
                    var chooser = new FileChooser();
                    chooser.setTitle(entry.get("label").getAsString());
                    var picked = chooser.showOpenDialog(field.getScene() == null
                            ? null : field.getScene().getWindow());
                    if (picked != null)
                        field.setText(picked.getAbsolutePath());
                });
                var row = new HBox(6, field, browse);
                row.setUserData(field);
                return row;
            }
            default -> {
                return null;
            }
        }
    }

    private static <T> Spinner<T> typable(Spinner<T> spinner) {
        spinner.setEditable(true);
        spinner.focusedProperty().addListener((observed, had, has) -> {
            if (!has)
                commit(spinner);
        });
        return spinner;
    }

    private static <T> void commit(Spinner<T> spinner) {
        var factory = spinner.getValueFactory();
        if (factory == null || factory.getConverter() == null)
            return;
        var converter = factory.getConverter();
        String typed = spinner.getEditor().getText();
        try {
            factory.setValue(converter.fromString(typed));
        } catch (RuntimeException ignored) {
            spinner.getEditor().setText(converter.toString(factory.getValue()));
        }
    }

    JsonObject collect() {
        var chosen = new JsonObject();
        for (var pair : controls.entrySet()) {
            Node control = pair.getValue();
            if (control instanceof HBox row && row.getUserData() instanceof TextField field) {
                String typed = field.getText() == null ? "" : field.getText().trim();
                if (!typed.isBlank())
                    chosen.addProperty(pair.getKey(), typed);
            }
            else if (control instanceof Spinner<?> spinner) {
                commit(spinner);
                chosen.addProperty(pair.getKey(), (Number) spinner.getValue());
            }
            else if (control instanceof CheckBox box)
                chosen.addProperty(pair.getKey(), box.isSelected());
            else if (control instanceof ChoiceBox<?> box)
                chosen.addProperty(pair.getKey(), String.valueOf(box.getValue()));
        }
        return chosen;
    }
}
