import io.github.flimkit.bridge.FlimKitServerBuilder

import java.net.URI
import java.nio.file.Paths

def paths = args[0].split(';')

def builder = new FlimKitServerBuilder()
println('BUILDER_NAME ' + builder.getName())

paths.each { path ->
    def uri = Paths.get(path).toUri()
    def name = new File(path).getName()
    def support = builder.checkImageSupport(uri)
    if (support == null) {
        println("SUPPORT name=${name} level=none")
        return
    }
    println("SUPPORT name=${name} level=${support.getSupportLevel()}")
    def server = builder.buildServer(uri)
    try {
        println("OPENED name=${name} width=${server.getWidth()} height=${server.getHeight()}" +
                " type=${server.getPixelType()} channels=${server.nChannels()}" +
                " serverType='${server.getServerType()}'")
        def img = server.readRegion(1.0, 0, 0, server.getWidth(), server.getHeight())
        def raster = img.getRaster()
        int peak = 0
        long total = 0
        for (int yy = 0; yy < img.getHeight(); yy++) {
            for (int xx = 0; xx < img.getWidth(); xx++) {
                int v = raster.getSample(xx, yy, 0)
                total += v
                if (v > peak) peak = v
            }
        }
        println("TILE name=${name} w=${img.getWidth()} h=${img.getHeight()}" +
                " max=${peak} sum=${total}")
    } finally {
        server.close()
    }
}
println('OPEN_DONE')
