package io.github.hazymorning.klangbild

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The packages build on each other in one direction: each uses only the ones listed for it. The
 * model knows nothing of Home Assistant or the screen, the data layer nothing of the settings,
 * and the settings nothing of the screen. MainActivity and the wiring (KlangbildApp) stand on
 * top and may use everything. Each layer is a module of its own in layers/, and its build file
 * names the layers it builds on; this table is what they may be.
 */
class LayersTest {
    private val below = mapOf(
        "art" to setOf(),
        "color" to setOf(),
        "json" to setOf(),
        "sound" to setOf(),
        "math" to setOf(),
        "text" to setOf(),
        // Sendspin, the protocol of Music Assistant's speakers, in plain Kotlin.
        "sendspin" to setOf("json"),
        "domain" to setOf("json", "text"),
        "data" to setOf("domain", "json", "sendspin", "sound"),
        "config" to setOf("color", "data", "domain", "json", "text"),
        "landscape" to setOf("color", "math"),
        "render" to setOf("art", "color", "domain", "math"),
        "lights" to setOf("color", "config", "data", "domain", "landscape", "math", "render"),
        // This TV as a speaker: its connection, its memory, its playback.
        "speaker" to setOf("config", "data", "domain", "json", "sendspin", "sound"),
        "setup" to setOf("config", "data", "domain", "json", "render", "text"),
        "app" to setOf("color", "config", "data", "domain", "landscape", "lights", "math", "render", "setup", "sound", "text"),
        "ui" to setOf("app", "color", "config", "domain", "landscape", "math", "render", "setup", "sound", "text"),
        // The screensaver is an entry point too: it reaches the wiring in the root package.
        "dream" to setOf("app", "render", "ui", ROOT),
    )

    @Test
    fun `every package uses only the ones below it`() {
        val roots = code()
        val packages = roots.flatMap { root -> root.listFiles { f -> f.isDirectory }!!.map { it.name } }.toSet()
        assertWithMessage("packages without a place in the layers").that(packages - below.keys).isEmpty()
        assertWithMessage("layers whose code was not found").that(below.keys - packages).isEmpty()
        val wrong = roots.asSequence().flatMap { sources ->
            sources.walk().filter { it.extension == "kt" }.flatMap { file ->
                val own = file.relativeTo(sources).path.substringBefore(File.separator, missingDelimiterValue = ROOT)
                if (own == ROOT) return@flatMap emptySequence()
                file.readLines().asSequence()
                    .mapNotNull { Regex("""^import io\.github\.hazymorning\.klangbild\.(\w+)""").find(it)?.groupValues?.get(1) }
                    .map { used -> if (used in packages) used else ROOT }
                    .filter { used -> used != own && used !in below.getValue(own) }
                    .map { used -> "${file.relativeTo(sources)} uses $used" }
            }
        }.toList()
        assertWithMessage("imports that point up").that(wrong).isEmpty()
    }

    /**
     * Where the code is, as the build names it: the app's own (the root package and the screensaver)
     * and each layer's. The -testing modules beside the layers hold their tests' helpers.
     */
    private fun code(): List<File> {
        val layers = File(System.getProperty("klangbild.layers")!!).listFiles { f -> f.isDirectory && !f.name.endsWith("-testing") }!!
        return listOf(File(System.getProperty("klangbild.appCode")!!)) + layers.sorted().map { File(it, CODE) }
    }

    private companion object {
        const val ROOT = "the root package"
        const val CODE = "src/main/java/io/github/hazymorning/klangbild"
    }
}
