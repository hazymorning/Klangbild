package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A value whose choice is on its way (the queue's way while its stage is made) shimmers: the word
 * waits one step quieter, and a soft band of its own ink passes over it from left to right at an
 * even pace, then rests a moment, and passes again while the wait goes on. A pass that has begun
 * always ends, so even the shortest wait shows one whole pass and nothing flickers.
 */
object Shimmer {
    /**
     * The band's width (dp): the word's own ink in its middle, falling off as cos² to either side.
     * A narrower band lights only a letter or two and looks too faint.
     */
    const val BAND_DP = 96f

    /** One pass, from the band's right edge at the word's start to its left edge at the word's end. */
    const val PASS_MS = 1_200L

    /** The rest after a pass. */
    const val REST_MS = 400L

    /** The word's ink eases into its quieter one, and back, in this long (as the inks of a chosen entry do). */
    const val INK_MS = 240

    /** How much of the word's own ink the band holds, at even steps across it. */
    internal val PROFILE = FloatArray(17) { i -> sin(PI * i / 16).toFloat().let { it * it } }

    /**
     * Where the band's left edge stands (px from the word's start) at [progress] (0 to 1) of a pass
     * over a word [width] px wide, with the band [band] px wide.
     */
    fun bandLeft(progress: Float, width: Float, band: Float): Float = -band + (width + band) * progress
}

/** A shimmer's state: whether it runs, and where its pass is. */
@Stable
class Sweep internal constructor() {
    /** The word waits in its quieter ink: from the first pass until the last has ended. */
    var running by mutableStateOf(false)
        private set

    /** A band crosses the word now. */
    var passing by mutableStateOf(false)
        private set

    /** When the pass under way began (frame time, ns). */
    private var passStart = 0L

    /** How far the pass under way has come at frame time [frameNanos] (0 to 1); null between passes. */
    fun progress(frameNanos: Long): Float? =
        if (!passing) null else ((frameNanos - passStart) / (Shimmer.PASS_MS * 1e6f)).coerceIn(0f, 1f)

    /** Passes while [wanted] holds, each finished, with a rest between them. */
    internal suspend fun runWhile(wanted: () -> Boolean) {
        while (true) {
            snapshotFlow(wanted).first { it }
            running = true
            do {
                passStart = withFrameNanos { it }
                passing = true
                delay(Shimmer.PASS_MS)
                passing = false
                // The wait over during the pass or the rest: the word goes back to its ink.
                val over = withTimeoutOrNull(Shimmer.REST_MS) { snapshotFlow(wanted).first { !it } } != null
            } while (!over)
            running = false
        }
    }
}

/** A shimmer that runs while [active]; nothing runs in a still. */
@Composable
fun rememberSweep(active: Boolean): Sweep {
    val sweep = remember { Sweep() }
    val wanted = rememberUpdatedState(active)
    if (!LocalStill.current) LaunchedEffect(sweep) { sweep.runWhile { wanted.value } }
    return sweep
}

/**
 * [text] in [ink], shimmering while [sweep] runs: it waits in the quieter [waiting] ink and the
 * band brings [ink] back as it passes. At rest it is plain text, exactly as without a shimmer.
 */
@Composable
fun ShimmerText(text: String, style: TextStyle, sweep: Sweep, ink: () -> Color, waiting: Color, alpha: () -> Float = { 1f }) {
    val quiet = animateFloatAsState(if (sweep.running) 1f else 0f, tween(Shimmer.INK_MS, easing = Motion.Dimmer), label = "shimmer-quiet")
    val waitingInk = animateColorAsState(waiting, tween(Shimmer.INK_MS, easing = Motion.Dimmer), label = "shimmer-waiting")
    // Back in its own ink, the word is plain text again; the two draw the same pixels, so the
    // change between them never shows.
    val settled by remember { derivedStateOf { quiet.value == 0f } }
    if (settled && !sweep.running) {
        BasicText(text, style = style, color = { ink().let { it.copy(alpha = it.alpha * alpha()) } }, maxLines = 1)
    } else {
        SweptWord(text, style, sweep, word = { mix(ink(), waitingInk.value, quiet.value) }, ink = ink, alpha = alpha)
    }
}

/**
 * [text] drawn in [word], with the band of [ink] passing over it while [sweep] is in a pass. It
 * is measured once and drawn in its draw phase: a frame only draws it anew, never composes or
 * measures it, and needs no layer. Only the band is drawn with a gradient; beside it the word is
 * drawn plainly, since text drawn with a gradient comes out a little heavier at its soft edges
 * than plain text (drawn whole with one, the word stepped brighter as the band came in). Its
 * first baseline is that of [BasicText], so it stands where the plain text stands.
 */
@Composable
internal fun SweptWord(text: String, style: TextStyle, sweep: Sweep, word: () -> Color, ink: () -> Color, alpha: () -> Float) {
    val measurer = rememberTextMeasurer(cacheSize = 2)
    val layout = remember(text, style, measurer) { measurer.measure(text, style, maxLines = 1) }
    // The frames a pass is drawn on, only while a band crosses. The stage's own tick does not
    // reach the quick settings or the menu, which lie over every screen (the landscape keeps time
    // of its own, and the stage's tick may run slowly or stop, as it does under the menu), so a
    // pass runs a loop of its own for its 1.2 s, at the stage's pace; nothing runs between passes.
    val frames = rememberFrames(fast = true, running = sweep.passing)
    Layout(
        Modifier.drawBehind {
            val w = word()
            val a = alpha()
            val band = Shimmer.BAND_DP.dp.toPx()
            val left = sweep.progress(frames.value)?.let { Shimmer.bandLeft(it, size.width, band) }
            val plain = w.copy(alpha = w.alpha * a)
            if (left == null || left >= size.width || left + band <= 0f) {
                drawText(layout, color = plain)
            } else {
                // Room above and below for whatever reaches beyond the line's box.
                val top = -size.height
                val bottom = 2 * size.height
                if (left > 0f) clipRect(-size.width, top, left, bottom) { drawText(layout, color = plain) }
                if (left + band < size.width) clipRect(left + band, top, 2 * size.width, bottom) { drawText(layout, color = plain) }
                val i = ink()
                val last = Shimmer.PROFILE.size - 1
                val stops = Array(Shimmer.PROFILE.size) { k -> k.toFloat() / last to mix(w, i, Shimmer.PROFILE[k]) }
                clipRect(left, top, left + band, bottom) {
                    drawText(layout, Brush.horizontalGradient(*stops, startX = left, endX = left + band), alpha = a)
                }
            }
        },
    ) { _, constraints ->
        val width = layout.size.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = layout.size.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height, mapOf(FirstBaseline to layout.firstBaseline.roundToInt())) {}
    }
}

/** [a] turned [f] of the way to [b]; exactly [a] and [b] at the ends, so a word at rest keeps its very ink. */
private fun mix(a: Color, b: Color, f: Float): Color = when {
    f <= 0f -> a
    f >= 1f -> b
    else -> lerp(a, b, f)
}
