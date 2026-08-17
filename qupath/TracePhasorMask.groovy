import io.github.flimkit.bridge.PhasorAnnotations
import java.nio.file.Files
import java.nio.file.Paths

def labelPath = args[0]
def width = args[1] as int
def height = args[2] as int
def binning = args[3] as int

def bytes = Files.readAllBytes(Paths.get(labelPath))
def objects = PhasorAnnotations.fromLabels(bytes, width, height, binning,
        ['Population A', 'Population B'], 'test-server')

println("TRACED count=${objects.size()}")
objects.each { o ->
    def r = o.getROI()
    println("ROI name=${o.getName()}|area=${r.getArea()}|x=${r.getBoundsX()}" +
            "|y=${r.getBoundsY()}|w=${r.getBoundsWidth()}|h=${r.getBoundsHeight()}" +
            "|class=${o.getPathClass()}")
}
println('TRACE_DONE')
