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
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon

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
                // The tests run in a browser without a window, where Compose's graphics (Skia)
                // load, bundled as an executable is: a library's bundle leaves Skia's runtime
                // out (CMP-4906) and runs as a script, not a module.
                binaries.executable()
                browser {
                    testTask {
                        useKarma {
                            useChromeHeadless()
                            useConfigDirectory(rootDir.resolve("build-logic/karma.config.d"))
                        }
                    }
                }
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
        // module's: on Android (Robolectric), on the JVM and as WebAssembly. The shared code is
        // also compiled on its own, which catches calls that every target has but common code
        // doesn't.
        tasks.register("testDebugUnitTest") {
            dependsOn("allTests")
            dependsOn(tasks.withType<KotlinCompileCommon>())
        }
        // Karma, which runs the WebAssembly tests, runs on Node.js, and Binaryen optimizes what
        // assemble builds; both come from the repositories declared in settings.gradle.kts, as every
        // other download does (the root project's Node.js is set there).
        plugins.withType<WasmNodeJsPlugin> {
            extensions.getByType<WasmNodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
        }
        @OptIn(ExperimentalWasmDsl::class)
        plugins.withType<BinaryenPlugin> {
            extensions.getByType<BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
        }
    }
}
