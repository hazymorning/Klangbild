plugins {
    id("klangbild.android.library")
}

android {
    namespace = "io.github.hazymorning.klangbild.text.testing"
}

dependencies {
    implementation(projects.text)
    implementation(libs.androidx.test.core)
}
