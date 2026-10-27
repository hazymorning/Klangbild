package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.ui.LocalStill
import io.github.hazymorning.klangbild.ui.Motion

/**
 * How a pause, or a lost link, dims the stage (a single cover or Cover Flow): the wall's
 * [light], the [picture] (on paper a little less) and the [text], all gliding in
 * [Motion.PAUSE_MS]. While the speaker buffers, the light breathes, and when it plays again the
 * light glides back from where the breath was. Read the values in draw lambdas.
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
    // One value for the breath, so it always carries on from where the light stands: buffering
    // again while it glides back starts there.
    val breath = remember { Animatable(1f) }
    val buffering = status == PlayerStatus.BUFFERING
    if (!LocalStill.current) {
        LaunchedEffect(buffering) {
            if (buffering) {
                while (true) {
                    breath.animateTo(BREATH_LOW, tween(Motion.BREATH_MS, easing = Motion.Dimmer))
                    breath.animateTo(1f, tween(Motion.BREATH_MS, easing = Motion.Exhale))
                }
            } else if (breath.value < 1f) {
                breath.animateTo(1f, tween(Motion.PAUSE_MS, easing = Motion.Dimmer))
            }
        }
    }
    return StageDim(pause, breath.asState(), picture, text)
}

/** The light's lowest point while the speaker buffers. */
private const val BREATH_LOW = 0.7f
