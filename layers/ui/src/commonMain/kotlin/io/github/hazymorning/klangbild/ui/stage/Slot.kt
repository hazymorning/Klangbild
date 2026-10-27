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

/** What stands in the slot under the text block ([slotOf]). */
internal sealed interface Slot {
    /** The progress bar over [durationMs] from [startMs] in the track's time, for the track with [trackKey]. */
    data class Bar(val durationMs: Long, val startMs: Long, val trackKey: String) : Slot

    /** A notice or a station ([StageModel.slotText]). */
    data class Text(val text: String) : Slot
}

/** What stands in [model]'s slot: a notice or a station, the progress bar, or nothing. */
internal fun slotOf(model: StageModel): Slot? = when {
    model.slotText != null -> Slot.Text(model.slotText)
    model.barShown && model.durationMs != null -> Slot.Bar(model.durationMs, model.barStartMs, model.scene.key)
    else -> null
}

/**
 * The slot under the text block ([slotOf]): the progress bar, or a line of words. A change
 * cross-fades, and a bar that leaves keeps its own length; a new chapter only starts a new bar.
 * The bar itself shows as much as [barAlpha] says on top of that.
 */
@OptIn(ExperimentalAnimationApi::class) // For Transition.Crossfade's contentKey, so a new chapter updates the bar in place.
@Composable
internal fun SlotContent(model: StageModel, styles: StageStyles, modifier: Modifier, label: String, barAlpha: () -> Float = { 1f }) {
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
