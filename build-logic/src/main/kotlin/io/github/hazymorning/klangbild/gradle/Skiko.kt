package io.github.hazymorning.klangbild.gradle

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Adds skikoMain: the code the JVM and the browser share, where Compose draws with Skia. */
@OptIn(ExperimentalKotlinGradlePluginApi::class)
fun KotlinMultiplatformExtension.skikoSourceSet() {
    applyDefaultHierarchyTemplate {
        common {
            group("skiko") {
                withJvm()
                withWasmJs()
            }
        }
    }
}
