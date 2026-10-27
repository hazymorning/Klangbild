plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.atomicfu)
            implementation(projects.json)
            implementation(projects.text)
            // JsonObject is part of the API: entity states, custom values and conditions keep Home Assistant's JSON.
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            implementation(libs.normalize)
        }
        commonTest.dependencies {
            implementation(projects.textTesting)
        }
    }
}
