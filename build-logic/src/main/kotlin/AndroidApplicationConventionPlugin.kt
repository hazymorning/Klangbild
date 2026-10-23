import com.android.build.api.dsl.ApplicationExtension
import io.github.hazymorning.klangbild.gradle.configureAndroid
import io.github.hazymorning.klangbild.gradle.libs
import io.github.hazymorning.klangbild.gradle.library
import io.github.hazymorning.klangbild.gradle.number
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** The app: an Android application whose lint looks at every module it is made of. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            configureAndroid(this)
            defaultConfig.targetSdk = libs.number("targetSdk")
            lint.checkDependencies = true
        }
        dependencies {
            "testImplementation"(libs.library("kotlin-test"))
            "testImplementation"(libs.library("junit"))
            "testImplementation"(libs.library("assertk"))
        }
    }
}
