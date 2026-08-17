package io.github.flimkit.bridge;

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
