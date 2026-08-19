plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-flimkit-bridge"
    group = "io.github.flimkit"
    version = "0.3.0"
    description = "Direct image and ROI exchange between FLIMKit and QuPath"
    automaticModule = "io.github.flimkit.bridge"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
}
