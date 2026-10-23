import com.android.build.api.dsl.LibraryExtension
import io.github.hazymorning.klangbild.gradle.configureAndroid
import io.github.hazymorning.klangbild.gradle.libs
import io.github.hazymorning.klangbild.gradle.number
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** A layer of the app, or the helpers of a layer's tests: an Android library. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroid(this)
            // A library's tests run in an app of their own, which targets what the app targets.
            testOptions.targetSdk = libs.number("targetSdk")
        }
    }
}
