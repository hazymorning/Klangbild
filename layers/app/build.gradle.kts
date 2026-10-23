plugins {
    id("klangbild.android.library")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

android {
    namespace = "io.github.hazymorning.klangbild.app"
}

dependencies {
    implementation(projects.color)
    implementation(projects.config)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.landscape)
    implementation(projects.lights)
    implementation(projects.math)
    implementation(projects.render)
    implementation(projects.setup)
    implementation(projects.sound)
    implementation(projects.text)
    // The controller runs in the scope it is given, on OkHttp's clients, and hands out its state
    // as a flow.
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    testImplementation(projects.appTesting)
    testImplementation(projects.artTesting)
    testImplementation(projects.textTesting)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
}
