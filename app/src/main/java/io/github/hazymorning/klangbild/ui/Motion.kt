package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * True while rendering a still (screenshots): nothing runs a loop of its own, so the
 * composition can come to rest.
 */
val LocalStill = staticCompositionLocalOf { false }

/**
 * One clock for what moves by itself on the stage (the progress bar and the color field), so
 * both are redrawn in the same frame. Two loops out of step would double the frames drawn.
 * [frame] changes about 30 times a second while something moves, twice a second otherwise.
 */
@Stable
class StageTick(val frame: State<Long>)

/** The stage's tick; null where nobody provides one (a lone bar then keeps its own loop). */
val LocalStageTick = staticCompositionLocalOf<StageTick?> { null }

/**
 * A tick for the stage: [fast] while music plays or the colors move. While nothing reads it
 * (no [running]: no bar shown, the colors still, or the settings over the stage) it stops.
 */
@Composable
fun rememberStageTick(fast: Boolean, running: Boolean = true): StageTick {
    val frame = rememberFrameLoop(fast, running, TICK_MS)
    return remember(frame) { StageTick(frame) }
}

/**
 * The frames something that moves by itself is redrawn on: the stage's tick, or where there is
 * none (a part shown alone, as in a test) a loop of its own, every [fastMs] while [fast] and
 * twice a second otherwise, and none while not [running].
 */
@Composable
fun rememberFrames(fast: Boolean, running: Boolean = true, fastMs: Long = TICK_MS): State<Long> =
    LocalStageTick.current?.frame ?: rememberFrameLoop(fast, running, fastMs)

/** A frame-aligned loop; nothing runs in a still. */
@Composable
private fun rememberFrameLoop(fast: Boolean, running: Boolean, fastMs: Long): State<Long> {
    val frame = remember { mutableLongStateOf(0L) }
    if (running && !LocalStill.current) {
        LaunchedEffect(fast) {
            while (true) {
                withFrameNanos { frame.longValue = it }
                delay(if (fast) fastMs else SLOW_TICK_MS)
            }
        }
    }
    return frame
}

/** About 30 frames a second, aligned to the display's: plenty for a bar and a field this slow. */
const val TICK_MS = 24L
private const val SLOW_TICK_MS = 500L

/**
 * A value that glides to [target]: from rest in [durationMs] ([Motion.Dimmer]). A new target
 * that comes while it still glides (a key held, or pressed again quickly) carries on from its
 * pace, without a bounce, instead of starting over from a standstill.
 */
@Composable
fun animateGlide(target: Float, durationMs: Int): State<Float> {
    val glide = remember { Animatable(target) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(target) {
        if (glide.targetValue == target) return@LaunchedEffect
        val spec = if (glide.isRunning) spring(Spring.DampingRatioNoBouncy, Motion.GLIDE_STIFFNESS) else tween<Float>(durationMs, easing = Motion.Dimmer)
        // Launched where it outlives this effect: a glide ended by the next target would lose its pace first.
        scope.launch { glide.animateTo(target, spec) }
    }
    return glide.asState()
}

/**
 * Motion vocabulary. Everything is driven by the display's frames and eased; nothing
 * bounces, nothing slides, nothing jumps. A track change takes about a second: long enough
 * to be seen, short enough that nobody waits for it.
 */
object Motion {
    /** Soft in-out, like a dimmer turned by hand. */
    val Dimmer = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)

    /** A sine in-out: the gentlest way for a picture to come up. */
    val Sine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

    /**
     * A glide that comes while one runs ([animateGlide]) follows a spring this stiff: at the
     * remote's own repeat a held key moves a list on evenly, two or three lines behind the key,
     * and it comes to rest about 0.4 s after the key is let go.
     */
    const val GLIDE_STIFFNESS = Spring.StiffnessMediumLow

    /** Quick start, long settle: for things arriving at their place. */
    val Decelerate = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /**
     * The cover flow turning over: a gentle start, an unhurried middle (at most about twice
     * the average speed) and a long soft landing.
     */
    val Turn = CubicBezierEasing(0.35f, 0f, 0.35f, 1f)

    /**
     * A full track change cross-fades, so the screen never goes dark. The new artwork comes up
     * over the old one (0 to 600 ms), and the old goes once it is covered (600 to 750 ms). The
     * light on the wall turns to the new colors (0 to 800 ms). The old text leaves (220 ms); the
     * new lines rise into place one after another from 260 ms, 380 ms each.
     */
    const val TRACK_CHANGE_MS = 1_000
    const val ART_IN_END_MS = 600
    const val ART_OUT_START_MS = 600
    const val ART_OUT_END_MS = 750
    const val GLOW_MS = 800
    const val TEXT_OUT_MS = 220
    const val TEXT_IN_MS = 260
    const val TEXT_LINE_MS = 380

    /** New track, same picture (next song of the album): only the text changes, sooner. */
    const val TEXT_CHANGE_MS = 950
    const val TEXT_IN_SAME_PICTURE_MS = 250

    /**
     * Cover Flow: when another picture comes to the middle, the strip turns over in 1.3 s
     * ([Turn]). The light above the cards takes the new color (0 to 1100 ms). The old text has
     * left before the cards gather speed, and the new lines rise from 780 ms, as the new cover
     * turns to face the room. Cards that make way for others rolling in go first (300 ms).
     */
    const val FLOW_TURN_MS = 1_300
    const val FLOW_LIGHT_MS = 1_100
    const val FLOW_TEXT_IN_MS = 780
    const val FLOW_CLEAR_MS = 300

    /** A change that something newer is waiting behind finishes within this (ms). */
    const val HURRY_MS = 350

    /** Cards beside the middle come or go (the queue changed). */
    const val FLOW_SIDES_MS = 900

    /** Same track, a better cover. */
    const val SOFT_SWAP_MS = 900

    /** Light wall to dark one at sunset (and back in the morning). */
    const val WALL_CHANGE_MS = 2_400

    /** On pause the light, the artwork and the text dim; nothing moves. */
    const val PAUSE_MS = 600

    /** A line that changes its content (status, station, notice) cross-fades. */
    const val LINE_FADE_MS = 400

    /** Light, artwork and text while paused; on the light wall the artwork dims less (it would turn muddy). */
    const val PAUSED_LIGHT = 0.45f
    const val PAUSED_COVER = 0.72f
    const val PAUSED_COVER_ON_PAPER = 0.86f
    const val PAUSED_TEXT = 0.7f

    /** Eased progress of a phase that runs from [start] to [end] of the overall 0..1 [t]. */
    fun phase(t: Float, start: Float, end: Float, easing: Easing = Dimmer): Float {
        val x = ((t - start) / (end - start)).coerceIn(0f, 1f)
        return easing.transform(x)
    }

    /** [phase] with the window given in milliseconds of an animation lasting [totalMs]. */
    fun phaseMs(t: Float, startMs: Int, endMs: Int, totalMs: Int, easing: Easing = Dimmer): Float =
        phase(t, startMs.toFloat() / totalMs, endMs.toFloat() / totalMs, easing)

    /**
     * OLED care: the whole composition drifts on a slow Lissajous path (±[amplitudePx]),
     * moving at most about one pixel every few seconds. Viewers do not notice it, but the
     * panel is spared. Whole pixels only, so hairlines never shimmer.
     */
    fun drift(wallClockMs: Long, amplitudePx: Float): Pair<Int, Int> {
        val t = wallClockMs / 1000.0
        val x = sin(2 * PI * t / (37 * 60)) * amplitudePx
        val y = sin(2 * PI * t / (29 * 60) + 1.3) * amplitudePx * 0.8
        return x.roundToInt() to y.roundToInt()
    }
}
