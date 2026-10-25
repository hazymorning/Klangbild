package io.github.hazymorning.klangbild.art

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import kotlin.math.abs
import kotlin.math.sign
import kotlin.test.Test

class SleeveArtTest {
    @Test
    fun `one artist always gets the same foil, whatever the album and however the name is written`() {
        val foil = SleeveArt.foilOf(SleeveArt.Seed("Theo Lind", "Morning Fog"))
        assertThat(SleeveArt.foilOf(SleeveArt.Seed("Theo Lind", "Kreis"))).isEqualTo(foil)
        assertThat(SleeveArt.foilOf(SleeveArt.Seed(" theo lind", "Kreis"))).isEqualTo(foil)
        // The view follows the album: of these, at least one differs.
        val views = listOf("Morning Fog", "Kreis", "Low Hills", "Abendlied").map { SleeveArt.viewOf(SleeveArt.Seed("Theo Lind", it)) }
        assertThat(views.toSet().size).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `without a view the rings rise from below, and without a foil the sleeve is plain`() {
        assertThat(SleeveArt.viewOf(SleeveArt.Seed("Radio Morgenlicht", null))).isEqualTo(SleeveArt.rising)
        assertThat(SleeveArt.foilOf(SleeveArt.Seed(null, null))).isNull()
        val px = SleeveArt.render(SleeveArt.Seed(null, null), 96)
        for (p in px) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            assertThat(maxOf(abs(r - g), abs(g - b), abs(r - b))).isLessThanOrEqualTo(1)
        }
        assertThat(SleeveArt.render(SleeveArt.Seed("Theo Lind", "Kreis"), 96).toList()).isNotEqualTo(px.toList())
    }

    @Test
    fun `the same seed gives the same pixels every time`() {
        val seed = SleeveArt.Seed("Clara Wendt", "Nachtfahrt")
        assertThat(SleeveArt.render(seed, 120).toList()).isEqualTo(SleeveArt.render(seed, 120).toList())
    }

    /**
     * In every view each line either keeps a tenth of the side clear of an edge or crosses it
     * steeply: a line running close along an edge, or crossing it at a slant, looks like a mistake.
     */
    @Test
    fun `no line runs along an edge`() {
        for (view in SleeveArt.views + SleeveArt.rising) {
            val k = 1024f / view.zoom
            for (line in 0 until SleeveArt.lineCount) {
                for (edge in 0 until 4) {
                    val n = 2000
                    val d = FloatArray(n + 1) { s ->
                        val t = s.toFloat() / n
                        val (u, v) = when (edge) {
                            0 -> t to 0f
                            1 -> t to 1f
                            2 -> 0f to t
                            else -> 1f to t
                        }
                        SleeveArt.centerline(line, (u - view.x) * k, (v - view.y) * k) / k
                    }
                    val crossings = (0 until n).filter { sign(d[it]) != sign(d[it + 1]) }
                    for (c in crossings) {
                        val a = maxOf(c - 8, 0)
                        val b = minOf(c + 9, n)
                        val slope = abs(d[b] - d[a]) / ((b - a).toFloat() / n)
                        assertThat(slope, name = "$view, line $line, edge $edge: crossing").isGreaterThanOrEqualTo(0.5f)
                    }
                    for (s in 1 until n) {
                        val local = abs(d[s]) <= abs(d[s - 1]) && abs(d[s]) <= abs(d[s + 1])
                        if (local && crossings.none { abs(it - s) <= 8 }) {
                            assertThat(abs(d[s]), name = "$view, line $line, edge $edge: near miss").isGreaterThanOrEqualTo(0.09f)
                        }
                    }
                }
            }
        }
    }
}
