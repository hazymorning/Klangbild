plugins {
    id("klangbild.android.library")
}

android {
    namespace = "io.github.hazymorning.klangbild.app.testing"
}

dependencies {
    implementation(projects.app)
    implementation(projects.artTesting)
    implementation(projects.color)
    implementation(projects.config)
    implementation(projects.domain)
    implementation(projects.domainTesting)
    implementation(projects.landscape)
    implementation(projects.render)
    implementation(projects.text)
    implementation(projects.textTesting)
}
