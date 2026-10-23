pluginManagement {
    // The build's own plugins: what the modules have in common.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's official Maven Central mirror first: repo1.maven.org rate-limits (HTTP 429)
        // shared egress IPs; Maven Central itself stays as fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's official Maven Central mirror first: repo1.maven.org rate-limits (HTTP 429)
        // shared egress IPs; Maven Central itself stays as fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Klangbild"

// The app: the activity, the speaker's service, the screensaver and the wiring of it all.
include(":androidApp")

// The layers, from the bottom up, each a module of its own in layers/ that is named as its
// package; the -testing modules hold helpers that the tests of several layers share.
for (module in listOf(
    "math", "json", "color", "text", "art", "sound", "sendspin", "domain", "data", "config",
    "landscape", "render", "lights", "speaker", "setup", "app", "ui",
    "art-testing", "text-testing", "domain-testing", "app-testing",
)) {
    include(":$module")
    project(":$module").projectDir = file("layers/$module")
}
