plugins {
    id("klangbild.android.library")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

android {
    namespace = "io.github.hazymorning.klangbild.text"
}

dependencies {
    // The words come as a StateFlow and as a composition local.
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.compose.ui)

    testImplementation(projects.textTesting)
}
