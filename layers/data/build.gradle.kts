plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.domain)
            implementation(projects.json)
            implementation(projects.sendspin)
            implementation(projects.sound)
            // Its API hands out flows and covers, takes Ktor's clients and speaks Home Assistant's JSON.
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            api(libs.compose.ui)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.datetime)
            implementation(libs.normalize)
        }
        // Ktor's OkHttp engine, whose interceptors keep cleartext in the home network.
        androidMain.dependencies {
            api(libs.ktor.client.okhttp)
        }
        commonTest.dependencies {
            implementation(projects.domainTesting)
        }
        androidHostTest.dependencies {
            implementation(libs.okhttp.mockwebserver)
        }
    }
}
