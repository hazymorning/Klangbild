plugins {
    id("klangbild.multiplatform")
    id("klangbild.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.color)
            implementation(projects.config)
            implementation(projects.data)
            implementation(projects.domain)
            implementation(projects.math)
            implementation(projects.render)
            // The lights run in the scope they are given.
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
