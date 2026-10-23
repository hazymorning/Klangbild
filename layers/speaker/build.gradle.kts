import io.github.hazymorning.klangbild.gradle.reads

plugins {
    id("klangbild.android.library")
    id("klangbild.robolectric")
}

android {
    namespace = "io.github.hazymorning.klangbild.speaker"

    testOptions {
        unitTests.all {
            // The speaker against aiosendspin, the server Music Assistant runs (ci/sendspin_server.py):
            // -Pklangbild.sendspin=/path/to/a/python/with/aiosendspin
            providers.gradleProperty("klangbild.sendspin").orNull?.let { python -> it.systemProperty("klangbild.sendspin", python) }
            it.reads("klangbild.sendspinServer", layout.settingsDirectory.file("ci/sendspin_server.py"))
        }
    }
}

dependencies {
    implementation(projects.config)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.sendspin)
    implementation(projects.sound)
    // The speaker runs in the scope it is given, on OkHttp's client, keeps its state in a DataStore
    // and tells how it stands as a flow.
    api(libs.androidx.datastore.preferences)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
