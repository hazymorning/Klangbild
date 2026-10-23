package io.github.hazymorning.klangbild.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.contrastRatio
import org.junit.Test

/**
 * The quick settings' inks keep 4.5:1 on both walls: every ink on the veil, and the chosen
 * entry's on the veil where the band of light under it is brightest (on black it lifts the
 * ground; on paper it makes it a little whiter).
 */
class QuickColorsTest {
    /** The ground under the chosen entry: the band's light added to the veil, as it is drawn. */
    private fun lit(c: QuickColors): Int {
        val veil = c.veil.toArgb()
        val band = c.band.toArgb()
        fun add(shift: Int) = minOf(255, (veil shr shift and 0xFF) + (band shr shift and 0xFF)) shl shift
        return (0xFF shl 24) or add(16) or add(8) or add(0)
    }

    private fun contrast(ink: Color, ground: Int) = contrastRatio(ink.toArgb(), ground)

    @Test
    fun `every ink keeps four and a half to one on the veil, the chosen ones in the band's light too`() {
        for (appearance in Appearance.entries) {
            val c = QuickColors.of(appearance)
            val veil = c.veil.toArgb()
            for ((name, ink) in listOf("label" to c.label, "labelFocus" to c.labelFocus, "value" to c.value, "valueFocus" to c.valueFocus)) {
                assertWithMessage("$appearance $name on the veil").that(contrast(ink, veil)).isAtLeast(4.5f)
            }
            for (muted in listOf(false, true)) {
                assertWithMessage("$appearance value at rest, muted $muted").that(contrast(c.valueInk(focused = false, muted), veil)).isAtLeast(4.5f)
                assertWithMessage("$appearance value chosen, muted $muted").that(contrast(c.valueInk(focused = true, muted), lit(c))).isAtLeast(4.5f)
            }
            for ((name, ink) in listOf("labelFocus" to c.labelFocus, "valueFocus" to c.valueFocus)) {
                assertWithMessage("$appearance $name in the band's light").that(contrast(ink, lit(c))).isAtLeast(4.5f)
            }
        }
    }

    @Test
    fun `a muted value is quieter than a value, and chosen no brighter than another value at rest`() {
        for (appearance in Appearance.entries) {
            val c = QuickColors.of(appearance)
            val veil = c.veil.toArgb()
            val atRest = contrast(c.valueInk(focused = false, muted = false), veil)
            assertWithMessage("$appearance at rest").that(contrast(c.valueInk(focused = false, muted = true), veil)).isLessThan(atRest)
            assertWithMessage("$appearance chosen").that(contrast(c.valueInk(focused = true, muted = true), veil)).isAtMost(atRest)
            // Chosen, it still says it is chosen: it comes forward from its quiet ink.
            assertWithMessage("$appearance chosen").that(contrast(c.valueInk(focused = true, muted = true), veil))
                .isGreaterThan(contrast(c.valueInk(focused = false, muted = true), veil))
        }
    }
}
