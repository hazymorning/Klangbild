package io.github.hazymorning.klangbild.gradle

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension

/** What every Android module has in common: the SDK, Java 17, Kotlin and lint, which fail on every warning. */
internal fun Project.configureAndroid(android: CommonExtension) {
    android.compileSdk = libs.number("compileSdk")
    android.defaultConfig.minSdk = libs.number("minSdk")
    android.compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    android.lint.apply {
        // Every warning fails the build. The checks below are off, each for the reason given.
        warningsAsErrors = true
        abortOnError = true
        disable += listOf(
            // Target 36 on purpose (see targetSdk in the version catalog).
            "OldTargetApi",
            // Depends on the network and the day: CI would fail without a change to the code.
            "NewerVersionAvailable",
            "GradleDependency",
            "AndroidGradlePluginVersion",
            // Sideloaded as one APK with every language; there are no app bundle language splits.
            "AppBundleLocaleChanges",
            // The TV shows the banner; the square icon is a full tile on purpose.
            "IconLauncherShape",
            // The TV banner exists at xhdpi only, the size Android TV asks for.
            "IconMissingDensityFolder",
        )
    }
    android.testOptions.unitTests.all {
        // The speaker's simulated playback and the screens' 4K pictures need this much room.
        it.maxHeapSize = "3g"
    }
    extensions.configure<KotlinAndroidExtension> {
        compilerOptions {
            // A compiler warning fails the build as a lint warning does.
            allWarningsAsErrors.set(true)
        }
    }
    dependencies {
        "testImplementation"(libs.library("junit"))
        "testImplementation"(libs.library("truth"))
    }
}
