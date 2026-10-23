plugins {
    id("klangbild.multiplatform")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.artTesting)
            implementation(projects.domain)
        }
    }
}
