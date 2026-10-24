package io.github.hazymorning.klangbild.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Text as the platform's own engine sets it, for what Compose's text doesn't give: how far a
 * stretch of text moves the pen, and its glyphs' outlines. The rest clock is drawn from the
 * outlines ([ClockFigures]).
 */
interface Glyphs {
    /** A pen for figures drawn as outlines, in [style]'s typeface (or the platform's own) at [sizePx] px. */
    fun drawing(style: TextStyle, resolver: FontFamily.Resolver, sizePx: Float): Pen

    interface Pen {
        /** How far the pen moves over [text] from [start] to [end], the kerning inside it included (px). */
        fun advance(text: String, start: Int = 0, end: Int = text.length): Float

        /** The outlines of [text] from [start] to [end], the pen starting at [x] on the baseline at [y]. */
        fun outline(text: String, start: Int = 0, end: Int = text.length, x: Float = 0f, y: Float = 0f): Path
    }
}

/** The platform's glyphs, which it sets where the composition starts. */
val LocalGlyphs = staticCompositionLocalOf<Glyphs> { error("The platform sets LocalGlyphs where the composition starts") }
