import io.github.hazymorning.klangbild.gradle.reads

plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
    id("klangbild.robolectric")
    alias(libs.plugins.roborazzi)
}

kotlin {
    android {
        // The two typefaces are Compose resources, which Android reads from the app's assets.
        androidResources.enable = true
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.app)
            implementation(projects.color)
            implementation(projects.config)
            implementation(projects.domain)
            implementation(projects.landscape)
            implementation(projects.math)
            implementation(projects.render)
            implementation(projects.setup)
            implementation(projects.sound)
            implementation(projects.text)
            // The screens' API is Compose's, and their frame statistics come as a flow.
            api(libs.compose.animation)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.compose.resources)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.datetime)
            implementation(libs.lifecycle.runtime.compose)
        }
        androidHostTest.dependencies {
            implementation(projects.appTesting)
            implementation(projects.artTesting)
            implementation(projects.domainTesting)
            implementation(projects.textTesting)
            // The design rules hold the sand figures too (DesignRulesTest), and the README's
            // pictures show the lights' colors (ReadmeShotsTest).
            implementation(projects.art)
            implementation(projects.lights)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.roborazzi)
            implementation(libs.roborazzi.compose)
            // The party code read back from the pixels (PartyCodeTest), the sun's times for the
            // landscape's gallery (LandscapeGalleryTest).
            implementation(libs.zxing.core)
            implementation(libs.suncalc)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.hazymorning.klangbild.ui.resources"
}

roborazzi {
    outputDir.set(layout.buildDirectory.dir("screenshots"))
}

tasks.withType<Test>().configureEach {
    // Two JVMs share the Robolectric classes; the screenshot classes run side by side.
    maxParallelForks = 2
    // The picture tests render how the screens look and check nothing themselves (see Pictures
    // in the tests). The everyday run leaves them out; -Pklangbild.pictures runs them too, as
    // CI's picture comparison does.
    if (!providers.gradleProperty("klangbild.pictures").isPresent) {
        useJUnit { excludeCategories("io.github.hazymorning.klangbild.Pictures") }
    }
    // LanguageTest reads the words as they are written, in English and German.
    reads("klangbild.strings", layout.settingsDirectory.dir("layers/text/strings"), "values*/strings.xml")
}
