package io.github.hazymorning.klangbild.render

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.art.ChladniArt
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.CoverPalette
import io.github.hazymorning.klangbild.color.Oklab
import io.github.hazymorning.klangbild.color.contrastRatio
import io.github.hazymorning.klangbild.color.relativeLuminance
import io.github.hazymorning.klangbild.ui.stage.BLOCK_SHARE
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Design principles as tests, so polishing never quietly breaks them. */
class DesignRulesTest {
    private val screen = ScreenSpec.TV_1080
    private val layout = StageGrid(screen)
    private val shape = PrintShape(layout.artRect, corner = layout.radius)

    /** A 48 × 48 thumbnail: the top [split] of the rows in [top], the rest in [bottom]. */
    private fun thumb(top: Int, bottom: Int = top, split: Float = 0.5f): IntArray =
        IntArray(48 * 48) { i -> if (i / 48 < (48 * split).toInt()) top else bottom }

    private fun glow(thumb: IntArray) = WallGlow.field(thumb, 48, shape, screen)

    /** The glow at (x, y) dp on the 960 × 540 map. */
    private fun IntArray.at(xDp: Float, yDp: Float): Int = this[yDp.toInt() * WallGlow.MAP_W + xDp.toInt()]

    private fun luminance(argb: Int) = relativeLuminance(argb)

    private fun contrast(a: Int, b: Int) = contrastRatio(a, b)

    private val covers = listOf(
        0xFFF2EEE6.toInt(), // white paper
        0xFFE8C547.toInt(), // yellow
        0xFFE0457B.toInt(), // pink
        0xFF2A6FD6.toInt(), // blue
        0xFF0E5A66.toInt(), // teal
        0xFF101010.toInt(), // black
    )

    // --- The light on the wall ---------------------------------------------------------

    @Test
    fun `no light at all near the screen edges`() {
        for (c in covers) {
            val field = glow(thumb(c))
            for (y in 0 until WallGlow.MAP_H) for (x in 0 until WallGlow.MAP_W) {
                val edge = minOf(x + 0.5f, WallGlow.MAP_W - x - 0.5f, y + 0.5f, WallGlow.MAP_H - y - 0.5f)
                if (edge < WallGlow.EDGE_BLACK_DP - 1f) assertWithMessage("($x, $y)").that(field[y * WallGlow.MAP_W + x] and 0xFFFFFF).isEqualTo(0)
            }
        }
    }

    @Test
    fun `a tight halo, the same on every side, gone before the text column`() {
        val field = glow(thumb(0xFFF2EEE6.toInt()))
        val r = layout.artRect
        val near = luminance(field.at(r.right + 2f, r.centerY))
        assertThat(near).isIn(Range.closed(WallGlow.EDGE_LUMINANCE * 0.8f, WallGlow.EDGE_LUMINANCE * 1.1f))
        // the same at 12 dp on all four sides (no side cut short by the screen's edge)
        val sides = listOf(
            field.at(r.right + 12f, r.centerY),
            field.at(r.left - 12f, r.centerY),
            field.at(r.centerX, r.top - 12f),
            field.at(r.centerX, r.bottom + 12f),
        ).map { luminance(it) }
        for (s in sides) assertThat(s).isWithin(near * 0.12f).of(sides.first())
        assertThat(sides.first()).isLessThan(near)
        // the text column stands on black
        assertThat(field.at(layout.columnLeft + 1f, r.centerY) and 0xFFFFFF).isEqualTo(0)
        // dark behind the artwork, lit just under its edge (no dark rim around it)
        assertThat(field.at(r.centerX, r.centerY) and 0xFFFFFF).isEqualTo(0)
        assertThat(luminance(field.at(r.right - 1f, r.centerY))).isGreaterThan(near * 0.2f)
    }

    @Test
    fun `the glow takes the color of the nearest edge`() {
        // violet above, teal below
        val field = glow(thumb(0xFF6A2BC2.toInt(), 0xFF0E8A86.toInt()))
        val above = Oklab.fromArgb(field.at(layout.artRect.centerX, layout.artRect.top - 6f))
        val below = Oklab.fromArgb(field.at(layout.artRect.centerX, layout.artRect.bottom + 6f))
        assertThat(above.hue).isIn(Range.closed(270f, 320f))
        assertThat(below.hue).isIn(Range.closed(170f, 215f))
    }

    @Test
    fun `dark edges glow little, and no glow is a neon sign`() {
        val bright = luminance(glow(thumb(0xFFE0457B.toInt())).at(layout.artRect.right + 2f, layout.artRect.centerY))
        val dark = luminance(glow(thumb(0xFF101010.toInt())).at(layout.artRect.right + 2f, layout.artRect.centerY))
        // a picture lights the wall at most as much as it is bright itself
        assertThat(dark).isLessThan(bright * 0.2f)
        for (c in covers) {
            val lab = Oklab.fromArgb(glow(thumb(c)).at(layout.artRect.right + 2f, layout.artRect.centerY))
            // chroma at the glow's own (low) lightness stays modest
            assertThat(lab.chroma).isAtMost(WallGlow.MAX_CHROMA)
        }
    }

    @Test
    fun `the quietest text stays readable on the glow`() {
        for (c in covers) {
            val field = glow(thumb(c))
            val palette = CoverPalette.derive(CoverPalette.analyze(thumb(c)))
            // the brightest wall under the text column: its left edge, level with the artwork's center
            val wall = field.at(layout.columnLeft + 1f, layout.textCenter)
            assertWithMessage("cover " + Integer.toHexString(c)).that(contrast(palette.inkQuiet, wall)).isAtLeast(4.5f)
            assertWithMessage("cover " + Integer.toHexString(c)).that(contrast(palette.ink, wall)).isAtLeast(10f)
        }
    }

    // --- Color of the text --------------------------------------------------------------

    @Test
    fun `text is near-neutral, never pure white, never ivory`() {
        for (c in covers) {
            val p = CoverPalette.derive(CoverPalette.analyze(thumb(c)))
            val ink = Oklab.fromArgb(p.ink)
            assertThat(p.ink and 0xFFFFFF).isNotEqualTo(0xFFFFFF)
            assertThat(ink.l).isIn(Range.closed(0.88f, 0.95f))
            assertThat(ink.chroma).isAtMost(0.015f)
        }
        // a neutral cover gets neutral text
        val grey = CoverPalette.derive(CoverPalette.analyze(thumb(0xFF808080.toInt())))
        assertThat(Oklab.fromArgb(grey.ink).chroma).isAtMost(0.007f)
    }

    @Test
    fun `three clear text levels on both walls, every one at least four and a half to one`() {
        for (appearance in Appearance.entries) {
            val p = CoverPalette.derive(CoverPalette.analyze(thumb(0xFF2A6FD6.toInt())), appearance)
            val tiers = listOf(p.ink, p.inkMuted, p.inkQuiet)
            // each level clearly apart from the one before it, away from the wall
            val l = tiers.map { Oklab.fromArgb(it).l }
            for (i in 1 until l.size) assertWithMessage("$appearance").that(abs(l[i - 1] - l[i])).isAtLeast(0.095f)
            for (ink in tiers) assertWithMessage("$appearance").that(contrast(ink, p.wall)).isAtLeast(4.5f)
        }
    }

    // --- The grid -------------------------------------------------------------------------

    @Test
    fun `the artwork is the heaviest element and sets the height`() {
        // two thirds of the screen's height
        assertThat(layout.art).isWithin(0.01f).of(368f)
        // the text block takes clearly less height than the artwork
        assertThat(BLOCK_SHARE).isAtMost(0.65f)
        // the quiet lines frame the column within the artwork's straight edge, a corner radius in
        assertThat(layout.statusTop).isWithin(0.01f).of(layout.artRect.top + layout.radius)
        assertThat(layout.footerBottom).isWithin(0.01f).of(layout.artRect.bottom - layout.radius)
        // the group stands exactly in the screen's middle: as much room above as below …
        assertThat(layout.artRect.centerY).isWithin(0.01f).of(screen.heightDp / 2f)
        // … and as much left of the artwork as right of the column; 64 dp between
        assertThat(layout.artRect.left).isWithin(0.01f).of(88f)
        assertThat(screen.widthDp - (layout.columnLeft + layout.columnWidth)).isWithin(0.01f).of(layout.artRect.left)
        assertThat(layout.columnLeft - layout.artRect.right).isWithin(0.01f).of(64f)
    }

    @Test
    fun `the artwork has softly rounded corners`() {
        val r = layout.radius
        assertThat(r / layout.art).isIn(Range.closed(0.015f, 0.03f))
        val rect = layout.artRect
        // the very corner of the square is outside the rounded artwork …
        assertThat(shape.contains(rect.left + 0.5f, rect.top + 0.5f)).isFalse()
        // … the middle of an edge is not
        assertThat(shape.contains(rect.centerX, rect.top + 0.5f)).isTrue()
        assertThat(shape.distance(rect.left - 10f, rect.centerY)).isWithin(0.01f).of(10f)
    }

    // --- Generated art -------------------------------------------------------------------

    @Test
    fun `sand figures are mirror-symmetric, so none can look like a hooked cross`() {
        val rnd = Random(7)
        for (seed in listOf("Radio Morgenlicht", "Jazzradio", "BR-Klassik", "FIP", "SWR2", "WDR 3", "x", "Klangbild")) {
            val spec = ChladniArt.specFor(seed)
            repeat(50) {
                val r = rnd.nextFloat()
                val t = rnd.nextFloat() * 6.283f
                assertThat(ChladniArt.field(spec, r, t)).isWithin(1e-4f).of(ChladniArt.field(spec, r, -t))
            }
        }
    }

    @Test
    fun `sand figures are wavy rings, never a cross, a wheel or a rosette`() {
        val seen = HashSet<Pair<Int, Int>>()
        for (i in 0 until 300) {
            val spec = ChladniArt.specFor("Sender $i")
            // no nodal diameters: through the center they make crosses, wheels and petals
            assertThat(spec.n1).isEqualTo(0)
            assertThat(spec.n2).isIn(setOf(2, 3, 4, 5, 6, 8))
            // the second mode only bends the circles
            assertThat(kotlin.math.abs(spec.mix)).isAtMost(ChladniArt.MAX_MIX)
            // the middle stays free: the field there is the ring mode's
            assertThat(ChladniArt.field(spec, 0.05f, 1f)).isGreaterThan(0.8f)
            seen += Math.round(spec.k1 * 0.7f / 3f) to spec.n2
        }
        // variety: two or three circles, many lobe counts
        assertThat(seen.size).isAtLeast(8)
    }
}
