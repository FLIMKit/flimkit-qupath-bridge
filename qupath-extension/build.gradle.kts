plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

// maven.scijava.org is serving the qupath-core and qupath-gui-fx 0.7.0 poms
// but 404s on the jars themselves (an upstream outage, not a real removal).
// This vendors those two jars, extracted from the official QuPath-v0.7.0
// release build, until scijava's mirror recovers; safe to drop once
// https://maven.scijava.org/.../qupath-gui-fx/0.7.0/qupath-gui-fx-0.7.0.jar
// resolves normally again.
repositories {
    all {
        if (this is org.gradle.api.artifacts.repositories.MavenArtifactRepository &&
            url.toString().contains("scijava")) {
            content {
                excludeModule("io.github.qupath", "qupath-core")
                excludeModule("io.github.qupath", "qupath-gui-fx")
            }
        }
    }
    maven {
        url = uri("vendor-repo")
        content {
            includeModule("io.github.qupath", "qupath-core")
            includeModule("io.github.qupath", "qupath-gui-fx")
        }
    }
}

qupathExtension {
    name = "qupath-extension-flimkit-bridge"
    group = "io.github.flimkit"
    version = "0.5.0"
    description = "Direct image and ROI exchange between FLIMKit and QuPath"
    automaticModule = "io.github.flimkit.bridge"
}

dependencies {
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
