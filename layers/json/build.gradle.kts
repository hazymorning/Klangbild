plugins {
    id("klangbild.multiplatform")
}

kotlin {
    sourceSets.commonMain.dependencies {
        // Its functions read the elements of kotlinx.serialization's JSON.
        api(libs.kotlinx.serialization.json)
    }
}
