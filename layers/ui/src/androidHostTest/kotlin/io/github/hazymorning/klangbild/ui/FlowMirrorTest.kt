package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.app.FlowEntry
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.render.FlowCards
import io.github.hazymorning.klangbild.render.FlowGeometry
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.ui.flow.CardMotion
import io.github.hazymorning.klangbild.ui.flow.FlowLook
import io.github.hazymorning.klangbild.ui.flow.FlowStrip
import io.github.hazymorning.klangbild.ui.stage.asColor
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * The mirror images of Cover Flow keep their strength while the cards move: while a card turns
 * from its full texture to its half-size one, and while the strip takes a new look.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class FlowMirrorTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    @Test
    fun aCardTurningAsideDimsItsMirrorImageEvenly() = turn(from = 0f, to = -1f)

    @Test
    fun aCardComingToTheMiddleBrightensItsMirrorImageEvenly() = turn(from = 1f, to = 0f)

    @Test
    fun aCardLeavingTheStripFadesWithoutAJump() = turn(from = 0f, to = -1f, toAlpha = 0f)

    @Test
    fun theMirrorImagesGoOverEvenlyWhileTheMiddleCardTakesANewCover() {
        // The next song of the same card, whose cover differs: the middle card goes over to it.
        // Its mirror image and those beside it change evenly and never swell beyond where they
        // stood before and after.
        val sides = listOf(SyntheticCover.MOSS, SyntheticCover.TERRACOTTA, SyntheticCover.SALT_WATER)
            .map { Fixtures.track(it) }
        val sideScenes = sides.map { Fixtures.scene(it, mdpi, Appearance.DARK, flow = true) }
        val before = Fixtures.track(SyntheticCover.BAUHAUS)
        val after = Fixtures.track(SyntheticCover.NOCTURNE)
        fun strip(middle: io.github.hazymorning.klangbild.domain.TrackInfo) = FlowData(
            listOf(
                FlowEntry(1, sides[0], sideScenes[0]),
                FlowEntry(2, middle, Fixtures.scene(middle, mdpi, Appearance.DARK, flow = true)),
                FlowEntry(3, sides[1], sideScenes[1]),
                FlowEntry(4, sides[2], sideScenes[2]),
            ),
            current = 1,
        )
        var flow by mutableStateOf(strip(before))
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.FlowTheaterOf(flow) }
        compose.mainClock.advanceTimeBy(3_000)
        val geometry = FlowGeometry(sideScenes[0].layout)
        val shift = io.github.hazymorning.klangbild.ui.flow.balanceOf(flow, geometry).cards
        flow = strip(after)
        val side = ArrayList<Float>()
        val middle = ArrayList<Float>()
        for (frame in 0..100) {
            val picture = capture()
            side += mirror(picture, geometry, 1f, shift)
            middle += mirror(picture, geometry, 0f, shift)
            compose.mainClock.advanceTimeBy(16)
        }
        // Beside the new, darker cover the light card steps back; it does so evenly.
        for (values in listOf(side, middle)) {
            for (i in 1 until values.size) assertThat(abs(values[i] - values[i - 1])).isLessThan(3f)
            assertThat(values.max()).isLessThanOrEqualTo(max(values.first(), values.last()) + 1f)
        }
    }

    @Test
    fun aPictureWithClearPartsGivesWayToTheSongsOwnWithoutADarkFlash() {
        // On paper a station's logo, clear around its mark, gives way to the song's own cover as
        // Music Assistant finds it. The print goes over evenly and never darkens beyond both ends.
        val scene = Fixtures.scene(Fixtures.track(SyntheticCover.BAUHAUS), mdpi, Appearance.LIGHT, flow = true)
        val geometry = FlowGeometry(scene.layout)
        val cover = scene.card!!
        val side = cover.sidePx
        val logo = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(logo).drawCircle(side / 2f, side / 2f, side / 5f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A2A4A.toInt() })
        val was = FlowCards.build(logo.asImageBitmap(), cornerPx = scene.layout.radius, geometry = geometry, density = 1f, appearance = Appearance.LIGHT)
        val paper = scene.palette.wall.asColor()
        val cards = listOf(CardMotion(0, cover, 0f, 0f, was = was))
        var look by mutableFloatStateOf(0f)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(paper)) {
                FlowStrip(cards, geometry, Appearance.LIGHT, paper, edges = false, before = FlowLook(Appearance.LIGHT, paper, edges = false, sign = null), look = { look })
            }
        }
        val seen = ArrayList<Float>()
        for (step in 0..40) {
            look = step / 40f
            compose.waitForIdle()
            seen += print(capture(), geometry, 0f)
        }
        assertThat(seen.min()).isGreaterThanOrEqualTo(min(seen.first(), seen.last()) - 2f)
        for (i in 1 until seen.size) assertThat(abs(seen[i] - seen[i - 1])).isLessThan(6f)
    }

    /**
     * One card moving from position [from] to [to] (fading to [toAlpha]) on the black floor, in
     * frames of 16 ms: its mirror image and its print change brightness evenly, frame by frame.
     */
    private fun turn(from: Float, to: Float, toAlpha: Float = 1f) {
        val scene = Fixtures.scene(Fixtures.track(SyntheticCover.BAUHAUS), mdpi, Appearance.DARK, flow = true)
        val geometry = FlowGeometry(scene.layout)
        val cards = listOf(CardMotion(0, scene.card!!, from, to, toAlpha = toAlpha))
        var time by mutableFloatStateOf(0f)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                FlowStrip(cards, geometry, Appearance.DARK, Color.Black, time = { time }, edges = false)
            }
        }
        var mirrorBefore = Float.NaN
        var printBefore = Float.NaN
        for (ms in 0..Motion.FLOW_TURN_MS step 16) {
            time = ms / Motion.FLOW_TURN_MS.toFloat()
            compose.waitForIdle()
            val p = from + (to - from) * Motion.Turn.transform(time)
            val picture = capture()
            val mirror = mirror(picture, geometry, p, 0f)
            val print = print(picture, geometry, p)
            // A card fading out loses its print quickly, but evenly.
            if (!mirrorBefore.isNaN()) {
                assertThat(abs(mirror - mirrorBefore)).isLessThan(1.5f)
                assertThat(abs(print - printBefore)).isLessThan(10f)
            }
            mirrorBefore = mirror
            printBefore = print
        }
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    /** The mean brightness inside the mirror image of the card at [position], [shift] dp to the right. */
    private fun mirror(b: Bitmap, g: FlowGeometry, position: Float, shift: Float): Float {
        val half = g.side / 2f
        return mean(b, g, position, shift, -half, half + g.gap, half, half + g.gap + g.reflection)
    }

    /** The mean brightness inside the print of the card at [position]. */
    private fun print(b: Bitmap, g: FlowGeometry, position: Float): Float {
        val half = g.side / 2f
        return mean(b, g, position, 0f, -half, -half, half, half)
    }

    /** The mean of each pixel's brightest channel inside a rectangle in the plane of the card at [position]. */
    private fun mean(b: Bitmap, g: FlowGeometry, position: Float, shift: Float, left: Float, top: Float, right: Float, bottom: Float): Float {
        val q = FloatArray(8)
        g.cornersOf(position, left, top, right, bottom, q)
        for (i in 0 until 8 step 2) q[i] += shift
        // A pixel inward of the quad's edges, so the filtered rims do not count.
        val x0 = max(0, (min(min(q[0], q[2]), min(q[4], q[6]))).toInt())
        val x1 = min(b.width - 1, (max(max(q[0], q[2]), max(q[4], q[6]))).toInt())
        val y0 = max(0, (min(min(q[1], q[3]), min(q[5], q[7]))).toInt())
        val y1 = min(b.height - 1, (max(max(q[1], q[3]), max(q[5], q[7]))).toInt())
        var sum = 0L
        var count = 0
        for (y in y0..y1) for (x in x0..x1) {
            if (!inside(q, x + 0.5f, y + 0.5f, inset = 1.5f)) continue
            val c = b.getPixel(x, y)
            sum += maxOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
            count++
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    /** Whether ([x], [y]) lies inside the convex quad [q] (clockwise on screen), at least [inset] from its edges. */
    private fun inside(q: FloatArray, x: Float, y: Float, inset: Float): Boolean {
        for (i in 0 until 4) {
            val ax = q[2 * i]
            val ay = q[2 * i + 1]
            val bx = q[(2 * i + 2) % 8]
            val by = q[(2 * i + 3) % 8]
            val ex = bx - ax
            val ey = by - ay
            val length = kotlin.math.sqrt(ex * ex + ey * ey)
            if (length == 0f) return false
            // Clockwise on a screen whose y points down: inside lies to the right of each edge.
            if ((ex * (y - ay) - ey * (x - ax)) / length < inset) return false
        }
        return true
    }
}
