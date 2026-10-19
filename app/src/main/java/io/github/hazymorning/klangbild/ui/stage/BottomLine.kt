package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import io.github.hazymorning.klangbild.ui.Motion
import kotlin.math.roundToInt

/** The progress bar itself, as [bottomOf] names it beside the words a line may hold. */
internal const val BAR = "\u0000bar"

/** What stands in the progress bar's place: a notice or a station ([StageModel.line]), the bar, or nothing. */
internal fun bottomOf(model: StageModel): String? = when {
    model.line != null -> model.line
    model.progress && model.durationMs != null -> BAR
    else -> null
}

/**
 * The progress bar's place in the column ([bottomOf]): the bar, or a line of words. A change
 * cross-fades.
 */
@Composable
internal fun BottomLine(content: String?, model: StageModel, styles: StageStyles, playing: Boolean, modifier: Modifier, label: String) {
    Crossfade(content, modifier, tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer), label = label) { shown ->
        when (shown) {
            null -> Box(Modifier)
            BAR -> ProgressBar(
                durationMs = model.durationMs ?: 1L,
                positionMs = model.positionMs,
                startMs = model.rangeStartMs,
                item = model.scene.key,
                playing = playing,
                colors = ProgressColors(track = styles.track, fill = styles.accent, label = styles.quiet),
                labelStyle = styles.time,
            )
            else -> BasicText(shown, style = styles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A line of the column as a change moves it: its [alpha] and how far it still has to rise (dp, on whole pixels). */
internal fun Modifier.lineMotion(which: TextLine, alpha: (TextLine) -> Float, rise: (TextLine) -> Float): Modifier = graphicsLayer {
    this.alpha = alpha(which).coerceIn(0f, 1f)
    translationY = (rise(which) * density).roundToInt().toFloat()
}
