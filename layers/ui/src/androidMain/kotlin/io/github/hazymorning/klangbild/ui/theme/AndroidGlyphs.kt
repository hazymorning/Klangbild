package io.github.hazymorning.klangbild.ui.theme

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** [Glyphs] from Android's text engine, in the typeface Compose's text is set in. */
object AndroidGlyphs : Glyphs {
    override fun measuring(style: TextStyle, resolver: FontFamily.Resolver, sizePx: Float, letterSpacing: Float): Glyphs.Pen? {
        val typeface = typeface(style, resolver) ?: return null
        return pen(Paint(), typeface, sizePx, letterSpacing)
    }

    override fun drawing(style: TextStyle, resolver: FontFamily.Resolver, sizePx: Float): Glyphs.Pen =
        pen(Paint(Paint.ANTI_ALIAS_FLAG), typeface(style, resolver) ?: Typeface.DEFAULT, sizePx, 0f)

    private fun typeface(style: TextStyle, resolver: FontFamily.Resolver): Typeface? =
        resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal).value as? Typeface

    private fun pen(paint: Paint, typeface: Typeface, sizePx: Float, letterSpacing: Float): Glyphs.Pen {
        paint.typeface = typeface
        paint.textSize = sizePx
        paint.letterSpacing = letterSpacing
        return object : Glyphs.Pen {
            override fun advance(text: String, start: Int, end: Int): Float = paint.measureText(text, start, end)

            override fun outline(text: String, start: Int, end: Int, x: Float, y: Float): Path =
                android.graphics.Path().also { paint.getTextPath(text, start, end, x, y, it) }.asComposePath()
        }
    }
}
