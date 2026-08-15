import qupath.lib.io.GsonTools

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

def baseUrl = args[0]
def token = args[1]

def client = HttpClient.newHttpClient()
def request = HttpRequest.newBuilder()
    .uri(URI.create("${baseUrl}/v1/rois"))
    .header('Authorization', "Bearer ${token}")
    .GET()
    .build()
def response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
if (response.statusCode() != 200) {
    throw new IllegalStateException("GET ROIs returned ${response.statusCode()}")
}

def objects = GsonTools.parseObjectsFromGeoJSON(response.body())
int withRoi = 0
int empty = 0
objects.each { object ->
    def roi = object.getROI()
    if (roi == null) {
        empty++
        return
    }
    if (roi.isEmpty()) {
        empty++
        return
    }
    withRoi++
}

println("QUPATH_PARSE_OK objects=${objects.size()} with_roi=${withRoi} empty=${empty}")
