package io.github.flimkit.bridge;

import com.google.gson.JsonObject;

import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class PhasorAnnotations {

    private PhasorAnnotations() {}

    public static final String PREFIX = "Phasor: ";

    public static void applyStats(JsonObject entry, PathObject annotation) {
        var list = annotation.getMeasurementList();
        put(list, entry, "tau_phi_ns", PREFIX + "tau phi (ns)");
        put(list, entry, "tau_mod_ns", PREFIX + "tau mod (ns)");
        put(list, entry, "tau_phi_min_ns", PREFIX + "tau phi min (ns)");
        put(list, entry, "tau_phi_max_ns", PREFIX + "tau phi max (ns)");
        put(list, entry, "mean_g", PREFIX + "G");
        put(list, entry, "mean_s", PREFIX + "S");
        put(list, entry, "photons", PREFIX + "photons");
        put(list, entry, "n_pixels", PREFIX + "pixels");
        list.close();
    }

    private static void put(qupath.lib.measurements.MeasurementList list,
                            JsonObject entry, String key, String label) {
        if (entry == null || !entry.has(key) || entry.get(key).isJsonNull())
            return;
        list.put(label, entry.get(key).getAsDouble());
    }

    /**
     * Turns a phasor label image into QuPath annotations.
     *
     * The label image is at the phasor resolution, which is the full image
     * divided by the binning FLIMKit used. Tracing with downsample = binning is
     * what puts the resulting ROIs back into full-resolution image coordinates.
     */
    public static List<PathObject> fromLabels(byte[] labels, int width, int height,
                                              int binning, List<String> names,
                                              String serverPath) {
        if (labels.length != width * height)
            throw new IllegalArgumentException(
                    "label image is " + labels.length + " bytes, expected "
                            + width * height + " for " + width + "x" + height);
        if (binning < 1)
            throw new IllegalArgumentException("binning must be at least 1");
        float[] pixels = new float[labels.length];
        int highest = 0;
        for (int i = 0; i < labels.length; i++) {
            int value = labels[i] & 0xFF;
            pixels[i] = value;
            if (value > highest)
                highest = value;
        }
        var image = SimpleImages.createFloatImage(pixels, width, height);
        var request = RegionRequest.createInstance(
                serverPath, binning, 0, 0, width * binning, height * binning);
        var found = new ArrayList<PathObject>();
        for (int label = 1; label <= highest; label++) {
            ROI roi = ContourTracing.createTracedROI(
                    image, label - 0.5, label + 0.5, request);
            if (roi == null || roi.isEmpty())
                continue;
            var annotation = PathObjects.createAnnotationObject(roi);
            String name = label <= names.size() ? names.get(label - 1)
                    : "Phasor cursor " + label;
            annotation.setName(name);
            annotation.setPathClass(PathClass.fromString("Phasor"));
            found.add(annotation);
        }
        return found;
    }

    public static List<PathObject> fromBase64(String encoded, int width, int height,
                                              int binning, List<String> names,
                                              String serverPath) {
        return fromLabels(Base64.getDecoder().decode(encoded), width, height,
                binning, names, serverPath);
    }
}
