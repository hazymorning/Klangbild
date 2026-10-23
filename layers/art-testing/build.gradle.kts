plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.render)
            // The covers are Compose's bitmaps.
            api(libs.compose.ui)
        }
    }
}
