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
 * A value whose choice is on its way (the queue's way while its stage is made) breathes, as the
 * light does while the speaker buffers: the whole word sinks into the quietest ink that still
 * reads well in [Motion.BREATH_MS] and comes back as slowly, for as long as the wait goes on, so
 * the wait shows from across the room. When it is over, the word glides back to its own ink in
 * [Motion.PAUSE_MS]. The state is how far the word has sunk (0 in its own ink, 1 in the quiet
 * one); read it in a draw lambda ([breathed]), so a frame only draws the word anew.
 */
@Composable
fun rememberBreath(waiting: Boolean): State<Float> {
    val depth = remember { Animatable(0f) }
    if (!LocalStill.current) {
        LaunchedEffect(waiting) {
            if (waiting) {
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

/** [ink] sunk [depth] of the way into [quiet]; exactly [ink] at rest, so a word that does not wait keeps its very ink. */
fun breathed(ink: Color, quiet: Color, depth: Float): Color = if (depth <= 0f) ink else lerp(ink, quiet, depth)
