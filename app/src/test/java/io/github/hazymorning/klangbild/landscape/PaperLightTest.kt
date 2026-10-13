package io.github.hazymorning.klangbild.landscape

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.color.Oklab
import io.github.hazymorning.klangbild.color.contrastRatio
import org.junit.Test
import kotlin.math.abs

class PaperLightTest {
    private val seasons = listOf(SeasonMix.SPRING, SeasonMix.SUMMER, SeasonMix.AUTUMN, SeasonMix.WINTER)

    @Test
    fun `the label keeps four and a half to one on its bank's lightest paper in every light, sky and season`() {
        var worst = Float.MAX_VALUE
        for (season in seasons) for (cover in listOf(0f, 0.45f, 1f)) for (az in listOf(100f, 180f, 260f)) {
            var el = -24f
            while (el <= 62f) {
                for (moon in listOf(0f, 1f)) {
                    val look = PaperLight.look(SkyState(el, az, moonElevation = 30f, moonAzimuth = 170f, moonFraction = moon, cover = cover, season = season))
                    val bank = lighter(look.bank, PaperLight.GRAIN_LIGHTEST)
                    for (ink in look.label) worst = minOf(worst, contrastRatio(ink, bank))
                }
                el += 0.5f
            }
        }
        assertThat(worst).isAtLeast(4.5f)
    }

    @Test
    fun `the label's levels stay apart`() {
        for (el in listOf(-20f, -4f, 2f, 20f, 50f)) {
            val l = PaperLight.look(SkyState(el, 200f)).label.map { Oklab.fromArgb(it).l }
            assertThat(l[0] - l[1]).isAtLeast(0.09f)
            assertThat(l[1] - l[2]).isAtLeast(0.09f)
        }
    }

    @Test
    fun `the light never jumps as the sun moves`() {
        // The colors are worked out again every ten seconds of the sky's time; the sun climbs at
        // most a quarter of a degree a minute. A twentieth of a degree is one or two of those
        // steps, and each changes no color by more than two levels of 255, too little to see.
        for (az in listOf(95f, 180f, 265f)) {
            var prev = PaperLight.look(SkyState(-24f, az))
            var el = -23.95f
            while (el <= 62f) {
                val next = PaperLight.look(SkyState(el, az))
                for (id in listOf(SheetId.SKY, SheetId.RANGE_FAR, SheetId.WOOD_NEAR, SheetId.MEADOW, SheetId.FRONT_LEFT)) {
                    assertWithMessage("$id at $el° (azimuth $az°)").that(step(prev.inks(id).base, next.inks(id).base)).isAtMost(2)
                    assertWithMessage("$id at $el° (azimuth $az°)").that(step(prev.inks(id).first, next.inks(id).first)).isAtMost(2)
                }
                prev = next
                el += 0.05f
            }
        }
    }

    @Test
    fun `morning and evening pass into each other around noon without a jump, and the folds turn with the sun`() {
        var prev = PaperLight.look(SkyState(30f, 150f))
        var az = 150.5f
        while (az <= 210f) {
            val next = PaperLight.look(SkyState(30f, az))
            assertThat(step(prev.inks(SheetId.SKY).second, next.inks(SheetId.SKY).second)).isAtMost(4)
            assertThat(step(prev.inks(SheetId.RANGE_FAR).base, next.inks(SheetId.RANGE_FAR).base)).isAtMost(4)
            prev = next
            az += 0.5f
        }
        // Morning sun from the east (left): the mountains' left faces are the lit ones; in the evening the right.
        fun l(argb: Int) = Oklab.fromArgb(argb).l
        val morning = PaperLight.look(SkyState(15f, 110f)).inks(SheetId.RANGE_FAR)
        val evening = PaperLight.look(SkyState(15f, 250f)).inks(SheetId.RANGE_FAR)
        assertThat(l(morning.base)).isGreaterThan(l(morning.first))
        assertThat(l(evening.first)).isGreaterThan(l(evening.base))
    }

    @Test
    fun `night stays night, the moon lights it a little, the fire more`() {
        val dark = PaperLight.look(SkyState(-25f, 0f))
        val moonlit = PaperLight.look(SkyState(-25f, 0f, moonElevation = 35f, moonAzimuth = 180f, moonFraction = 1f))
        val l = Oklab.fromArgb(dark.inks(SheetId.MEADOW).base).l
        val lm = Oklab.fromArgb(moonlit.inks(SheetId.MEADOW).base).l
        assertThat(l).isLessThan(0.3f)
        assertThat(lm).isGreaterThan(l)
        assertThat(lm).isLessThan(0.4f)
        assertThat(dark.stars).isGreaterThan(0.9f)
        val noon = PaperLight.look(SkyState(50f, 180f))
        assertThat(dark.fire).isGreaterThan(5f * noon.fire)
        assertThat(dark.fireReach).isGreaterThan(noon.fireReach)
    }

    @Test
    fun `overcast mutes the colors without darkening the picture`() {
        val clear = PaperLight.look(SkyState(30f, 200f))
        val gray = PaperLight.look(SkyState(30f, 200f, cover = 1f))
        val c = Oklab.fromArgb(clear.inks(SheetId.MEADOW).base)
        val g = Oklab.fromArgb(gray.inks(SheetId.MEADOW).base)
        assertThat(g.chroma).isLessThan(c.chroma * 0.6f)
        assertThat(abs(g.l - c.l)).isLessThan(0.08f)
        assertThat(gray.discAlpha).isLessThan(0.05f)
    }

    @Test
    fun `autumn colors the broad-leaved crowns and the meadow by day, the firs stay`() {
        val summer = PaperLight.look(SkyState(25f, 200f, season = SeasonMix.SUMMER))
        val autumn = PaperLight.look(SkyState(25f, 200f, season = SeasonMix.AUTUMN))
        fun hue(argb: Int) = Oklab.fromArgb(argb).hue
        assertThat(hue(autumn.inks(SheetId.LEAVES).base)).isLessThan(85f)
        assertThat(hue(summer.inks(SheetId.LEAVES).base)).isGreaterThan(110f)
        assertThat(hue(autumn.inks(SheetId.MEADOW).base)).isLessThan(hue(summer.inks(SheetId.MEADOW).base))
        assertThat(step(summer.inks(SheetId.STANDEES).base, autumn.inks(SheetId.STANDEES).base)).isAtMost(4)
    }

    private fun lighter(argb: Int, k: Float): Int {
        var out = 0xFF shl 24
        for (s in intArrayOf(16, 8, 0)) out = out or (((argb shr s and 0xFF) * k).toInt().coerceIn(0, 255) shl s)
        return out
    }

    private fun step(a: Int, b: Int): Int =
        maxOf(abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)), abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)), abs((a and 0xFF) - (b and 0xFF)))
}
