import io.github.flimkit.bridge.BridgeClient
import io.github.flimkit.bridge.ProjectImporter
import qupath.lib.projects.Projects

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Paths

def baseUrl = args[0]
def token = args[1]
def projectDir = Paths.get(args[2])

Files.createDirectories(projectDir)
def project = Projects.createProject(projectDir.toFile(), BufferedImage.class)
def client = new BridgeClient(baseUrl, token)

def names = []
['intensity', 'lifetime'].each { imageId ->
    def fetched = client.fetchImage(imageId)
    def stored = ProjectImporter.storeBesideProject(project, imageId, fetched.file())
    def entry = ProjectImporter.addToProject(project, stored, imageId, fetched.valueUnit())
    names << entry.getImageName()
}
project.syncChanges()

def reopened = Projects.createProject(projectDir.toFile(), BufferedImage.class)
def entries = project.getImageList()
def opened = 0
entries.each { entry ->
    def server = entry.getServerBuilder().build()
    if (server.getWidth() > 0 && server.getHeight() > 0) {
        opened++
    }
    println("ENTRY name=${entry.getImageName()} width=${server.getWidth()} height=${server.getHeight()} type=${server.getPixelType()}")
    server.close()
}

println("PROJECT_OK entries=${entries.size()} opened=${opened} names=${names.join('|')}")
