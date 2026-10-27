package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.hazymorning.klangbild.ui.stage.TextLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Plays one change of a theater: [t] runs from 0 to 1 in [totalMs]. If another track is waiting
 * before the end ([newer] returns), the rest of the change hurries (at most [Motion.HURRY_MS])
 * once [onHurry] has learned where it stood. The change still lands (nothing jumps), and the
 * next one follows at once. Anything else that comes meanwhile (a card that joins the strip, a
 * better cover) waits for the change to land.
 */
internal suspend fun playChange(
    t: Animatable<Float, AnimationVector1D>,
    totalMs: Int,
    newer: suspend () -> Unit,
    onHurry: (Float) -> Unit,
) = coroutineScope {
    var hurried = false
    val watch = launch {
        newer()
        hurried = true
        val at = t.value
        onHurry(at)
        t.animateTo(1f, tween(((1f - at) * totalMs).roundToInt().coerceIn(0, Motion.HURRY_MS), easing = LinearEasing))
    }
    try {
        t.animateTo(1f, tween(totalMs, easing = LinearEasing))
    } catch (e: CancellationException) {
        // The hurried rest took over the animation. Anything else is a real cancellation.
        if (!hurried) throw e
    }
    if (hurried) watch.join() else watch.cancel()
}

/**
 * How the lines of a change's new text rise in a change of [totalMs]: each in
 * [Motion.TEXT_LINE_MS], from [startMs] plus its stagger.
 */
internal class TextRise(private val startMs: Int, val totalMs: Int) {
    /** When the first line sets off (0 to 1 of the change). */
    val begins: Float get() = startMs.toFloat() / totalMs

    /** How far [line] has risen at [at] (0 to 1 of the change). */
    fun level(at: Float, line: TextLine): Float =
        Motion.phaseMs(at, startMs + line.staggerMs, startMs + line.staggerMs + Motion.TEXT_LINE_MS, totalMs, Motion.Decelerate)

    companion object {
        /** When the last line of text that sets off at [startMs] is in place (ms). */
        fun endMs(startMs: Int): Int = startMs + TextLine.entries.maxOf { it.staggerMs } + Motion.TEXT_LINE_MS
    }
}

/**
 * The new text of a theater's changes. Its lines rise one after another as the change runs on
 * [t]. When the change hurries they stop where they are ([hold]), so the lines of a track that
 * is skipped past never come up. They rise the rest of the way only if that track stays after
 * all ([release]). When the next track's text comes ([begin]), this one leaves from where it
 * stood ([leaving]), so nothing jumps.
 */
@Stable
internal class RisingText(private val t: Animatable<Float, AnimationVector1D>) {
    /** The text that leaves, its lines where [rise] had them at [at] when the change began. */
    private class LeavingText(val rise: TextRise?, val at: Float) {
        fun level(line: TextLine): Float = rise?.level(at, line) ?: 1f
    }

    private var rise by mutableStateOf<TextRise?>(null)
    private var held by mutableStateOf<Float?>(null)
    private var leavingText by mutableStateOf<LeavingText?>(null)

    /** The lines are on their way up (rising, or held on the way). */
    val rising: Boolean get() = rise != null

    /** How far [line] has risen: 1 in its place. */
    fun level(line: TextLine): Float = rise?.level(held ?: t.value, line) ?: 1f

    /**
     * How far [line] has risen as its change runs, whether it is held or not: the queue's rows
     * come the whole way, since they are the queue and not the track skipped past.
     */
    fun unheld(line: TextLine): Float = rise?.level(t.value, line) ?: 1f

    /** How far [line] of the text that leaves had risen when the change began. */
    fun leaving(line: TextLine): Float = leavingText?.level(line) ?: 1f

    /**
     * How far [line] stood before the change now running began: the text that leaves, or the text
     * that stays. A frame composed before the change began still shows its lines so.
     */
    fun before(line: TextLine): Float = leavingText?.level(line) ?: level(line)

    /** A change brings new text that rises as [next] says; the text so far leaves from where it stands. */
    fun begin(next: TextRise) {
        leavingText = LeavingText(rise, held ?: 1f)
        rise = next
        held = null
    }

    /** The change hurries at [at] (0 to 1): lines on their way up stop where they are. */
    fun hold(at: Float) {
        if (rise != null && held == null) held = at
    }

    /** The change has landed: its lines are in place, unless they were held. */
    fun landed() {
        if (held == null) rise = null
        leavingText = null
    }

    /**
     * Held lines rise the rest of the way at the pace of their change. They start at once, since
     * the change has landed. If another track comes first ([newer] returns), they stay where they
     * are.
     */
    suspend fun release(newer: suspend () -> Unit) {
        val r = rise ?: return
        val from = max(held ?: return, r.begins)
        coroutineScope {
            val up = launch {
                animate(from, 1f, animationSpec = tween(((1f - from) * r.totalMs).roundToInt(), easing = LinearEasing)) { v, _ -> held = v }
                rise = null
                held = null
            }
            val watch = launch {
                newer()
                up.cancel()
            }
            up.join()
            watch.cancel()
        }
    }
}
