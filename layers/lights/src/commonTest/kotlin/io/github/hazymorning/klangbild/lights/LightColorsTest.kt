package io.github.hazymorning.klangbild.lights

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.CoverPalette
import io.github.hazymorning.klangbild.color.Lab
import io.github.hazymorning.klangbild.render.CoverField
import io.github.hazymorning.klangbild.render.FieldScene
import kotlin.math.sqrt
import kotlin.test.Test

/** Three lights with a color each, from the cover. */
class LightColorsTest {
    private fun field(vararg colors: Int): FieldScene {
        val n = 48
        // The colors side by side, from left to right.
        val thumb = IntArray(n * n) { i -> colors[(i % n) * colors.size / n] }
        return CoverField.build(thumb, n, CoverPalette.analyze(thumb), Appearance.DARK, 1f, layout = null)
    }

    private fun distance(a: Lab, b: Lab) = sqrt(a.distanceSq(b))

    private fun lights(c: LightColors, t: Double = 0.0) = LightSlot.entries.map { s -> FloatArray(3).also { c.color(s, t, it) } }

    @Test
    fun `every light shows a color of its own, placed as it sits in the cover`() {
        val red = 0xFFC8321E.toInt()
        val blue = 0xFF1E46B4.toInt()
        val yellow = 0xFFE0B020.toInt()
        val (l, c, r) = lights(LightColors(field(red, yellow, blue), moving = false)).map(::labOf)
        for ((a, b) in listOf(l to c, c to r, l to r)) assertThat(distance(a, b)).isGreaterThan(0.09f)
        // Red sits on the left of the cover, blue on its right.
        assertThat(l.a, name = "left light red").isGreaterThan(0.08f)
        assertThat(r.b, name = "right light blue").isLessThan(-0.08f)
    }

    @Test
    fun `a cover of one color gives that color and two of its neighbors, a gray one three whites`() {
        val teal = lights(LightColors(field(0xFF1E8C8C.toInt()), moving = false)).map(::labOf)
        for ((a, b) in teal.zipWithNext()) assertThat(distance(a, b)).isGreaterThan(0.04f)
        val gray = lights(LightColors(field(0xFF808080.toInt()), moving = false)).map(::labOf)
        for (g in gray) assertThat(sqrt(g.a * g.a + g.b * g.b)).isLessThan(0.05f)
    }

    @Test
    fun `with motion the colors move on from light to light, resting in between`() {
        val colors = LightColors(field(0xFFC8321E.toInt(), 0xFFE0B020.toInt(), 0xFF1E46B4.toInt()), moving = true)
        val start = lights(colors, 0.0).map(::labOf)
        // Resting: a little later nothing has changed.
        val rest = lights(colors, LightColors.ROTATE_S * 0.5).map(::labOf)
        for ((a, b) in start.zip(rest)) assertThat(distance(a, b)).isLessThan(0.001f)
        // A whole step later each light shows the color its neighbor showed.
        val next = lights(colors, LightColors.ROTATE_S).map(::labOf)
        assertThat(distance(next[0], start[1])).isLessThan(0.001f)
        assertThat(distance(next[1], start[2])).isLessThan(0.001f)
    }
}
