package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
 * How a pause, or a lost link, dims the stage in either view: the wall's [light], the
 * [picture] (on paper a little less) and the [text], all gliding in [Motion.PAUSE_MS]. While
 * the speaker buffers, the light breathes, and when it plays again the light glides back from
 * where the breath was. Read the values in draw lambdas.
 */
internal class StageDim(
    private val pause: State<Float>,
    private val breath: State<Float>,
    private val pictureLevel: State<Float>,
    private val textLevel: State<Float>,
    private val seen: Breath,
) {
    val light: Float
        get() {
            val b = breath.value
            seen.last = b
            return pause.value * b
        }
    val picture: Float get() = pictureLevel.value
    val text: Float get() = textLevel.value
}

/** Where the breath was last drawn, so the light glides back from there. */
internal class Breath {
    var last = 1f
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
    val seen = remember { Breath() }
    val breath: State<Float> = if (status == PlayerStatus.BUFFERING) {
        rememberInfiniteTransition(label = "buffering").animateFloat(
            initialValue = 1f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(tween(1_600, easing = Motion.Dimmer), RepeatMode.Reverse),
            label = "breath",
        )
    } else {
        // Made anew each time buffering ends: from where the breath was, back to full light.
        // At full light already (no buffering before), nothing runs.
        val from = remember { seen.last }
        val back = remember { Animatable(from) }
        if (from < 1f && !LocalStill.current) LaunchedEffect(Unit) { back.animateTo(1f, tween(Motion.PAUSE_MS, easing = Motion.Dimmer)) }
        back.asState()
    }
    return StageDim(pause, breath, picture, text, seen)
}
