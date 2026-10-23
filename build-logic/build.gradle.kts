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
    compileOnly(libs.kotlin.gradle.plugin)
    // The texts' formats come from Unicode's CLDR, as ICU has them (GenerateTexts).
    implementation(libs.icu4j)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "klangbild.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("multiplatform") {
            id = "klangbild.multiplatform"
            implementationClass = "MultiplatformConventionPlugin"
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
