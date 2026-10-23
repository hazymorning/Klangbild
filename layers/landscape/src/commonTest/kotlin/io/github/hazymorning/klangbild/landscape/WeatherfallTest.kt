package io.github.hazymorning.klangbild.landscape

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import kotlin.math.abs
import kotlin.test.Test

class WeatherfallTest {
    private val out = FloatArray(Weatherfall.STRIDE * 400)

    private fun count(band: Weatherfall.Band, strength: Float, snow: Boolean) =
        Weatherfall.place(band, 1_000.0, 200.0, 0.3f, strength, snow, out)

    @Test
    fun `heavier weather lets more fall, none at all lets nothing`() {
        for ((bands, snow) in listOf(Weatherfall.SNOW to true, Weatherfall.RAIN to false)) for (band in bands) {
            assertThat(count(band, 0f, snow)).isEqualTo(0)
            assertThat(count(band, 0.8f, snow)).isGreaterThan(count(band, 0.3f, snow))
        }
    }

    @Test
    fun `a flake sails on smoothly from frame to frame, even as the wind changes`() {
        val band = Weatherfall.SNOW[1]
        var t = 500.0
        var drift = 100.0
        var wind = 0.2f
        val before = FloatArray(out.size)
        repeat(600) {
            val n0 = Weatherfall.place(band, t, drift, wind, 1f, true, before)
            // A frame later, the wind a little stronger: every flake a little farther, none jumped.
            wind = (wind + 0.003f).coerceAtMost(1f)
            t += 1.0 / 20.0
            drift += wind / 20.0
            val n1 = Weatherfall.place(band, t, drift, wind, 1f, true, out)
            assertThat(n1).isGreaterThanOrEqualTo(n0 - 3)
            for (i in 0 until n1) {
                val x = out[i * Weatherfall.STRIDE]
                val y = out[i * Weatherfall.STRIDE + 1]
                // The same flake a frame earlier: close by (a few dp in a gale), or it just wrapped around and is out of sight.
                val near = (0 until n0).any { j ->
                    abs(before[j * Weatherfall.STRIDE] - x) < 5f && abs(before[j * Weatherfall.STRIDE + 1] - y) < 3f
                }
                val outOfSight = y < 0f || x < -PaperMotif.OVERSCAN || x > PaperMotif.WIDTH + PaperMotif.OVERSCAN
                assertThat(near || outOfSight, name = "flake at $x/$y, $t s").isTrue()
            }
        }
    }

    @Test
    fun `they land, they never vanish in the air`() {
        for (band in Weatherfall.SNOW + Weatherfall.RAIN) {
            var t = 0.0
            repeat(300) {
                val n = Weatherfall.place(band, t, t * 0.2, 0.2f, 1f, band in Weatherfall.SNOW, out)
                for (i in 0 until n) {
                    val y = out[i * Weatherfall.STRIDE + 1]
                    val a = out[i * Weatherfall.STRIDE + 3]
                    if (y > band.ground - 6f) assertThat(a).isLessThan(0.2f)
                }
                t += 0.37
            }
        }
    }

    @Test
    fun `mist only lies where the clearing stays free`() {
        for (depth in listOf(Depth.FAR, Depth.MID)) {
            var t = 0.0
            repeat(200) {
                for (c in MistField.places(t, 1f, depth)) {
                    // Its baseline above the clearing's meadow, where the fire and the tent stand.
                    assertThat(c.y).isLessThan(410f)
                    assertThat(c.alpha).isLessThanOrEqualTo(0.4f)
                }
                t += 13.0
            }
            assertThat(MistField.places(10.0, 0f, depth)).isEmpty()
        }
    }
}
