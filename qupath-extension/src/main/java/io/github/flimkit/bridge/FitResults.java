package io.github.flimkit.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.List;

public class FitResults {

    private FitResults() {}

    public static final String PREFIX = "FLIM: ";

    public static int applyToObjects(JsonObject payload, List<PathObject> annotations) {
        JsonArray results = payload.getAsJsonArray("results");
        int applied = 0;
        for (int i = 0; i < results.size() && i < annotations.size(); i++) {
            JsonObject result = results.get(i).getAsJsonObject();
            if (result.has("error"))
                continue;
            apply(result, annotations.get(i));
            applied++;
        }
        return applied;
    }

    static void apply(JsonObject result, PathObject annotation) {
        var list = annotation.getMeasurementList();
        putIfPresent(list, result, "tau_mean_ns", PREFIX + "tau mean (ns)");
        putIfPresent(list, result, "tau_mean_amp_ns", PREFIX + "tau mean amp (ns)");
        putIfPresent(list, result, "tau_mean_int_ns", PREFIX + "tau mean int (ns)");
        putIfPresent(list, result, "chi2_r", PREFIX + "chi2r");
        putIfPresent(list, result, "chi2_r_tail", PREFIX + "chi2r tail");
        putIfPresent(list, result, "photon_count", PREFIX + "photons");
        putIfPresent(list, result, "n_pixels", PREFIX + "pixels");
        putIfPresent(list, result, "n_exp", PREFIX + "components");
        putArray(list, result, "taus_ns", PREFIX + "tau");
        putArray(list, result, "fractions", PREFIX + "f");
        list.close();
    }

    private static void putIfPresent(qupath.lib.measurements.MeasurementList list,
                                     JsonObject result, String key, String label) {
        if (!result.has(key) || result.get(key).isJsonNull())
            return;
        list.put(label, result.get(key).getAsDouble());
    }

    private static void putArray(qupath.lib.measurements.MeasurementList list,
                                 JsonObject result, String key, String labelPrefix) {
        if (!result.has(key) || !result.get(key).isJsonArray())
            return;
        JsonArray values = result.getAsJsonArray(key);
        for (int i = 0; i < values.size(); i++)
            list.put(labelPrefix + (i + 1), values.get(i).getAsDouble());
    }

    public static List<String> errors(JsonObject payload) {
        var found = new ArrayList<String>();
        for (var element : payload.getAsJsonArray("results")) {
            JsonObject result = element.getAsJsonObject();
            if (result.has("error"))
                found.add(result.get("name").getAsString() + ": "
                        + result.get("error").getAsString());
        }
        return found;
    }
}
