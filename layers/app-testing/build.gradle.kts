plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.app)
            implementation(projects.artTesting)
            implementation(projects.color)
            implementation(projects.config)
            implementation(projects.domain)
            implementation(projects.domainTesting)
            implementation(projects.render)
            implementation(projects.text)
            implementation(projects.textTesting)
            implementation(libs.kotlinx.datetime)
        }
    }
}
