import com.android.build.api.dsl.ApplicationExtension
import io.github.hazymorning.klangbild.gradle.libs
import io.github.hazymorning.klangbild.gradle.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A module with tests that run on Android as Robolectric gives it, with the resources and real
 * graphics: a layer's androidHostTest, or the app's unit tests.
 */
class RobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val testLibraries = listOf("robolectric", "androidx-test-core", "androidx-test-ext-junit")
        pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            extensions.configure<KotlinMultiplatformExtension> {
                sourceSets.getByName("androidHostTest").dependencies {
                    for (name in testLibraries) implementation(libs.library(name))
                }
            }
        }
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension> {
                testOptions.unitTests.isIncludeAndroidResources = true
            }
            dependencies {
                for (name in testLibraries) "testImplementation"(libs.library(name))
            }
        }
        tasks.withType<Test>().configureEach {
            systemProperty("robolectric.graphicsMode", "NATIVE")
            // Robolectric fetches its android-all jars itself; use the same Maven Central
            // mirror as Gradle (repo1.maven.org rate-limits shared egress IPs).
            systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
        }
    }
}
