import io.github.hazymorning.klangbild.gradle.GenerateTexts

plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

// The words of strings/values*/strings.xml and their languages' formats, as Kotlin.
val generateTexts = tasks.register<GenerateTexts>("generateTexts") {
    strings = layout.projectDirectory.dir("strings")
    output = layout.buildDirectory.dir("generated/texts")
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateTexts.flatMap { it.output })
            dependencies {
                api(libs.compose.runtime)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
            }
        }
        androidMain.dependencies {
            implementation(libs.androidx.annotation)
            implementation(libs.androidx.core.ktx)
        }
        commonTest.dependencies {
            implementation(projects.textTesting)
        }
    }
}
