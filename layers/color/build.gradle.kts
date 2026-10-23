plugins {
    id("klangbild.android.library")
    id("klangbild.compose")
}

android {
    namespace = "io.github.hazymorning.klangbild.color"
}

dependencies {
    // The wall turns with the sun, also by the clock alone (CoverPaletteTest).
    testImplementation(projects.domain)
}
