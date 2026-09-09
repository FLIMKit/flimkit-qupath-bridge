package io.github.flimkit.bridge;

import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ProjectImporter {

    private ProjectImporter() {}

    public static Path storeBesideProject(Project<BufferedImage> project, String imageId,
                                          Path downloaded, String suffix) throws IOException {
        Path projectPath = project.getPath();
        Path directory = projectPath == null
                ? Files.createTempDirectory("flimkit-")
                : projectPath.getParent().resolve("flimkit");
        Files.createDirectories(directory);
        Path target = directory.resolve(imageId + suffix);
        Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public static Path storeBesideProject(Project<BufferedImage> project,
                                          String imageId, Path downloaded) throws IOException {
        Path projectPath = project.getPath();
        Path directory = projectPath == null
                ? Files.createTempDirectory("flimkit-")
                : projectPath.getParent().resolve("flimkit");
        Files.createDirectories(directory);
        Path target = directory.resolve(imageId + ".tif");
        Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public static ProjectImageEntry<BufferedImage> addNamed(
            Project<BufferedImage> project, Path file, String name) throws IOException {
        var support = ImageServerProvider.getPreferredUriImageSupport(
                BufferedImage.class, file.toAbsolutePath().toString());
        if (support == null || support.getBuilders().isEmpty())
            throw new IOException("QuPath cannot open " + file);
        var entry = project.addImage(support.getBuilders().get(0));
        entry.setImageName(name);
        return entry;
    }

    public static ProjectImageEntry<BufferedImage> addToProject(
            Project<BufferedImage> project, Path file, String imageId, String unit)
            throws IOException {
        var support = ImageServerProvider.getPreferredUriImageSupport(
                BufferedImage.class, file.toAbsolutePath().toString());
        if (support == null || support.getBuilders().isEmpty())
            throw new IOException("QuPath cannot open " + file);
        var entry = project.addImage(support.getBuilders().get(0));
        entry.setImageName(label(imageId, unit));
        return entry;
    }

    /**
     * Adds a volume that may be a directory rather than a file, trying each
     * path QuPath might accept for it. Throws with every path tried if none
     * open, so the caller can say where the store is instead.
     */
    public static ProjectImageEntry<BufferedImage> addVolume(
            Project<BufferedImage> project, java.util.List<Path> candidates,
            String name) throws IOException {
        var refused = new java.util.ArrayList<String>();
        for (Path candidate : candidates) {
            if (!Files.exists(candidate)) {
                refused.add(candidate + " (not there)");
                continue;
            }
            try {
                var support = ImageServerProvider.getPreferredUriImageSupport(
                        BufferedImage.class, candidate.toAbsolutePath().toString());
                if (support == null || support.getBuilders().isEmpty()) {
                    refused.add(candidate.toString());
                    continue;
                }
                var entry = project.addImage(support.getBuilders().get(0));
                entry.setImageName(name);
                return entry;
            } catch (Exception e) {
                refused.add(candidate + " (" + e.getMessage() + ")");
            }
        }
        throw new IOException("QuPath could not open the volume. Tried:\n"
                + String.join("\n", refused));
    }

    public static String label(String imageId, String unit) {
        if (unit == null || unit.isBlank())
            return "FLIMKit " + imageId;
        return "FLIMKit " + imageId + " (" + unit + ")";
    }
}
