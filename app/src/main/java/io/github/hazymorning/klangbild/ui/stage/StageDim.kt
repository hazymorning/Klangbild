package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.ui.Motion

/**
 * How a pause, or a lost link, dims the stage in either view: the wall's [light], the
 * [picture] (on paper a little less) and the [text], all gliding in [Motion.PAUSE_MS]. While
 * the speaker buffers, the light breathes. Read the values in draw lambdas.
 */
internal class StageDim(
    private val pause: State<Float>,
    private val breath: State<Float>,
    private val pictureLevel: State<Float>,
    private val textLevel: State<Float>,
) {
    val light: Float get() = pause.value * breath.value
    val picture: Float get() = pictureLevel.value
    val text: Float get() = textLevel.value
}

/** The dimming for [status] ([dimmed]: the link is down) on the wall of [appearance]. */
@Composable
internal fun rememberStageDim(status: PlayerStatus, dimmed: Boolean, appearance: Appearance): StageDim {
    val quiet = status == PlayerStatus.PAUSED || dimmed
    val pause = animateFloatAsState(
        targetValue = if (quiet) Motion.PAUSED_LIGHT else 1f,
        animationSpec = tween(Motion.PAUSE_MS, easing = Motion.Dimmer),
        label = "pause-light",
    )
    val picture = animateFloatAsState(
        targetValue = when {
            !quiet -> 1f
            appearance == Appearance.LIGHT -> Motion.PAUSED_COVER_ON_PAPER
            else -> Motion.PAUSED_COVER
        },
        animationSpec = tween(Motion.PAUSE_MS, easing = Motion.Dimmer),
        label = "pause-picture",
    )
    val text = animateFloatAsState(
        targetValue = if (quiet) Motion.PAUSED_TEXT else 1f,
        animationSpec = tween(Motion.PAUSE_MS, easing = Motion.Dimmer),
        label = "pause-text",
    )
    val breath: State<Float> = if (status == PlayerStatus.BUFFERING) {
        rememberInfiniteTransition(label = "buffering").animateFloat(
            initialValue = 1f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(1_600, easing = Motion.Dimmer), RepeatMode.Reverse),
            label = "breath",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    return StageDim(pause, breath, picture, text)
}
