// The build's own plugins: what every module of Klangbild has in common, set in one place.
pluginManagement {
    repositories {
        // Google's official Maven Central mirror first, as in the main build; the plugin portal
        // last, for kotlin-dsl itself.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's official Maven Central mirror first, as in the main build.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
