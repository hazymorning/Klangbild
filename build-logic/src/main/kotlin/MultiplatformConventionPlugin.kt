import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import io.github.hazymorning.klangbild.gradle.configureLint
import io.github.hazymorning.klangbild.gradle.configureTests
import io.github.hazymorning.klangbild.gradle.libs
import io.github.hazymorning.klangbild.gradle.library
import io.github.hazymorning.klangbild.gradle.number
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin

/**
 * A layer of the app, or the helpers of a layer's tests: a Kotlin Multiplatform library whose
 * code is common, built for Android, the JVM and the browser (WebAssembly). Android's own
 * implementations of what a layer asks of its platform live in its androidMain.
 */
class MultiplatformConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        pluginManager.apply("com.android.lint")
        extensions.configure<KotlinMultiplatformExtension> {
            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
                // A module is named as its package: art-testing is ...klangbild.art.testing.
                namespace = "io.github.hazymorning.klangbild." + project.name.replace('-', '.')
                compileSdk = libs.number("compileSdk")
                minSdk = libs.number("minSdk")
                lint { configureLint(this) }
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
                // The common tests run on Android too, in an app of their own that targets what
                // the app targets, with the resources (Robolectric's tests need them).
                withHostTest {
                    isIncludeAndroidResources = true
                    targetSdk { version = release(libs.number("targetSdk")) }
                }
            }
            jvm {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
            @OptIn(ExperimentalWasmDsl::class)
            wasmJs {
                nodejs()
            }
            compilerOptions {
                // A compiler warning fails the build as a lint warning does.
                allWarningsAsErrors.set(true)
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(libs.library("kotlin-test"))
                implementation(libs.library("assertk"))
            }
        }
        configureTests()
        // A layer's tests answer to the app's test task's name too, so one command runs every
        // module's: on Android (Robolectric), on the JVM and as WebAssembly.
        tasks.register("testDebugUnitTest") { dependsOn("allTests") }
        tasks.withType<KotlinJsTest>().configureEach {
            // As much room as the JVM's tests have (the speaker's simulated playback needs it).
            nodeJsArgs += "--max-old-space-size=3072"
        }
        // The WebAssembly tests run on Node.js, which comes from the repositories declared in
        // settings.gradle.kts, as every other download does (the root project's is set there).
        plugins.withType<WasmNodeJsPlugin> {
            extensions.getByType<WasmNodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
        }
    }
}
