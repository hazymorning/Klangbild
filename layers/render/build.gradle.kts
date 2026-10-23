plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

kotlin {
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
