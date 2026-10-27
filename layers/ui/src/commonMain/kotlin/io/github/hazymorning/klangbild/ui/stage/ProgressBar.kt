package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.rememberFrames
import io.github.hazymorning.klangbild.ui.theme.Face
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.rememberFigures
import kotlin.math.abs
import kotlin.math.roundToInt

/** Colors of the progress bar. */
class ProgressColors(val track: Color, val fill: Color, val label: Color)

/**
 * A classic progress bar: a thin track with round ends, the played part filled (no knob, since
 * nothing here is touched), the elapsed time under its left end and the track's length under
 * its right end ("1:35" and "4:11"). Only one number ticks, and its figures sit in fixed
 * cells, so nothing jitters.
 *
 * It glides. While playing, only the bar's own drawing is refreshed, about 30 times a second
 * and aligned to the display's frames. The fill grows by a fraction of a pixel each time,
 * anti-aliased, so it never steps. A seek glides to the new place in 350 ms instead of
 * jumping. The labels recompose once a second. Paused, the fill dims and the bar is redrawn
 * only twice a second (a seek while paused still shows).
 *
 * The labels' cap height sits [BAR_LABEL_GAP] below the bar; the measured height ends at their
 * baseline, so a layout can align the block by it.
 *
 * In a book with chapters the bar spans the chapter: from [startMs] in the item's own time, for
 * [durationMs]; a new chapter starts a new bar, without gliding back, and so does another [item].
 */
@Composable
fun ProgressBar(
    durationMs: Long,
    positionMs: () -> Long?,
    playing: Boolean,
    colors: ProgressColors,
    labelStyle: TextStyle,
    modifier: Modifier = Modifier,
    startMs: Long = 0L,
    item: Any? = null,
) {
    // The stage's one tick, shared with the color field.
    val frame = rememberFrames(fast = playing)
    val fillLevel by animateFloatAsState(
        targetValue = if (playing) 1f else 0.6f,
        animationSpec = tween(Motion.PAUSE_MS, easing = Motion.Dimmer),
        label = "progress-pause",
    )
    val glide = remember(item, startMs, durationMs) { Glide() }
    // Always the newest reading, also for the labels' state, which outlives the first one.
    val position by rememberUpdatedState(positionMs)
    val elapsed: () -> Long = { ((position() ?: startMs) - startMs).coerceIn(0L, durationMs) }
    // The labels only change when the displayed second does.
    val second by remember(item, startMs, durationMs) {
        derivedStateOf {
            frame.value // re-read every refresh
            elapsed() / 1000L
        }
    }
    val label = labelStyle.copy(color = colors.label)
    val figures = rememberFigures(labelStyle, Face.TITLE)
    val length = remember(figures, durationMs) { figures.of(TimeText.duration(durationMs)) }
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            // Its own layer: the refresh re-records only the bar, not the labels.
            Canvas(Modifier.fillMaxWidth().height(BAR_THICKNESS.dp).graphicsLayer()) {
                val now = frame.value // redraw on every refresh
                val target = (elapsed().toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                val f = glide.at(target, now, durationMs)
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(colors.track, cornerRadius = r)
                if (f > 0f) {
                    // Never shorter than it is high: a round dot at the very start.
                    val w = (size.width * f).coerceAtLeast(size.height)
                    drawRoundRect(colors.fill.copy(alpha = colors.fill.alpha * fillLevel), Offset.Zero, Size(w, size.height), r)
                }
            }
            BasicText(figures.of(TimeText.duration(second * 1000L)), style = label, maxLines = 1)
            BasicText(length, style = label, maxLines = 1)
        },
    ) { measurables, constraints ->
        val bar = measurables[0].measure(constraints)
        val loose = constraints.copy(minWidth = 0)
        val elapsed = measurables[1].measure(loose)
        val remaining = measurables[2].measure(loose)
        // Cap height of the labels [BAR_LABEL_GAP] below the bar.
        val cap = (labelStyle.fontSize.toPx() * Fonts.CAP_HEIGHT).roundToInt()
        val baseline = bar.height + BAR_LABEL_GAP.dp.roundToPx() + cap
        val first = elapsed[FirstBaseline]
        layout(constraints.maxWidth, baseline) {
            bar.place(0, 0)
            elapsed.place(0, baseline - first)
            remaining.place(constraints.maxWidth - remaining.width, baseline - remaining[FirstBaseline])
        }
    }
}

/**
 * Follows the position, except after a jump (a seek, more than 1.5 s at once): then it
 * eases from where it was to the new place over [GLIDE_MS].
 */
private class Glide {
    private var shown = -1f
    private var from = 0f
    private var startNs = 0L

    fun at(target: Float, nowNs: Long, durationMs: Long): Float {
        if (shown < 0f) {
            shown = target
            return target
        }
        val jump = abs(target - shown) * durationMs > JUMP_MS
        if (jump && startNs == 0L) {
            from = shown
            startNs = nowNs
        }
        if (startNs != 0L) {
            val t = ((nowNs - startNs) / 1e6f / GLIDE_MS).coerceIn(0f, 1f)
            if (t >= 1f) {
                startNs = 0L
            } else {
                shown = from + (target - from) * Motion.Decelerate.transform(t)
                return shown
            }
        }
        shown = target
        return shown
    }
}

/** The bar's thickness, and the room from it down to its labels' cap height (dp). */
const val BAR_THICKNESS = 4f
const val BAR_LABEL_GAP = 10f

private const val JUMP_MS = 1_500f
private const val GLIDE_MS = 350f
