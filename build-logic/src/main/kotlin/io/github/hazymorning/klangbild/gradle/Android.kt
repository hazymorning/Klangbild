package io.github.hazymorning.klangbild.gradle

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.Lint
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidExtension

/** The app's Android setup: the SDK, Java 17, Kotlin and lint, which fail on every warning. */
internal fun Project.configureAndroid(android: CommonExtension) {
    android.compileSdk = libs.number("compileSdk")
    android.defaultConfig.minSdk = libs.number("minSdk")
    android.compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    configureLint(android.lint)
    extensions.configure<KotlinAndroidExtension> {
        compilerOptions {
            // A compiler warning fails the build as a lint warning does.
            allWarningsAsErrors.set(true)
        }
    }
    configureTests()
}

/** Lint for the app and every layer: every warning fails the build. The checks below are off, each for the reason given. */
internal fun configureLint(lint: Lint) {
    lint.warningsAsErrors = true
    lint.abortOnError = true
    lint.disable += listOf(
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

/** Every module's tests. */
internal fun Project.configureTests() {
    tasks.withType<Test>().configureEach {
        // The speaker's simulated playback and the screens' 4K pictures need this much room.
        maxHeapSize = "3g"
    }
}
