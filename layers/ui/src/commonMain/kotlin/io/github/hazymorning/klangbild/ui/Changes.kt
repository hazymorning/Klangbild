package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.ui.stage.BaseWall
import io.github.hazymorning.klangbild.ui.stage.StageModel
import io.github.hazymorning.klangbild.ui.stage.StatusLayer
import io.github.hazymorning.klangbild.ui.stage.TextLine
import io.github.hazymorning.klangbild.ui.stage.WallLayer
import io.github.hazymorning.klangbild.ui.stage.WallMargin
import io.github.hazymorning.klangbild.ui.stage.WordsStates
import io.github.hazymorning.klangbild.ui.stage.asColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
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

    /** A change brings new text that rises as [next] says, and the text so far leaves from where it stands. */
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

/**
 * The changes of a theater (the single cover's or Cover Flow's): the stage on screen ([current]),
 * while a change runs the one it leaves ([previous]) and how the two differ ([kind]), and how far
 * the change has come ([t], 0 to 1) with its new text ([text]). [began] counts the changes, so a
 * frame can tell one that began after it was composed ([ChangeFrame]).
 */
@Stable
internal class TheaterChanges<D : Any, K : Any>(initial: D, initialKind: K) {
    var current by mutableStateOf(initial)
    var previous by mutableStateOf<D?>(null)
        private set
    var kind by mutableStateOf(initialKind)
        private set
    var began by mutableIntStateOf(0)
        private set
    val t = Animatable(1f)
    val text = RisingText(t)

    /**
     * Plays the change from [old] to [target], of [kind] and [totalMs] long, its new text rising as
     * [rise] says (none: the text stays). [start] sets what else the change needs once it has
     * begun, before it is shown. Another track coming ([newer]) hurries it ([playChange]).
     */
    suspend fun play(old: D, target: D, kind: K, totalMs: Int, rise: TextRise?, newer: suspend () -> Unit, start: () -> Unit = {}) {
        began++
        t.snapTo(0f)
        rise?.let { text.begin(it) }
        this.kind = kind
        start()
        previous = old
        current = target
        playChange(t, totalMs, newer) { text.hold(it) }
        previous = null
        text.landed()
    }
}

/**
 * Runs a theater's [changes] toward [latest], the stage to show. One change at a time: what comes
 * meanwhile waits until the running change has landed, and only the newest follows. Another track
 * ([trackKey]) makes the running change hurry ([playChange]). [change] plays the change from the
 * stage on screen to the next ([TheaterChanges.play]), or returns false when there is nothing to
 * watch.
 */
@Composable
internal fun <D : Any> RunChanges(
    changes: TheaterChanges<D, *>,
    latest: D,
    trackKey: (D) -> String,
    change: suspend (old: D, target: D, newer: suspend () -> Unit) -> Boolean,
) {
    val newest by rememberUpdatedState(latest)
    LaunchedEffect(Unit) {
        snapshotFlow { newest }.conflate().collect { target ->
            val old = changes.current
            val key = trackKey(target)
            val newer: suspend () -> Unit = { snapshotFlow { trackKey(newest) }.first { it != key } }
            // Lines held by a skip that came to nothing rise the rest of the way first.
            if (key == trackKey(old)) changes.text.release(newer)
            if (!change(old, target, newer)) return@collect
            if (trackKey(newest) == key) changes.text.release(newer)
        }
    }
}

/**
 * A theater's change as a frame composed it, the whole of it [totalMs] long: how far it has come
 * ([at], 0 to 1) and how far a stretch of it has ([p]). A change can begin between composing a
 * frame and drawing it. Until it is composed, the frame shows the stage as the change before
 * left it ([stale]), its lines where they stood.
 */
internal class ChangeFrame(private val changes: TheaterChanges<*, *>, private val seen: Int, val totalMs: Int) {
    /** A change has begun since this frame was composed. */
    val stale: Boolean get() = changes.began != seen

    /** How far the change has come (0 to 1), at its end once another has begun. */
    val at: () -> Float = { if (changes.began == seen) changes.t.value else 1f }

    /** How far the stretch from [startMs] to [endMs] has come, eased by [easing] ([Motion.phaseMs]). */
    fun p(startMs: Int, endMs: Int, easing: Easing = Motion.Dimmer): Float = Motion.phaseMs(at(), startMs, endMs, totalMs, easing)
}

/** The change of [changes] as this composition shows it, the whole of it [totalMs] long ([ChangeFrame]). */
@Composable
internal fun rememberChangeFrame(changes: TheaterChanges<*, *>, totalMs: Int): ChangeFrame {
    val seen = changes.began
    return remember(changes, seen, totalMs) { ChangeFrame(changes, seen, totalMs) }
}

/**
 * How the change in [frame] moves the column's text, its new lines rising as [text] says: one
 * after another, and with [fadeIn] coming up over the whole change as well. The text so far
 * leaves from where it stood, and a new look of the same track ([soft]) crossfades in place.
 */
internal class TextMotion(private val frame: ChangeFrame, private val text: RisingText, private val soft: Boolean, private val fadeIn: Boolean) {
    /** How strongly each line of the new text shows. */
    val newAlpha: (TextLine) -> Float = { line ->
        when {
            frame.stale -> text.before(line)
            fadeIn -> text.level(line) * frame.p(0, frame.totalMs, Motion.Sine)
            else -> text.level(line)
        }
    }

    /** How far each line of the new text still has to rise (dp). */
    val newRise: (TextLine) -> Float = { line -> Motion.RISE_DP * (1f - if (frame.stale) text.before(line) else text.level(line)) }

    /** How strongly the text so far shows, as a whole, while it leaves. */
    val out: () -> Float = { if (soft) 1f - frame.p(0, frame.totalMs) else 1f - frame.p(0, Motion.TEXT_OUT_MS) }

    /** How far the text so far still has to rise, as a whole, while it leaves (dp): it rises past its place. */
    val outRise: () -> Float = { if (soft) 0f else -Motion.LEAVE_RISE_DP * frame.p(0, Motion.TEXT_OUT_MS) }

    /** How far each line of the text so far had risen when the change began. */
    private val oldLevel: (TextLine) -> Float = { line -> if (soft) text.level(line) else text.leaving(line) }

    /** How strongly each line of the text so far shows. */
    val oldAlpha: (TextLine) -> Float = { line -> oldLevel(line) * out() }

    /** How far each line of the text so far still has to rise (dp). */
    val oldRise: (TextLine) -> Float = { line -> Motion.RISE_DP * (1f - oldLevel(line)) + outRise() }
}

/** How the change in [frame] moves the column's text ([TextMotion]). */
@Composable
internal fun rememberTextMotion(frame: ChangeFrame, text: RisingText, soft: Boolean, fadeIn: Boolean): TextMotion =
    remember(frame, text, soft, fadeIn) { TextMotion(frame, text, soft, fadeIn) }

/** The words of the stage shown ([cur]) and of the one leaving ([prev]), which keep their places through a change. */
@Composable
internal fun rememberWordsStates(cur: StageData, prev: StageData?): WordsStates {
    val states = remember { WordsStates() }
    SideEffect { states.keep(cur, prev) }
    return states
}

/**
 * A theater's background: [scene]'s, and while a change brings a new one, the [old] one under it.
 * The old background stays whole until the new one (its light and its color beyond the screen's
 * edges) has come up over it at [alpha], so a new one never shows the old in a strip at the side.
 * [lightLevel] is the light's strength, and [shift] and [column] say where Cover Flow's cards and
 * its column stand ([WallLayer]).
 */
@Composable
internal fun ChangingWalls(
    scene: ArtworkScene,
    old: ArtworkScene?,
    alpha: () -> Float,
    lightLevel: () -> Float,
    shift: () -> Float = { 0f },
    column: () -> Float = { 0f },
) {
    BaseWall((old ?: scene).palette.wall.asColor())
    if (old != null) {
        WallLayer(old, lightLevel = lightLevel, shift = shift, column = column, onOwnWall = true)
        WallMargin(scene.palette.wall.asColor(), alpha = alpha)
    }
    WallLayer(scene, alpha = alpha, lightLevel = lightLevel, shift = shift, column = column, onOwnWall = old == null)
}

/**
 * A theater's status line, [model]'s, which belongs to the room, not to the track, so it stays
 * through a change. When the background changes, it crossfades from the [old] one's inks over
 * [frame]'s change. [shift] moves it with the column ([StatusLayer]).
 */
@Composable
internal fun ChangingStatus(model: StageModel, old: StageModel?, frame: ChangeFrame, shift: () -> Float = { 0f }) {
    if (old != null) {
        StatusLayer(old, shift = shift, alpha = { 1f - frame.p(0, frame.totalMs, Motion.Sine) })
        StatusLayer(model, shift = shift, alpha = { frame.p(0, frame.totalMs, Motion.Sine) })
    } else {
        StatusLayer(model, shift = shift)
    }
}
