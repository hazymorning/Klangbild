package io.github.hazymorning.klangbild.color

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.domain.SunClock
import org.junit.Test

class CoverPaletteTest {
    private fun solid(argb: Int, n: Int = 48 * 48) = IntArray(n) { argb }

    private fun mix(vararg parts: Pair<Int, Float>): IntArray {
        val out = ArrayList<Int>()
        for ((c, share) in parts) repeat((share * 2304).toInt()) { out += c }
        return out.toIntArray()
    }

    private val covers = listOf(
        solid(0xFF000000.toInt()),
        solid(0xFFFFFFFF.toInt()),
        solid(0xFF808080.toInt()),
        mix(0xFFFF00FF.toInt() to 0.5f, 0xFF00FFFF.toInt() to 0.5f),
        mix(0xFFEEE7D8.toInt() to 0.7f, 0xFFD8512E.toInt() to 0.2f, 0xFF2E5BA6.toInt() to 0.1f),
    )

    @Test
    fun `oklab round trip`() {
        for (c in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0xFF2E5BA6.toInt(), 0xFF7F7F7F.toInt())) {
            assertThat(Oklab.fromArgb(c).toArgb()).isEqualTo(c)
        }
    }

    @Test
    fun `classifies edge cases`() {
        assertThat(CoverPalette.analyze(solid(0xFF070708.toInt())).kind).isEqualTo(CoverKind.DARK)
        assertThat(CoverPalette.analyze(solid(0xFF8A8A8A.toInt())).kind).isEqualTo(CoverKind.MONOCHROME)
        assertThat(CoverPalette.analyze(mix(0xFF0A0A0D.toInt() to 0.95f, 0xFFE9DDBA.toInt() to 0.05f)).kind)
            .isEqualTo(CoverKind.DARK)
        assertThat(CoverPalette.analyze(mix(0xFFF6F5F1.toInt() to 0.97f, 0xFF4060A0.toInt() to 0.03f)).kind)
            .isEqualTo(CoverKind.LIGHT)
        assertThat(CoverPalette.analyze(mix(0xFFFF00FF.toInt() to 0.5f, 0xFF00FFFF.toInt() to 0.5f)).kind)
            .isEqualTo(CoverKind.VIVID)
    }

    @Test
    fun `text stays readable on both walls for every edge case`() {
        for (pixels in covers) for (appearance in Appearance.entries) {
            val p = CoverPalette.derive(CoverPalette.analyze(pixels), appearance)
            assertWithMessage("title $p").that(contrastRatio(p.ink, p.wall)).isAtLeast(if (appearance == Appearance.DARK) 10f else 9f)
            assertWithMessage("quiet $p").that(contrastRatio(p.inkQuiet, p.wall)).isAtLeast(4.5f)
        }
    }

    @Test
    fun `the walls are black and warm paper, never white`() {
        for (pixels in covers) {
            val dark = CoverPalette.derive(CoverPalette.analyze(pixels), Appearance.DARK)
            assertThat(dark.wall).isEqualTo(0xFF000000.toInt())
            val light = CoverPalette.derive(CoverPalette.analyze(pixels), Appearance.LIGHT)
            val paper = Oklab.fromArgb(light.wall)
            assertThat(paper.l).isIn(com.google.common.collect.Range.closed(0.88f, 0.93f))
            // a hint of the cover's color at most
            assertThat(paper.chroma).isAtMost(0.02f)
            // ink never pure black
            assertThat(Oklab.fromArgb(light.ink).l).isAtLeast(0.2f)
        }
    }

    @Test
    fun `the light wall takes a hint of a warm cover's color and stays warm paper for a cool one`() {
        val red = CoverPalette.derive(CoverPalette.analyze(solid(0xFFB0412E.toInt())), Appearance.LIGHT)
        val blue = CoverPalette.derive(CoverPalette.analyze(solid(0xFF2E5BA6.toInt())), Appearance.LIGHT)
        val teal = CoverPalette.derive(CoverPalette.analyze(solid(0xFF1F7A78.toInt())), Appearance.LIGHT)
        val gray = CoverPalette.derive(CoverPalette.analyze(solid(0xFF777777.toInt())), Appearance.LIGHT)
        assertThat(red.lead).isNotNull()
        assertThat(gray.lead).isNull()
        assertThat(Oklab.fromArgb(red.wall).chroma).isGreaterThan(Oklab.fromArgb(gray.wall).chroma)
        // paper is never mint or blue: a cool cover gives it less color, not its hue
        for (p in listOf(red, blue, teal, gray)) {
            assertThat(Oklab.fromArgb(p.wall).hue).isIn(com.google.common.collect.Range.closed(45f, 95f))
        }
        assertThat(Oklab.fromArgb(blue.wall).chroma).isAtMost(Oklab.fromArgb(red.wall).chroma)
        assertThat(Oklab.fromArgb(gray.wall).hue).isWithin(20f).of(CoverPalette.WARM_HUE)
        // the Intensity setting turned off leaves plain paper
        val plain = CoverPalette.derive(CoverPalette.analyze(solid(0xFF2E5BA6.toInt())), Appearance.LIGHT, wallTint = 0f)
        assertThat(Oklab.fromArgb(plain.wall).chroma).isAtMost(Oklab.fromArgb(gray.wall).chroma)
    }

    @Test
    fun `the wall turns with the sun and holds steady near the horizon`() {
        assertThat(Appearance.bySun(30.0)).isEqualTo(Appearance.LIGHT)
        assertThat(Appearance.bySun(-6.0)).isEqualTo(Appearance.DARK)
        // sunset is at −0.833°: just above it is still day …
        assertThat(Appearance.bySun(-0.5)).isEqualTo(Appearance.LIGHT)
        // … and a value wobbling around the line does not flip the wall back and forth
        assertThat(Appearance.bySun(-0.6, previous = Appearance.DARK)).isEqualTo(Appearance.DARK)
        assertThat(Appearance.bySun(-1.0, previous = Appearance.LIGHT)).isEqualTo(Appearance.LIGHT)
        assertThat(Appearance.bySun(0.0, previous = Appearance.DARK)).isEqualTo(Appearance.LIGHT)
        // without Home Assistant the clock decides: day at noon, night at midnight
        assertThat(Appearance.bySun(SunClock.elevation(12, 0))).isEqualTo(Appearance.LIGHT)
        assertThat(Appearance.bySun(SunClock.elevation(23, 30))).isEqualTo(Appearance.DARK)
    }
}
