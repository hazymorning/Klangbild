package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import io.github.hazymorning.klangbild.ui.Motion
import kotlin.math.roundToInt

/** What stands in the progress bar's place ([slotOf]). */
internal sealed interface Slot {
    /** The progress bar over [durationMs] from [startMs] in the track's time, for the track with [trackKey]. */
    data class Bar(val durationMs: Long, val startMs: Long, val trackKey: String) : Slot

    /** A notice or a station ([StageModel.line]). */
    data class Text(val text: String) : Slot
}

/** What stands in [model]'s progress bar place: a notice or a station, the bar, or nothing. */
internal fun slotOf(model: StageModel): Slot? = when {
    model.line != null -> Slot.Text(model.line)
    model.progress && model.durationMs != null -> Slot.Bar(model.durationMs, model.rangeStartMs, model.scene.key)
    else -> null
}

/**
 * The progress bar's place in the column ([slotOf]): the bar, or a line of words. A change
 * cross-fades, and a bar that leaves keeps its own length; a new chapter only starts a new bar.
 * The bar itself shows as much as [barAlpha] says on top of that.
 */
@OptIn(ExperimentalAnimationApi::class) // For Transition.Crossfade's contentKey, so a new chapter updates the bar in place.
@Composable
internal fun BottomLine(model: StageModel, styles: StageStyles, modifier: Modifier, label: String, barAlpha: () -> Float = { 1f }) {
    updateTransition(slotOf(model), label).Crossfade(
        modifier,
        tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer),
        contentKey = { if (it is Slot.Bar) Slot.Bar::class else it },
    ) { slot ->
        when (slot) {
            null -> Box(Modifier)
            is Slot.Bar -> ProgressBar(
                durationMs = slot.durationMs,
                positionMs = model.positionMs,
                startMs = slot.startMs,
                item = slot.trackKey,
                playing = model.playing,
                colors = ProgressColors(groove = styles.barGroove, fill = styles.barFill, label = styles.quiet),
                labelStyle = styles.time,
                modifier = Modifier.graphicsLayer { alpha = barAlpha().coerceIn(0f, 1f) },
            )
            is Slot.Text -> BasicText(slot.text, style = styles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A line of the column as a change moves it: its [alpha] and how far it still has to rise (dp, on whole pixels). */
internal fun Modifier.lineMotion(which: TextLine, alpha: (TextLine) -> Float, rise: (TextLine) -> Float): Modifier = graphicsLayer {
    this.alpha = alpha(which).coerceIn(0f, 1f)
    translationY = (rise(which) * density).roundToInt().toFloat()
}
