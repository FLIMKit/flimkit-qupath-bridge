package io.github.flimkit.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the products a z-stack fit job hands back. Kept apart from the menu
 * code so it can be exercised without a JavaFX toolkit.
 */
public class ZStackResults {

    private ZStackResults() {}

    public record Volume(String label, Path file, String groupDir, String format,
                         int nZ, List<String> channels, List<String> units,
                         List<Double> voxelSizeUm, List<Double> tausNs,
                         String zSeriesCsv, JsonObject pooled) {

        public boolean isZarr() {
            return "ome-zarr".equals(format);
        }

        /** Whether the bridge told us enough to fetch this over HTTP instead. */
        public boolean canBeFetched() {
            return groupDir != null && !groupDir.isBlank();
        }

        public double zStepUm() {
            return voxelSizeUm.isEmpty() ? 1.0 : voxelSizeUm.get(0);
        }

        public double pixelSizeUm() {
            return voxelSizeUm.size() < 3 ? 0.0 : voxelSizeUm.get(2);
        }

        public String describe() {
            var lines = new ArrayList<String>();
            lines.add("Fitted by FLIMKit as one z-stack: " + nZ + " slices, "
                    + "one lifetime per component shared by every slice.");
            if (!tausNs.isEmpty()) {
                var parts = new ArrayList<String>();
                for (int i = 0; i < tausNs.size(); i++)
                    parts.add(String.format("tau%d = %.4f ns", i + 1, tausNs.get(i)));
                lines.add("Locked lifetimes: " + String.join(", ", parts));
            }
            if (!channels.isEmpty())
                lines.add("Channels: " + String.join(", ", labelled()));
            lines.addAll(pooledLines());
            if (voxelSizeUm.size() == 3)
                lines.add(String.format("Voxel size: %.4f x %.4f x %.4f um (z, y, x)",
                        voxelSizeUm.get(0), voxelSizeUm.get(1), voxelSizeUm.get(2)));
            lines.add("Volume: " + file);
            if (zSeriesCsv != null)
                lines.add("Per-slice summary: " + zSeriesCsv);
            return String.join("\n", lines);
        }

        /**
         * What the pooled fit was, not just what it decided. The lifetimes
         * above came from one fit of the decay summed over every slice, and
         * how good that fit was is the thing that says whether to trust them.
         */
        public List<String> pooledLines() {
            var lines = new ArrayList<String>();
            if (pooled == null)
                return lines;
            if (pooled.has("nexp"))
                lines.add("Components: " + pooled.get("nexp").getAsString());
            if (pooled.has("total_pooled_photons"))
                lines.add(String.format("Pooled photons: %,.0f",
                        pooled.get("total_pooled_photons").getAsDouble()));
            if (pooled.has("estimate_irf"))
                lines.add("IRF: " + pooled.get("estimate_irf").getAsString());
            if (pooled.has("calibrated_chi2_pearson"))
                lines.add(String.format("Pooled calibrated chi-squared: %.3f",
                        pooled.get("calibrated_chi2_pearson").getAsDouble()));
            if (pooled.has("user_supplied_tau")
                    && pooled.get("user_supplied_tau").getAsBoolean())
                lines.add("Lifetimes were supplied, not fitted");
            return lines;
        }

        public List<String> labelled() {
            var found = new ArrayList<String>();
            for (int i = 0; i < channels.size(); i++) {
                String unit = i < units.size() ? units.get(i) : "";
                found.add(unit == null || unit.isBlank()
                        ? channels.get(i)
                        : channels.get(i) + " (" + unit + ")");
            }
            return found;
        }
    }

    public static List<Volume> volumes(JsonObject result) {
        var found = new ArrayList<Volume>();
        if (result == null || !result.has("products")
                || !result.get("products").isJsonArray())
            return found;
        for (var element : result.getAsJsonArray("products")) {
            if (!element.isJsonObject())
                continue;
            var product = element.getAsJsonObject();
            if (!product.has("file"))
                continue;
            found.add(new Volume(
                    string(product, "image_id", "z-stack"),
                    Path.of(product.get("file").getAsString()),
                    string(product, "group_dir", null),
                    string(product, "format", "ome-tiff"),
                    product.has("n_z") ? product.get("n_z").getAsInt() : 0,
                    strings(product, "channels"),
                    strings(product, "units"),
                    numbers(product, "voxel_size_um"),
                    numbers(product, "taus_ns"),
                    string(product, "z_series_csv", null),
                    product.has("pooled") && product.get("pooled").isJsonObject()
                            ? product.getAsJsonObject("pooled") : null));
        }
        return found;
    }

    /** The stacks a run reported but could not turn into a volume. */
    public static List<String> problems(JsonObject result) {
        var found = new ArrayList<String>();
        if (result == null || !result.has("stacks") || !result.get("stacks").isJsonArray())
            return found;
        for (var element : result.getAsJsonArray("stacks")) {
            if (!element.isJsonObject())
                continue;
            var stack = element.getAsJsonObject();
            if (stack.has("error"))
                found.add(string(stack, "label", "a stack") + ": "
                        + stack.get("error").getAsString());
        }
        return found;
    }

    static String string(JsonObject payload, String key, String fallback) {
        if (payload == null || !payload.has(key) || payload.get(key).isJsonNull())
            return fallback;
        return payload.get(key).getAsString();
    }

    static List<String> strings(JsonObject payload, String key) {
        var found = new ArrayList<String>();
        if (payload == null || !payload.has(key) || !payload.get(key).isJsonArray())
            return found;
        for (var element : payload.getAsJsonArray(key))
            found.add(element.getAsString());
        return found;
    }

    static List<Double> numbers(JsonObject payload, String key) {
        var found = new ArrayList<Double>();
        if (payload == null || !payload.has(key) || !payload.get(key).isJsonArray())
            return found;
        JsonArray values = payload.getAsJsonArray(key);
        for (var element : values)
            found.add(element.getAsDouble());
        return found;
    }

    /**
     * Where QuPath might be able to open an OME-Zarr store from. Bio-Formats
     * takes the store directory in some builds and the metadata file inside it
     * in others, and the file is named differently between OME-Zarr 0.4 and
     * 0.5, so the caller tries these in turn. A plain file is only ever itself.
     */
    public static List<Path> openableAs(Path file, String format) {
        var candidates = new ArrayList<Path>();
        candidates.add(file);
        if ("ome-zarr".equals(format)) {
            candidates.add(file.resolve("zarr.json"));
            candidates.add(file.resolve(".zattrs"));
            candidates.add(file.resolve("OME").resolve("METADATA.ome.xml"));
        }
        return candidates;
    }
}
