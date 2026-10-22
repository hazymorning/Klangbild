package io.github.hazymorning.klangbild

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The packages build on each other in one direction: each uses only the ones listed for it. The
 * model knows nothing of Home Assistant or the screen, the data layer nothing of the settings,
 * and the settings nothing of the screen. MainActivity and the wiring (KlangbildApp) stand on
 * top and may use everything.
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
        val sources = File("src/main/java/io/github/hazymorning/klangbild")
        val packages = sources.listFiles { f -> f.isDirectory }!!.map { it.name }.toSet()
        assertWithMessage("packages without a place in the layers").that(packages - below.keys).isEmpty()
        val wrong = sources.walk().filter { it.extension == "kt" }.flatMap { file ->
            val own = file.relativeTo(sources).path.substringBefore(File.separator, missingDelimiterValue = ROOT)
            if (own == ROOT) return@flatMap emptySequence()
            file.readLines().asSequence()
                .mapNotNull { Regex("""^import io\.github\.hazymorning\.klangbild\.(\w+)""").find(it)?.groupValues?.get(1) }
                // R is the app's resources, which every layer may read.
                .map { used -> if (used in packages || used == "R") used else ROOT }
                .filter { used -> used != own && used != "R" && used !in below.getValue(own) }
                .map { used -> "${file.relativeTo(sources)} uses $used" }
        }.toList()
        assertWithMessage("imports that point up").that(wrong).isEmpty()
    }

    private companion object {
        const val ROOT = "the root package"
    }
}
