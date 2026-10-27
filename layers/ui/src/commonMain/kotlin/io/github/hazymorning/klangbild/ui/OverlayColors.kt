package io.github.hazymorning.klangbild.ui

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
