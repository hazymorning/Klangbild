plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
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
            // The controller runs in the scope it is given, on Ktor's clients, and hands out its
            // state, with the covers of the queue's list, as a flow.
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            api(libs.compose.ui)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidHostTest.dependencies {
            implementation(projects.appTesting)
            implementation(projects.artTesting)
            implementation(projects.textTesting)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp.mockwebserver)
        }
    }
}
