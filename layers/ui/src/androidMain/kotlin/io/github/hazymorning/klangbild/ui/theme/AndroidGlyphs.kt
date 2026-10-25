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
    override fun drawing(style: TextStyle, resolver: FontFamily.Resolver, sizePx: Float): Glyphs.Pen {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal).value as? Typeface ?: Typeface.DEFAULT
        paint.textSize = sizePx
        return object : Glyphs.Pen {
            override fun advance(text: String, start: Int, end: Int): Float = paint.measureText(text, start, end)

            override fun outline(text: String, start: Int, end: Int, x: Float, y: Float): Path =
                android.graphics.Path().also { paint.getTextPath(text, start, end, x, y, it) }.asComposePath()
        }
    }
}
