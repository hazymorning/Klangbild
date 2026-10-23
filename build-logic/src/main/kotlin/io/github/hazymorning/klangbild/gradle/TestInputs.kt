package io.github.hazymorning.klangbild.gradle

import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/**
 * Hands the tests [file], which they read from outside their module, as the system property
 * [property]. What is in it counts as the tests' input, so they run again when it changes, while
 * where it lies on this machine stays out of the build cache's key.
 */
fun Test.reads(property: String, file: RegularFile) {
    jvmArgumentProviders += TestInput(property, file.asFile, project.files(file))
}

/** As [reads] for a file, for the files in [dir] that [pattern] names; the property names [dir]. */
fun Test.reads(property: String, dir: Directory, pattern: String) {
    jvmArgumentProviders += TestInput(property, dir.asFile, dir.asFileTree.matching { include(pattern) })
}

private class TestInput(
    @get:Input val property: String,
    private val location: File,
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val contents: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-D$property=${location.absolutePath}")
}
