package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.hazymorning.klangbild.color.CoverPalette
import io.github.hazymorning.klangbild.color.Lamp
import io.github.hazymorning.klangbild.render.LampLight

/**
 * What the settings menu and the quick settings share in their colors: near-neutral inks, the
 * light background, and the band of light under the chosen row or entry.
 */
internal object OverlayColors {
    /** The band on black: the lamp's light, its brightest point ≈ L* 10. */
    val BAND_ON_BLACK = Color(LampLight.lightTint(LampLight.LAMP, 0.011f))

    /** The band on the light background: slightly whiter light. */
    val BAND_ON_LIGHT = Color(0xFF0D0C0B)

    /** The light background. */
    val LIGHT = ink(CoverPalette.PAPER, 0.006f)

    /** A near-neutral ink of lightness [l] with a breath of [chroma] in the lamp's hue, as the lamp shows it. */
    fun ink(l: Float, chroma: Float = 0.004f) = Color(Lamp.neutral(l, chroma))
}

/**
 * How the settings menu and the quick settings come and go: in over [inMs] while there is a
 * [state] to show, and out over [outMs] once it is null, still showing the last one. [content]
 * shows it, faded as far as its `shown` says (0 to 1), which it reads where it draws.
 */
@Composable
internal fun <T : Any> FadingOverlay(state: T?, inMs: Int, outMs: Int, content: @Composable (state: T, shown: () -> Float) -> Unit) {
    // The last state stays while the overlay fades out.
    val last = remember { LastShown<T>() }
    if (state != null) last.state = state
    val shown = remember { Animatable(0f) }
    // Composition only asks whether it shows, so the fade redraws without composing anew.
    val gone by remember { derivedStateOf { shown.value <= 0f } }
    LaunchedEffect(state != null) {
        shown.animateTo(if (state != null) 1f else 0f, tween(if (state != null) inMs else outMs, easing = Motion.Dimmer))
    }
    if (state == null && gone) return
    content(state ?: last.state ?: return) { shown.value }
}

/** What an overlay showed last. It is set while composing, so it is a plain field and no snapshot state. */
private class LastShown<T : Any> {
    var state: T? = null
}
