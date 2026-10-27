package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * While [pending] (a queue display chosen but not on screen yet), a value's ink moves toward a
 * quieter ink and back, each way in [Motion.BREATH_MS], like the stage's light while the speaker
 * buffers. When the wait ends, it returns to its own ink in [Motion.PAUSE_MS]. The state is the
 * depth: 0 in its own ink, 1 in the quiet one. Read it in a draw lambda ([breathed]), so only
 * drawing reruns.
 */
@Composable
fun rememberBreath(pending: Boolean): State<Float> {
    val depth = remember { Animatable(0f) }
    if (!LocalStill.current) {
        LaunchedEffect(pending) {
            if (pending) {
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

/** [ink] moved [depth] of the way toward [quiet]; exactly [ink] at depth 0. */
fun breathed(ink: Color, quiet: Color, depth: Float): Color = if (depth <= 0f) ink else lerp(ink, quiet, depth)
