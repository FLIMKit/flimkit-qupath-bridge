import qupath.lib.images.servers.ImageServerProvider

import java.awt.image.BufferedImage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Locale

def baseUrl = args[0]
def token = args[1]
def imageId = args[2]

def client = HttpClient.newHttpClient()
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
def width = server.getWidth()
def height = server.getHeight()
def image = server.readRegion(1.0, 0, 0, width, height)
def raster = image.getRaster()

double total = 0.0d
double peak = -Double.MAX_VALUE
int finite = 0
int nan = 0
for (int y = 0; y < height; y++) {
    for (int x = 0; x < width; x++) {
        double value = raster.getSampleFloat(x, y, 0)
        if (Double.isNaN(value)) {
            nan++
            continue
        }
        finite++
        total += value
        if (value > peak) {
            peak = value
        }
    }
}
def pixelType = server.getPixelType().toString()
server.close()
Files.deleteIfExists(file)
if (finite == 0) {
    peak = Double.NaN
}

println(String.format(
    Locale.US,
    'QUPATH_REAL_OK id=%s width=%d height=%d type=%s sum=%.6f max=%.9f finite=%d nan=%d unit=%s',
    imageId, width, height, pixelType, total, peak, finite, nan, unit))
