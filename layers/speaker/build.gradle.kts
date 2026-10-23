import io.github.hazymorning.klangbild.gradle.reads

plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.config)
            implementation(projects.data)
            implementation(projects.domain)
            implementation(projects.sendspin)
            implementation(projects.sound)
            // The speaker runs in the scope it is given, on Ktor's client, and tells how it stands
            // as a flow.
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.cryptography.random)
        }
        // The speaker's client on Android: Ktor's OkHttp engine, which pings.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}

// The speaker against aiosendspin, the server Music Assistant runs (ci/sendspin_server.py):
// -Pklangbild.sendspin=/path/to/a/python/with/aiosendspin
tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    providers.gradleProperty("klangbild.sendspin").orNull?.let { python -> systemProperty("klangbild.sendspin", python) }
    reads("klangbild.sendspinServer", layout.settingsDirectory.file("ci/sendspin_server.py"))
}
