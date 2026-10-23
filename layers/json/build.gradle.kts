plugins {
    id("klangbild.android.library")
}

android {
    namespace = "io.github.hazymorning.klangbild.json"
}

dependencies {
    // Its functions read the elements of kotlinx.serialization's JSON.
    api(libs.kotlinx.serialization.json)
}
