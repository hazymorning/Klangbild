import com.android.build.api.dsl.CommonExtension
import io.github.hazymorning.klangbild.gradle.libs
import io.github.hazymorning.klangbild.gradle.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/** A module whose tests run on Android as Robolectric gives it, with the resources and real graphics. */
class RobolectricConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        extensions.getByType<CommonExtension>().testOptions.unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                // Robolectric fetches its android-all jars itself; use the same Maven Central
                // mirror as Gradle (repo1.maven.org rate-limits shared egress IPs).
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven-central.storage-download.googleapis.com/maven2",
                )
            }
        }
        dependencies {
            "testImplementation"(libs.library("robolectric"))
            "testImplementation"(libs.library("androidx-test-core"))
            "testImplementation"(libs.library("androidx-test-ext-junit"))
        }
    }
}
