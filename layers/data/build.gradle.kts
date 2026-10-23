plugins {
    id("klangbild.android.library")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

android {
    namespace = "io.github.hazymorning.klangbild.data"
}

dependencies {
    implementation(projects.domain)
    implementation(projects.json)
    implementation(projects.sendspin)
    implementation(projects.sound)
    // Its API hands out flows, takes OkHttp's clients and speaks Home Assistant's JSON.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(projects.domainTesting)
    testImplementation(libs.okhttp.mockwebserver)
}
