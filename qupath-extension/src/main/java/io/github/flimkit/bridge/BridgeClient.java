package io.github.flimkit.bridge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class BridgeClient {

    private final String baseUrl;
    private final String token;
    private final HttpClient client;

    public BridgeClient(String baseUrl, String token) {
        this(baseUrl, token, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    BridgeClient(String baseUrl, String token, HttpClient client) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.token = token;
        this.client = client;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofMinutes(2));
    }

    public String status() throws IOException, InterruptedException {
        var response = client.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl + "/v1/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IOException("status returned " + response.statusCode());
        return response.body();
    }

    public FetchedImage fetchImage(String imageId) throws IOException, InterruptedException {
        var response = client.send(
                request("/v1/images/" + imageId + ".tif").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200)
            throw new IOException("GET " + imageId + " returned " + response.statusCode());
        String unit = response.headers().firstValue("X-FLIMKit-Value-Unit").orElse("");
        Path file = Files.createTempFile("flimkit-" + imageId + "-", ".tif");
        Files.write(file, response.body());
        return new FetchedImage(imageId, file, unit);
    }

    public String formats() throws IOException, InterruptedException {
        var response = client.send(
                request("/v1/formats").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IOException("GET formats returned " + response.statusCode());
        return response.body();
    }

    public String identify(String path) throws IOException, InterruptedException {
        String body = "{\"path\":" + quote(path) + "}";
        var response = client.send(
                request("/v1/identify")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IOException("POST identify returned " + response.statusCode()
                    + " for " + path);
        return response.body();
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    public String openDataset(String path) throws IOException, InterruptedException {
        String body = "{\"path\":" + quote(path) + "}";
        var response = client.send(
                request("/v1/datasets")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IOException("POST datasets returned " + response.statusCode()
                    + " for " + path);
        return response.body();
    }

    public void closeDataset(String datasetId) throws IOException, InterruptedException {
        client.send(
                request("/v1/datasets/" + datasetId).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    public String planes(String datasetId) throws IOException, InterruptedException {
        var response = client.send(
                request("/v1/datasets/" + datasetId + "/planes").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IOException("GET planes returned " + response.statusCode());
        return response.body();
    }

    public byte[] planeTiff(String datasetId, String plane, int x, int y, int w, int h)
            throws IOException, InterruptedException {
        String path = "/v1/datasets/" + datasetId + "/planes/" + plane + ".tif"
                + "?x=" + x + "&y=" + y + "&w=" + w + "&h=" + h;
        var response = client.send(
                request(path).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200)
            throw new IOException("GET plane " + plane + " returned " + response.statusCode());
        return response.body();
    }

    public String fetchRois() throws IOException, InterruptedException {
        var response = client.send(
                request("/v1/rois").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IOException("GET ROIs returned " + response.statusCode());
        return response.body();
    }

    public int postRois(String geojson) throws IOException, InterruptedException {
        var response = client.send(
                request("/v1/rois")
                        .header("Content-Type", "application/geo+json")
                        .POST(HttpRequest.BodyPublishers.ofString(geojson, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IOException("POST ROIs returned " + response.statusCode()
                    + ": " + response.body());
        return parseReceivedFeatures(response.body());
    }

    static int parseReceivedFeatures(String body) throws IOException {
        int key = body.indexOf("\"received_features\"");
        if (key < 0)
            throw new IOException("unexpected POST response: " + body);
        int colon = body.indexOf(':', key);
        if (colon < 0)
            throw new IOException("unexpected POST response: " + body);
        StringBuilder digits = new StringBuilder();
        for (int i = colon + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (Character.isDigit(c))
                digits.append(c);
            else if (digits.length() > 0)
                break;
            else if (!Character.isWhitespace(c))
                throw new IOException("unexpected POST response: " + body);
        }
        if (digits.length() == 0)
            throw new IOException("unexpected POST response: " + body);
        return Integer.parseInt(digits.toString());
    }

    public record FetchedImage(String imageId, Path file, String valueUnit) {}
}
