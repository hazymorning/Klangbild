import io.github.hazymorning.klangbild.gradle.skikoSourceSet

plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

kotlin {
    // The scene's pictures as Skia's bitmaps where Compose draws with Skia (the JVM and the browser).
    skikoSourceSet()
    sourceSets {
        commonMain.dependencies {
            implementation(projects.art)
            implementation(projects.color)
            implementation(projects.math)
            // The scene's pictures are Compose's bitmaps.
            api(libs.compose.ui)
        }
    }
}
