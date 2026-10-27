plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.math)
    }
}
