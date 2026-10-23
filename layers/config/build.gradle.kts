plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.color)
            implementation(projects.data)
            implementation(projects.domain)
            implementation(projects.json)
            implementation(projects.text)
            // The settings come as a flow; the own buttons and values are stored as JSON.
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        // The values on disk: DataStore's preferences.
        androidMain.dependencies {
            api(libs.androidx.datastore.preferences)
        }
        commonTest.dependencies {
            implementation(projects.textTesting)
        }
    }
}
