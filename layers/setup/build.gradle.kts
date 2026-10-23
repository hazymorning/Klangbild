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
            implementation(projects.json)
            implementation(projects.render)
            implementation(projects.text)
            // The setup page runs in the scope it is given, tells its state as a flow, asks Home
            // Assistant with Ktor and reads the scripts' fields as JSON.
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.cryptography.random)
        }
        commonTest.dependencies {
            implementation(projects.textTesting)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidHostTest.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp.mockwebserver)
        }
    }
}
