import com.android.build.api.dsl.CommonExtension
import io.github.hazymorning.klangbild.gradle.libs
import io.github.hazymorning.klangbild.gradle.library
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * A module the Compose compiler compiles: the screens, and every layer whose classes they take
 * as they are. The compiler sees a class as stable only when it compiled it itself, so the
 * layers below the screens go through it too, and the screens skip and remember just as when
 * everything was one module.
 */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.getByType<CommonExtension>().buildFeatures.compose = true
        dependencies {
            "implementation"(platform(libs.library("compose-bom")))
            "implementation"(libs.library("compose-runtime"))
        }
    }
}
