import io.github.hazymorning.klangbild.gradle.reads

plugins {
    id("klangbild.android.library")
    id("klangbild.compose")
    id("klangbild.robolectric")
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "io.github.hazymorning.klangbild.ui"

    testOptions {
        unitTests.all {
            // Two JVMs share the Robolectric classes; the screenshot classes run side by side.
            it.maxParallelForks = 2
            // The picture tests render how the screens look and check nothing themselves (see
            // Pictures in the tests). The everyday run leaves them out; -Pklangbild.pictures
            // runs them too, as CI's picture comparison does.
            if (!providers.gradleProperty("klangbild.pictures").isPresent) {
                it.useJUnit { excludeCategories("io.github.hazymorning.klangbild.Pictures") }
            }
            // LanguageTest reads the words as they are written, in English and German.
            it.reads("klangbild.strings", layout.settingsDirectory.dir("layers/text/src/main/res"), "values*/strings.xml")
        }
    }
}

roborazzi {
    outputDir.set(layout.buildDirectory.dir("screenshots"))
}

dependencies {
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
    api(platform(libs.compose.bom))
    api(libs.compose.animation)
    api(libs.compose.foundation)
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.text)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(projects.appTesting)
    testImplementation(projects.artTesting)
    testImplementation(projects.domainTesting)
    testImplementation(projects.textTesting)
    // The design rules hold the sand figures too (DesignRulesTest), and the README's pictures show
    // the lights' colors (ReadmeShotsTest).
    testImplementation(projects.art)
    testImplementation(projects.lights)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    // The party code read back from the pixels (PartyCodeTest), the sun's times for the
    // landscape's gallery (LandscapeGalleryTest).
    testImplementation(libs.zxing.core)
    testImplementation(libs.suncalc)
    debugImplementation(libs.compose.ui.test.manifest)
}
