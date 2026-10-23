plugins {
    id("klangbild.android.library")
}

android {
    namespace = "io.github.hazymorning.klangbild.domain.testing"
}

dependencies {
    implementation(projects.artTesting)
    implementation(projects.domain)
}
