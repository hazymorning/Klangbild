import io.github.hazymorning.klangbild.gradle.reads
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    id("klangbild.android.application")
    id("klangbild.compose")
    id("klangbild.robolectric")
}

// versionCode counts up by itself: the minutes from 2026-01-01 to the HEAD commit. It is the
// same for every build of a commit, grows with a linear history and needs no more history than
// a shallow clone has.
val headCommitEpochSeconds: Long = providers.exec {
    commandLine("git", "log", "-1", "--format=%ct")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().toLongOrNull() ?: 1767225600L
val headCommitShortSha: String = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "local" }
val versionEpochSeconds = 1767225600L // 2026-01-01T00:00:00Z
val autoVersionCode = ((headCommitEpochSeconds - versionEpochSeconds) / 60L).coerceAtLeast(1L).toInt()
// A release build from a tag gets the tag's name ("1.0.0", passed by CI); every other build
// is named after its commit date and hash.
val autoVersionName: String = providers.gradleProperty("klangbild.versionName").orNull?.takeIf { it.isNotBlank() }
    ?: (
        DateTimeFormatter.ofPattern("yyyy.MM.dd")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(headCommitEpochSeconds)) + " ($headCommitShortSha)"
        )

android {
    namespace = "io.github.hazymorning.klangbild"

    defaultConfig {
        applicationId = "io.github.hazymorning.klangbild"
        versionCode = autoVersionCode
        versionName = autoVersionName
        // No abiFilters on purpose: the app ships no native code of its own, and any
        // native code pulled in by libraries keeps all ABIs including armeabi-v7a.
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Signed with the debug key so that any build installs for testing. Published
            // releases are signed again in CI with the release key (ci/sign-release.sh).
            signingConfig = signingConfigs.getByName("debug")
            // The modules bring the rules their libraries need (consumer-rules.pro).
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            // FirstLaunchTest against the stand-in Home Assistant (ci/fake_ha.py): -Pklangbild.fakeHa=http://127.0.0.1:8123
            providers.gradleProperty("klangbild.fakeHa").orNull?.let { url -> it.systemProperty("klangbild.fakeHa", url) }
            // LayersTest reads the app's own code and every layer's.
            it.reads("klangbild.appCode", layout.projectDirectory.dir("src/main/java/io/github/hazymorning/klangbild"), "**/*.kt")
            it.reads("klangbild.layers", layout.settingsDirectory.dir("layers"), "*/src/main/**/*.kt")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }
}

dependencies {
    implementation(projects.app)
    implementation(projects.config)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.landscape)
    implementation(projects.render)
    implementation(projects.sound)
    implementation(projects.speaker)
    implementation(projects.text)
    implementation(projects.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
}
