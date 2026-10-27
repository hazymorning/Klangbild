package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em
import io.github.hazymorning.klangbild.domain.Emoji

/**
 * Color emoji at display size are louder than the typography. Emoji at the edges of a title are
 * already gone (see [io.github.hazymorning.klangbild.domain.TrackText]). The ones inside a
 * title are set smaller and dimmed, and glued to their neighbors with no-break spaces, so
 * an emoji never ends up alone on its own line.
 */
object EmojiText {
    private const val NBSP = ' '

    /** Emoji are set this much smaller than the text around them. */
    private const val SCALE = 0.74f

    fun isEmoji(cp: Int): Boolean = Emoji.isEmoji(cp)

    fun containsEmoji(text: String): Boolean = Emoji.containsEmoji(text)

    /** Splits [text] into runs of (isEmoji, run). */
    private fun runs(text: String): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        val sb = StringBuilder()
        var current: Boolean? = null
        var i = 0
        while (i < text.length) {
            val cp = Emoji.codePointAt(text, i)
            val e = isEmoji(cp)
            if (current != null && e != current) {
                out += current to sb.toString()
                sb.clear()
            }
            current = e
            val next = i + Emoji.charCount(cp)
            sb.appendRange(text, i, next)
            i = next
        }
        if (current != null && sb.isNotEmpty()) out += current to sb.toString()
        return out
    }

    /**
     * @param dimTo if set, emoji are drawn in this color's alpha (color glyphs keep their
     *              colors but become translucent on the dark wall, so they look dimmer).
     */
    fun annotate(text: String, dimTo: Color? = null): AnnotatedString {
        if (!containsEmoji(text)) return AnnotatedString(text)
        val parts = runs(text)
        return buildAnnotatedString {
            parts.forEachIndexed { index, (emoji, run) ->
                var r = run
                if (!emoji) {
                    // glue emoji to the words around them (a lone ✨ on its own line looks lost)
                    if (index > 0 && parts[index - 1].first && r.startsWith(' ')) r = NBSP + r.drop(1)
                    if (index < parts.lastIndex && parts[index + 1].first && r.endsWith(' ')) r = r.dropLast(1) + NBSP
                    append(r)
                } else {
                    val start = length
                    append(r)
                    addStyle(SpanStyle(fontSize = SCALE.em, color = dimTo ?: Color.Unspecified), start, length)
                }
            }
        }
    }
}
