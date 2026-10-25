package io.github.hazymorning.klangbild

import assertk.assertThat
import assertk.assertions.isEmpty
import org.junit.Test
import java.io.File

/**
 * The packages build on each other in one direction: each uses only the ones listed for it. The
 * model knows nothing of Home Assistant or the screen, the data layer nothing of the settings,
 * and the settings nothing of the screen. The app itself (MainActivity, KlangbildApp) stands on
 * top and may use everything. Each layer is a module of its own in layers/, and its build file
 * names the layers it builds on; this table is what they may be.
 */
class LayersTest {
    private val below = mapOf(
        "art" to setOf("color"),
        "color" to setOf("math"),
        "json" to setOf(),
        "sound" to setOf(),
        "math" to setOf(),
        "text" to setOf(),
        // Sendspin, the protocol of Music Assistant's speakers, in plain Kotlin.
        "sendspin" to setOf("json"),
        "domain" to setOf("json", "text"),
        "data" to setOf("domain", "json", "math", "sendspin", "sound"),
        "config" to setOf("color", "data", "domain", "json", "text"),
        "render" to setOf("art", "color", "domain", "math"),
        "lights" to setOf("color", "config", "data", "domain", "math", "render"),
        // This TV as a speaker: its connection, its memory, its playback.
        "speaker" to setOf("config", "data", "domain", "json", "sendspin", "sound"),
        "setup" to setOf("config", "data", "domain", "json", "render", "text"),
        "app" to setOf("color", "config", "data", "domain", "lights", "math", "render", "setup", "sound", "speaker", "text"),
        "ui" to setOf("app", "color", "config", "domain", "math", "render", "setup", "sound", "text"),
        // The screensaver is an entry point too: it reaches the wiring in the root package.
        "dream" to setOf("app", "render", "ui", ROOT),
    )

    @Test
    fun `every package uses only the ones below it`() {
        val roots = code()
        val packages = roots.flatMap { root -> root.listFiles { f -> f.isDirectory }!!.map { it.name } }.toSet()
        assertThat(packages - below.keys, name = "packages without a place in the layers").isEmpty()
        assertThat(below.keys - packages, name = "layers whose code was not found").isEmpty()
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
        assertThat(wrong, name = "imports that point up").isEmpty()
    }

    /**
     * Where the code is, as the build names it: the app's own (the root package and the screensaver)
     * and each layer's, common and for each platform. The -testing modules beside the layers hold
     * their tests' helpers.
     */
    private fun code(): List<File> {
        val layers = File(System.getProperty("klangbild.layers")!!).listFiles { f -> f.isDirectory && !f.name.endsWith("-testing") }!!
        val sources = layers.sorted().flatMap { layer ->
            File(layer, "src").listFiles { f -> f.name.endsWith("Main") }!!.sorted().map { File(it, CODE) }.filter { it.isDirectory }
        }
        return listOf(File(System.getProperty("klangbild.appCode")!!)) + sources
    }

    private companion object {
        const val ROOT = "the root package"
        const val CODE = "kotlin/io/github/hazymorning/klangbild"
    }
}
