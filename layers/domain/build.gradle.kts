plugins {
    id("klangbild.android.library")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

android {
    namespace = "io.github.hazymorning.klangbild.domain"
}

dependencies {
    implementation(projects.json)
    implementation(projects.text)
    // The values and conditions of one's own read Home Assistant's JSON as it comes.
    api(libs.kotlinx.serialization.json)

    testImplementation(projects.textTesting)
}
