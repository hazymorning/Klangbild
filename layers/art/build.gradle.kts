plugins {
    id("klangbild.multiplatform")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.color)
    }
}

// SleeveSheetTest draws the generated covers there; as the tests' output, the sheet comes back
// with them from the build cache.
tasks.named<Test>("jvmTest") {
    outputs.dir(layout.buildDirectory.dir("screenshots/art"))
}
