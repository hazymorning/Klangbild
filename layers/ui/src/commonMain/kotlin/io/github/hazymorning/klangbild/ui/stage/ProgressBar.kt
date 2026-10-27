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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.ui.LocalStill
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.rememberTick
import io.github.hazymorning.klangbild.ui.theme.Face
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.rememberFigures
import kotlin.math.abs
import kotlin.math.roundToInt

/** The progress bar's [groove], the [fill] of its played part, and its times' [label] color. */
internal class ProgressColors(val groove: Color, val fill: Color, val label: Color)

/**
 * A classic progress bar: a thin bar with round ends, the played part filled (no knob, since
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
 * In a book with chapters the bar spans the chapter: from [startMs] in the track's own time, for
 * [durationMs]. A new chapter starts a new bar, without gliding back, and so does another track
 * ([trackKey]).
 */
@Composable
internal fun ProgressBar(
    durationMs: Long,
    positionMs: () -> Long?,
    playing: Boolean,
    colors: ProgressColors,
    labelStyle: TextStyle,
    startMs: Long,
    trackKey: String,
    modifier: Modifier = Modifier,
) {
    // The stage's one tick, shared with the cover field and the lyrics.
    val tick = rememberTick(fast = playing)
    val fillLevel by animateFloatAsState(
        targetValue = if (playing) 1f else PAUSED_FILL,
        animationSpec = tween(Motion.PAUSE_MS, easing = Motion.Dimmer),
        label = "progress-pause",
    )
    val glide = remember(trackKey, startMs, durationMs) { Glide() }
    // A still has no clock to glide by.
    val still = LocalStill.current
    // Always the newest reading, also for the labels' state, which outlives the first one.
    val position by rememberUpdatedState(positionMs)
    val elapsed: () -> Long = { ((position() ?: startMs) - startMs).coerceIn(0L, durationMs) }
    // The labels only change when the displayed second does.
    val second by remember(trackKey, startMs, durationMs) {
        derivedStateOf {
            tick.value // read on every tick
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
                val now = tick.value // redraw on every tick
                val target = (elapsed().toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                val f = if (still) target else glide.at(target, now, durationMs)
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(colors.groove, cornerRadius = r)
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
        val elapsedLabel = measurables[1].measure(loose)
        val lengthLabel = measurables[2].measure(loose)
        val baseline = barBlock(labelStyle)
        layout(constraints.maxWidth, baseline) {
            bar.place(0, 0)
            elapsedLabel.place(0, baseline - elapsedLabel[FirstBaseline])
            lengthLabel.place(constraints.maxWidth - lengthLabel.width, baseline - lengthLabel[FirstBaseline])
        }
    }
}

/**
 * Follows the position, except after a seek (more than [SEEK_MS] at once): then it eases from
 * where it was to the new place over [GLIDE_MS].
 */
private class Glide {
    private var shown = -1f
    private var from = 0f
    private var startNs: Long? = null

    fun at(target: Float, nowNs: Long, durationMs: Long): Float {
        if (shown < 0f) {
            shown = target
            return target
        }
        if (startNs == null && abs(target - shown) * durationMs > SEEK_MS) {
            from = shown
            startNs = nowNs
        }
        val start = startNs
        if (start != null) {
            val t = ((nowNs - start) / 1e6f / GLIDE_MS).coerceIn(0f, 1f)
            if (t < 1f) {
                shown = from + (target - from) * Motion.Decelerate.transform(t)
                return shown
            }
            startNs = null
        }
        shown = target
        return shown
    }
}

/** The bar's block from its top to its labels' baseline (px), its labels in [labelStyle], as [ProgressBar] lays it out. */
internal fun Density.barBlock(labelStyle: TextStyle): Int =
    BAR_THICKNESS.dp.roundToPx() + BAR_LABEL_GAP.dp.roundToPx() + (labelStyle.fontSize.toPx() * Fonts.CAP_HEIGHT).roundToInt()

/** The bar's thickness, and the room from it down to its labels' cap height (dp). */
internal const val BAR_THICKNESS = 4f
internal const val BAR_LABEL_GAP = 10f

/** Paused, the played part keeps this much of its ink, so the bar shows the pause too. */
private const val PAUSED_FILL = 0.6f

/** A change in position of more than this (ms) is a seek, which glides over [GLIDE_MS]. */
private const val SEEK_MS = 1_500L
private const val GLIDE_MS = 350L
