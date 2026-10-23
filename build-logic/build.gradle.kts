plugins {
    `kotlin-dsl`
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // Only compiled against: the plugins themselves come from the main build, which declares them.
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin.api)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "klangbild.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "klangbild.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("compose") {
            id = "klangbild.compose"
            implementationClass = "ComposeConventionPlugin"
        }
        register("robolectric") {
            id = "klangbild.robolectric"
            implementationClass = "RobolectricConventionPlugin"
        }
    }
}
