import io.github.flimkit.bridge.FitResults
import com.google.gson.JsonParser
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane

def payload = JsonParser.parseString(new File(args[0]).text).getAsJsonObject()

def objects = ['Cell 1', 'Cell 2'].collect { nm ->
    def o = PathObjects.createAnnotationObject(
        ROIs.createRectangleROI(0, 0, 10, 10, ImagePlane.getDefaultPlane()))
    o.setName(nm)
    o
}

int applied = FitResults.applyToObjects(payload, objects)
println("APPLIED count=${applied}")
objects.each { o ->
    def ml = o.getMeasurementList()
    println("OBJECT name=${o.getName()}|n=${ml.getNames().size()}" +
            "|tau_mean=${ml.get('FLIM: tau mean (ns)')}" +
            "|chi2r=${ml.get('FLIM: chi2r')}" +
            "|tau1=${ml.get('FLIM: tau1')}")
}
println('ERRORS ' + FitResults.errors(payload).join('|'))
println('APPLY_DONE')
