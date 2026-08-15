import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

import qupath.lib.images.servers.ImageServerProvider
import qupath.lib.io.GsonTools
import qupath.lib.objects.PathObjects
import qupath.lib.regions.ImagePlane
import qupath.lib.roi.ROIs

import java.awt.image.BufferedImage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale

def baseUrl = args[0]
def token = args[1]
def client = HttpClient.newHttpClient()

def fetchImage = { String imageId ->
    def request = HttpRequest.newBuilder()
        .uri(URI.create("${baseUrl}/v1/images/${imageId}.tif"))
        .header('Authorization', "Bearer ${token}")
        .GET()
        .build()
    def response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    if (response.statusCode() != 200) {
        throw new IllegalStateException("GET ${imageId} returned ${response.statusCode()}")
    }
    def unit = response.headers().firstValue('X-FLIMKit-Value-Unit').orElse('')
    def file = Files.createTempFile("flimkit-${imageId}-", '.tif')
    Files.write(file, response.body())
    def server = ImageServerProvider.buildServer(
        file.toAbsolutePath().toString(), BufferedImage.class)
    if (server.getWidth() != 7 || server.getHeight() != 5) {
        throw new IllegalStateException(
            "${imageId} was ${server.getWidth()}x${server.getHeight()}, expected 7x5")
    }
    def image = server.readRegion(1.0, 0, 0, server.getWidth(), server.getHeight())
    def value = image.getRaster().getSampleFloat(6, 4, 0)
    server.close()
    Files.deleteIfExists(file)
    return [value: value, unit: unit]
}

def intensity = fetchImage('intensity')
def lifetime = fetchImage('lifetime')
if (intensity.value != 34.0f || Math.abs(lifetime.value - 3.4f) > 1e-6f) {
    throw new IllegalStateException(
        "pixel mismatch: intensity=${intensity.value}, lifetime=${lifetime.value}")
}
println(String.format(
    Locale.US,
    'QUPATH_IMAGES_OK intensity=%.1f lifetime=%.1f lifetime_unit=%s',
    intensity.value,
    lifetime.value,
    lifetime.unit,
))

def roi = ROIs.createPolygonROI(
    [1.25, 4.5, 3.0] as double[],
    [2.5, 2.5, 4.0] as double[],
    ImagePlane.getDefaultPlane())
def annotation = PathObjects.createAnnotationObject(roi)
annotation.setName('QuPath polygon')

def gson = GsonTools.getInstance()
def collection = new JsonObject()
collection.addProperty('type', 'FeatureCollection')
def features = new JsonArray()
features.add(JsonParser.parseString(gson.toJson(annotation)))
collection.add('features', features)
def geojson = gson.toJson(collection)

def postRequest = HttpRequest.newBuilder()
    .uri(URI.create("${baseUrl}/v1/rois"))
    .header('Authorization', "Bearer ${token}")
    .header('Content-Type', 'application/geo+json')
    .POST(HttpRequest.BodyPublishers.ofString(geojson, StandardCharsets.UTF_8))
    .build()
def postResponse = client.send(
    postRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
if (postResponse.statusCode() != 200) {
    throw new IllegalStateException(
        "POST ROIs returned ${postResponse.statusCode()}: ${postResponse.body()}")
}
if (!postResponse.body().contains('"received_features": 1')) {
    throw new IllegalStateException("unexpected POST response: ${postResponse.body()}")
}
println('QUPATH_ROI_POST_OK features=1')

def getRequest = HttpRequest.newBuilder()
    .uri(URI.create("${baseUrl}/v1/rois"))
    .header('Authorization', "Bearer ${token}")
    .GET()
    .build()
def getResponse = client.send(
    getRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
if (getResponse.statusCode() != 200) {
    throw new IllegalStateException("GET ROIs returned ${getResponse.statusCode()}")
}
def imported = GsonTools.parseObjectsFromGeoJSON(getResponse.body())
if (imported.isEmpty()) {
    throw new IllegalStateException('no objects parsed from the FLIMKit collection')
}
imported.each { object ->
    if (object.getROI() == null) {
        throw new IllegalStateException("imported object has no ROI: ${object}")
    }
}
println("QUPATH_IMPORT_OK objects=${imported.size()}")
