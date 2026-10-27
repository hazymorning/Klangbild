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
 * How a pause, or a lost connection, dims the stage (a single cover or Cover Flow): the
 * background's light ([lightLevel]), the cover ([coverLevel], a little less on the light
 * background) and the text ([textLevel]), all gliding in [Motion.PAUSE_MS]. While the speaker
 * buffers, the light breathes, and when it plays again the light glides back from where the
 * breath was. Read the levels in draw lambdas.
 */
internal class StageDim(
    private val light: State<Float>,
    private val breath: State<Float>,
    private val cover: State<Float>,
    private val text: State<Float>,
) {
    val lightLevel: Float get() = light.value * (1f - (1f - BREATH_LOW) * breath.value)
    val coverLevel: Float get() = cover.value
    val textLevel: Float get() = text.value
}

/** The dimming for [status] ([linkDown]: the connection is lost) on the background of [appearance]. */
@Composable
internal fun rememberStageDim(status: PlayerStatus, linkDown: Boolean, appearance: Appearance): StageDim {
    val dimmed = status == PlayerStatus.PAUSED || linkDown
    val light = animateFloatAsState(
        targetValue = if (dimmed) Motion.PAUSED_LIGHT else 1f,
        animationSpec = tween(Motion.PAUSE_MS, easing = Motion.Dimmer),
        label = "pause-light",
    )
    val cover = animateFloatAsState(
        targetValue = when {
            !dimmed -> 1f
            appearance == Appearance.LIGHT -> Motion.PAUSED_COVER_ON_LIGHT
            else -> Motion.PAUSED_COVER
        },
        animationSpec = tween(Motion.PAUSE_MS, easing = Motion.Dimmer),
        label = "pause-cover",
    )
    val text = animateFloatAsState(
        targetValue = if (dimmed) Motion.PAUSED_TEXT else 1f,
        animationSpec = tween(Motion.PAUSE_MS, easing = Motion.Dimmer),
        label = "pause-text",
    )
    // The breath's depth: 0 at full light, 1 at [BREATH_LOW].
    val breath = rememberBreath(status == PlayerStatus.BUFFERING)
    return StageDim(light, breath, cover, text)
}

/**
 * The breath's depth while [buffering]: down to 1 in [Motion.BREATH_MS] and back as slowly, for as
 * long as it lasts, then back to 0 in [Motion.PAUSE_MS]. A still stands at rest.
 */
@Composable
private fun rememberBreath(buffering: Boolean): State<Float> {
    val depth = remember { Animatable(0f) }
    if (!LocalStill.current) {
        LaunchedEffect(buffering) {
            if (buffering) {
                while (true) {
                    depth.animateTo(1f, tween(Motion.BREATH_MS, easing = Motion.Dimmer))
                    depth.animateTo(0f, tween(Motion.BREATH_MS, easing = Motion.Exhale))
                }
            } else if (depth.value > 0f) {
                depth.animateTo(0f, tween(Motion.PAUSE_MS, easing = Motion.Dimmer))
            }
        }
    }
    return depth.asState()
}

/** The light's lowest point while the speaker buffers. */
private const val BREATH_LOW = 0.7f
