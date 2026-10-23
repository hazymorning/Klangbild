plugins {
    id("klangbild.multiplatform")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(libs.kotlinx.atomicfu)
    }
}

// ChladniSheetTest draws every sand figure there; as the tests' output, the sheet comes back
// with them from the build cache.
tasks.named<Test>("jvmTest") {
    outputs.dir(layout.buildDirectory.dir("screenshots/art"))
}
