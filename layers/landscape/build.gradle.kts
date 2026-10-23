plugins {
    id("klangbild.android.library")
    id("klangbild.compose")
}

android {
    namespace = "io.github.hazymorning.klangbild.landscape"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation(projects.color)
    implementation(projects.math)
    implementation(libs.suncalc)
}
