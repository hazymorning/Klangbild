package io.github.hazymorning.klangbild.ui.landscape

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.ui.Fixtures
import io.github.hazymorning.klangbild.ui.composeRule
import io.github.hazymorning.klangbild.landscape.CameraPose
import io.github.hazymorning.klangbild.landscape.LabelSide
import io.github.hazymorning.klangbild.landscape.PaperBake
import io.github.hazymorning.klangbild.landscape.PaperLight
import io.github.hazymorning.klangbild.landscape.SeasonShapes
import io.github.hazymorning.klangbild.landscape.SkyMap
import io.github.hazymorning.klangbild.landscape.SkyState
import io.github.hazymorning.klangbild.landscape.LandscapeCamera
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/**
 * The camera's journey: in every corner of its range, and with the screen's own drift against
 * burn-in on top, the picture fills the screen. No edge of any layer shows, at the screen's
 * edges or between the layers. And the layers part as they should: the near ones move most (a
 * contact sheet of the journey).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LandscapeCameraTest {
    @get:Rule
    val compose = composeRule()

    private val baked = HashMap<LabelSide, PaperImages>()
    private fun imagesFor(side: LabelSide) = baked.getOrPut(side) { PaperImages(PaperBake.bake(1920, 1080, 2f, SeasonShapes(flowers = 1f), side)) }
    private val images get() = imagesFor(LabelSide.LEFT)
    private val look = PaperLight.look(SkyState(sunElevation = 32f, sunAzimuth = 200f))
    private val places = SkyMap.places(SkyState(sunElevation = 32f, sunAzimuth = 200f))

    private var shown by mutableStateOf<(@Composable () -> Unit)?>(null)

    private fun render(pose: CameraPose, driftX: Float = 0f, driftY: Float = 0f, side: LabelSide = LabelSide.LEFT): Bitmap {
        val content: @Composable () -> Unit = {
            Fixtures.Still {
                Box(Modifier.fillMaxSize().background(MAGENTA)) {
                    PaperView(
                        imagesFor(side), { look }, { places }, { PaperMotion(0.0, emptyList(), pose) },
                        Modifier.fillMaxSize().offset(driftX.dp, driftY.dp),
                    )
                }
            }
        }
        if (shown == null) compose.setContent { shown?.invoke() }
        shown = content
        compose.waitForIdle()
        return compose.onRoot().captureToImage().asAndroidBitmap()
    }

    @Test
    fun noEdgeEverShows() {
        val drift = LandscapeCamera.DRIFT
        // At an end of the camera's range only the drift toward that end can bring an edge in:
        // the picture moves further that way. A range without width gets both.
        fun outward(v: Float, lo: Float, hi: Float) = when {
            lo == hi -> listOf(-drift, drift)
            v == lo -> listOf(drift)
            else -> listOf(-drift)
        }
        for (side in LabelSide.entries) for (zoom in listOf(LandscapeCamera.ZOOM_MIN, (LandscapeCamera.ZOOM_MIN + LandscapeCamera.ZOOM_MAX) / 2f, LandscapeCamera.ZOOM_MAX)) {
            val (x0, x1) = LandscapeCamera.rangeX(zoom, side)
            for (cx in listOf(x0, x1).distinct()) {
                val (y0, y1) = LandscapeCamera.rangeY(zoom, side, cx)
                for (cy in listOf(y0, y1).distinct()) for (dx in outward(cx, x0, x1)) for (dy in outward(cy, y0, y1)) {
                    val bmp = render(LandscapeCamera.pose(zoom, cx, cy), dx, dy, side)
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    val bad = px.count { it == MAGENTA_ARGB }
                    assertWithMessage("$side, zoom $zoom, view at $cx/$cy, drift $dx/$dy: background showing").that(bad).isEqualTo(0)
                }
            }
        }
    }

    @Test
    fun theCutOutsSwayInTheBreeze() {
        fun at(t: Double): Bitmap {
            val content: @Composable () -> Unit = {
                Fixtures.Still { PaperView(images, { look }, { places }, { PaperMotion(t, emptyList()) }, Modifier.fillMaxSize()) }
            }
            if (shown == null) compose.setContent { shown?.invoke() }
            shown = content
            compose.waitForIdle()
            return compose.onRoot().captureToImage().asAndroidBitmap()
        }
        val a = at(1_000.0)
        val b = at(1_004.0)
        // The right group of cut-outs (dp 730 to 850 across, 320 to 450 down; 2 px a dp).
        var changed = 0
        for (y in 640 until 900) for (x in 1460 until 1700) if (a.getPixel(x, y) != b.getPixel(x, y)) changed++
        assertWithMessage("pixels that moved with the breeze").that(changed).isGreaterThan(40)
    }

    @Category(Pictures::class)
    @Test
    fun theLayersDriftApart() {
        // Three minutes of the camera, a frame every half minute: the near banks move most.
        for (i in 0..6) {
            val pose = LandscapeCamera.at(i * 30.0 + 1_000.0, LabelSide.LEFT)
            captureRoboImage("build/screenshots/landscape/camera/drift_%02d.png".format(i)) {
                Fixtures.Still { PaperView(images, { look }, { places }, { PaperMotion(0.0, emptyList(), pose) }, Modifier.fillMaxSize()) }
            }
        }
    }

    @Category(Pictures::class)
    @Test
    fun theLabelStandsStillOnItsBank() {
        // The label at the corners of the camera's range, on either side: always on its dark bank.
        val track = Fixtures.track(io.github.hazymorning.klangbild.art.SyntheticCover.NIGHT_DRIVE)
        val scene = Fixtures.stage(track).scene
        var i = 0
        for (side in listOf(LabelSide.LEFT, LabelSide.RIGHT)) for (zoom in listOf(LandscapeCamera.ZOOM_MIN, LandscapeCamera.ZOOM_MAX)) {
            val (x0, x1) = LandscapeCamera.rangeX(zoom, side)
            for (cx in listOf(x0, x1)) {
                val (y0, _) = LandscapeCamera.rangeY(zoom, side, cx)
                val pose = LandscapeCamera.pose(zoom, cx, y0)
                captureRoboImage("build/screenshots/landscape/camera/label_%02d.png".format(i++)) {
                    Fixtures.Still {
                        Box(Modifier.fillMaxSize()) {
                            PaperView(imagesFor(side), { look }, { places }, { PaperMotion(0.0, emptyList(), pose) }, Modifier.fillMaxSize())
                            LandscapeLabel(LabelText(track, scene, true) { (track.durationMs ?: 0L) / 3 }, side, inks = { look.label })
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val MAGENTA = Color(0xFFFF00FF)
        const val MAGENTA_ARGB = 0xFFFF00FF.toInt()
    }
}
