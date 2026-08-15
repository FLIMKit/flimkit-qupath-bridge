pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

qupath {
    version = "0.7.0"
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("io.github.qupath.qupath-extension-settings") version "0.2.1"
}

rootProject.name = "qupath-extension-flimkit-bridge"
