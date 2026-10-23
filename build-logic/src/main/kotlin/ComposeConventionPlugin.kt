import com.android.build.api.dsl.ApplicationExtension
import io.github.hazymorning.klangbild.gradle.libs
import io.github.hazymorning.klangbild.gradle.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A module the Compose compiler compiles: the screens, the app, and every layer whose classes
 * the screens take as they are. The compiler sees a class as stable only when it compiled it
 * itself, so the layers below the screens go through it too, so the screens can still skip what
 * hasn't changed when they recompose.
 */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            // Compose Multiplatform's own plugin brings Skia's WebAssembly runtime to what runs
            // in the browser, the tests too.
            pluginManager.apply("org.jetbrains.compose")
            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.getByName("commonMain").dependencies {
                    implementation(libs.library("compose-runtime"))
                }
            }
        }
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension> {
                buildFeatures.compose = true
            }
            dependencies {
                "implementation"(platform(libs.library("androidx-compose-bom")))
            }
        }
    }
}
