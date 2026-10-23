package io.github.hazymorning.klangbild.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** The versions and libraries the build uses, gradle/libs.versions.toml. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** A library from the catalog, by its name there. */
internal fun VersionCatalog.library(name: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(name).get()

/** A version from the catalog that is a number, such as an SDK level. */
internal fun VersionCatalog.number(name: String): Int = findVersion(name).get().requiredVersion.toInt()
