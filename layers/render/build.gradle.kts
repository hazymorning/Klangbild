plugins {
    id("klangbild.android.library")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

android {
    namespace = "io.github.hazymorning.klangbild.render"
}

dependencies {
    implementation(projects.art)
    implementation(projects.color)
    implementation(projects.math)
    implementation(libs.androidx.core.ktx)
    implementation(libs.zxing.core)
}
