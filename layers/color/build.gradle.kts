plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.math)
    }
    sourceSets.commonTest.dependencies {
        // The wall turns with the sun, also by the clock alone (CoverPaletteTest).
        implementation(projects.domain)
    }
}
