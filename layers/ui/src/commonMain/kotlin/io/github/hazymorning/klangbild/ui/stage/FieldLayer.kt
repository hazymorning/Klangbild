package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.CoverField
import io.github.hazymorning.klangbild.render.FieldBlob
import io.github.hazymorning.klangbild.render.FieldMotion
import io.github.hazymorning.klangbild.render.imageBitmapOf
import io.github.hazymorning.klangbild.ui.rememberFrames
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

/** The color field's clock as the stage reads it: its time ([now], seconds) and whether it runs. */
@Stable
class FieldTime(val now: () -> Double, val moving: Boolean) {
    companion object {
        val STILL = FieldTime({ 0.0 }, false)
    }
}

/** The field's clock for everything below (the stage provides it; stills and tests get [FieldTime.STILL]). */
val LocalFieldTime = staticCompositionLocalOf { FieldTime.STILL }

/**
 * The cover's colors as the wall ([CoverField], [FieldMotion]), edge to edge and
 * [CoverField.OVERSCAN_DP] beyond, so the stage's drift never shows a border. The ground and
 * the soft pools are drawn into a layer a quarter of the screen's size and scaled up, since
 * they are nothing but soft shapes. Each pool is one quad with its own radial gradient, turned
 * and stretched where [FieldMotion] puts it, so a frame costs a dozen small quads and one scaled
 * copy. Over it, at full resolution, come a grain of a level or two (soft dark ramps would
 * otherwise show bands) and the veil: the shade under the text.
 * While the field moves, it is redrawn with the stage's tick (~30 times a second), nothing else.
 *
 * [alpha] fades it during a change, [level] dims it (pause, a lost link).
 */
@Composable
fun FieldLayer(
    scene: ArtworkScene,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
    level: () -> Float = { 1f },
) {
    val field = scene.field ?: return
    val veil = field.veil
    val base = remember(field) { Color(field.base) }
    val brushes = remember(field) { field.blobs.map(::brush) }
    val places = remember(field) { FloatArray(field.blobs.size * FieldMotion.STRIDE) }
    val dark = field.dark
    val time = LocalFieldTime.current
    // The stage's one tick, shared with the progress bar.
    val frame = rememberFrames(fast = true, running = time.moving)
    val grain = remember { ShaderBrush(ImageShader(Grain.image, TileMode.Repeated, TileMode.Repeated)) }
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        // The field reaches beyond the screen on every side.
        val m = ceil(CoverField.OVERSCAN_DP * density).toInt()
        val fw = w + 2 * m
        val fh = h + 2 * m
        val lw = max(1, (fw + DOWN - 1) / DOWN)
        val lh = max(1, (fh + DOWN - 1) / DOWN)
        Canvas(
            Modifier
                .layout { measurable, _ ->
                    val p = measurable.measure(Constraints.fixed(lw, lh))
                    layout(lw, lh) { p.place(-m, -m) }
                }
                .graphicsLayer {
                    scaleX = fw / lw.toFloat()
                    scaleY = fh / lh.toFloat()
                    transformOrigin = TransformOrigin(0f, 0f)
                    // Drawn small, then scaled: the pools never touch the full resolution.
                    compositingStrategy = CompositingStrategy.Offscreen
                    this.alpha = alpha().coerceIn(0f, 1f)
                    // A little more vivid once mixed: where opposite colors meet, they stay colored, not gray.
                    colorFilter = VIVID
                },
        ) {
            if (time.moving) frame.value // a new frame while the field moves
            val t = time.now()
            // From here on in screen pixels: (0, 0) is the screen's top left corner.
            withTransform({
                scale(size.width / fw, size.height / fh, pivot = Offset.Zero)
                translate(m.toFloat(), m.toFloat())
            }) {
                drawRect(base, topLeft = Offset(-m.toFloat(), -m.toFloat()), size = Size(fw.toFloat(), fh.toFloat()))
                // An index loop: nothing allocated per frame.
                for (i in field.blobs.indices) {
                    val blob = field.blobs[i]
                    val o = i * FieldMotion.STRIDE
                    FieldMotion.place(blob, t, places, o)
                    val r = places[o + 3] * w
                    withTransform({
                        translate(places[o] * w, places[o + 1] * h)
                        rotate(places[o + 2], pivot = Offset.Zero)
                        scale(r, r * blob.aspect, pivot = Offset.Zero)
                    }) {
                        drawRect(brushes[i], topLeft = UNIT_TOP_LEFT, size = UNIT_SIZE, blendMode = if (blob.glow) BlendMode.Screen else BlendMode.SrcOver)
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val a = alpha().coerceIn(0f, 1f)
            if (a <= 0.002f) return@Canvas
            val topLeft = Offset(-m.toFloat(), -m.toFloat())
            val all = Size(fw.toFloat(), fh.toFloat())
            drawRect(grain, topLeft, all, alpha = a, blendMode = BlendMode.Plus)
            veil?.let { drawImage(it, dstOffset = IntOffset(-m, -m), dstSize = IntSize(fw, fh), alpha = a, filterQuality = FilterQuality.Low) }
            // In the dark, a pause (or a lost link) dims the colors; paper stays paper, like the plain wall.
            val dim = if (dark) 1f - level().coerceIn(0f, 1f) else 0f
            if (dim > 0.002f) drawRect(Color.Black, topLeft, all, alpha = a * dim)
        }
    }
}

/** How much smaller than the screen the pools are drawn. */
private const val DOWN = 4

private val UNIT_TOP_LEFT = Offset(-1f, -1f)
private val UNIT_SIZE = Size(2f, 2f)

/** A pool as a radial gradient on the unit circle: its color, its strength falling off at its [FieldBlob.stops]. */
private fun brush(b: FieldBlob): Brush {
    val color = Color(b.color)
    val stops = Array(FieldBlob.STOPS + 1) { i -> i / FieldBlob.STOPS.toFloat() to color.copy(alpha = b.alpha * b.stops[i]) }
    return Brush.radialGradient(*stops, center = Offset.Zero, radius = 1f)
}

/** Saturation after the pools have mixed ([CoverField.SATURATION]). */
private val VIVID = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(CoverField.SATURATION) })

/** A tile of fine noise (0 to 2 levels). Added to the field, it breaks up the bands of dark ramps. */
private object Grain {
    private const val SIDE = 64

    val image by lazy {
        val r = Random(23)
        val px = IntArray(SIDE * SIDE) {
            val v = r.nextInt(3)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        imageBitmapOf(px, SIDE, SIDE)
    }
}
