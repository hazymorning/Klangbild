import io.github.hazymorning.klangbild.gradle.GenerateLineBreaks
import io.github.hazymorning.klangbild.gradle.reads
import io.github.hazymorning.klangbild.gradle.skikoSourceSet

plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
    id("klangbild.robolectric")
    alias(libs.plugins.roborazzi)
}

// ICU's line break rules as Kotlin, for BalancedBreaker.
val generateLineBreaks = tasks.register<GenerateLineBreaks>("generateLineBreaks") {
    icu.from(configurations.detachedConfiguration(dependencies.create(libs.icu4j.linebreaks.get())))
    packageName = "io.github.hazymorning.klangbild.ui.text"
    output = layout.buildDirectory.dir("generated/lineBreaks")
}

kotlin {
    android {
        // The two typefaces are Android resources.
        androidResources.enable = true
    }
    // Where Compose draws with Skia (the JVM and the browser), the typefaces are Compose resources.
    skikoSourceSet()
    sourceSets {
        commonMain {
            kotlin.srcDir(generateLineBreaks.flatMap { it.output })
            dependencies {
                implementation(projects.app)
                implementation(projects.color)
                implementation(projects.config)
                implementation(projects.domain)
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
        }
        commonTest.dependencies {
            implementation(projects.textTesting)
            // The design rules hold the sand figures too (DesignRulesTest).
            implementation(projects.art)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidHostTest.dependencies {
            implementation(projects.appTesting)
            implementation(projects.artTesting)
            implementation(projects.domainTesting)
            // The README's pictures show the lights' colors (ReadmeShotsTest).
            implementation(projects.lights)
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.roborazzi)
            implementation(libs.roborazzi.compose)
            // The party code read back from the pixels (PartyCodeTest).
            implementation(libs.zxing.core)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.hazymorning.klangbild.ui.resources"
    // The files Android has as resources.
    customDirectory("skikoMain", provider { layout.projectDirectory.dir("src/androidMain/res") })
}

roborazzi {
    outputDir.set(layout.buildDirectory.dir("screenshots"))
}

// The tests on the Android host, the screens' among them.
tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
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
    // BreakerParityTest breaks every text the tests and fixtures set.
    for (dir in listOf("ui/src/androidHostTest", "ui/src/commonTest", "art-testing/src", "domain-testing/src", "app-testing/src")) {
        reads("klangbild.corpus.$dir", layout.settingsDirectory.dir("layers/$dir"), "**/*.kt")
    }
}
