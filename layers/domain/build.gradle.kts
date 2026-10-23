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
            // The values and conditions of one's own read Home Assistant's JSON as it comes.
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            implementation(libs.normalize)
        }
        commonTest.dependencies {
            implementation(projects.textTesting)
        }
    }
}
