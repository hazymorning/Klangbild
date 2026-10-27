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
internal object EmojiText {
    /** Emoji are set this much smaller than the text around them. */
    private const val SCALE = 0.74f

    /** Emoji are drawn at this alpha of the text's ink. */
    private const val ALPHA = 0.62f

    /** Splits [text] into runs of (isEmoji, run). */
    private fun runs(text: String): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        val sb = StringBuilder()
        var current: Boolean? = null
        var i = 0
        while (i < text.length) {
            val cp = Emoji.codePointAt(text, i)
            val e = Emoji.isEmoji(cp)
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
     * [text] with its emoji set smaller and glued to their neighbors. With an [ink], the emoji are
     * drawn at [ALPHA] of it: color glyphs keep their colors but turn translucent, so they look
     * dimmer.
     */
    fun annotate(text: String, ink: Color? = null): AnnotatedString {
        if (!Emoji.containsEmoji(text)) return AnnotatedString(text)
        val parts = runs(text)
        return buildAnnotatedString {
            parts.forEachIndexed { index, (emoji, run) ->
                var r = run
                if (!emoji) {
                    // Glue emoji to the neighboring words, so none stands alone on a line.
                    if (index > 0 && parts[index - 1].first && r.startsWith(' ')) r = NBSP + r.drop(1)
                    if (index < parts.lastIndex && parts[index + 1].first && r.endsWith(' ')) r = r.dropLast(1) + NBSP
                    append(r)
                } else {
                    val start = length
                    append(r)
                    addStyle(SpanStyle(fontSize = SCALE.em, color = ink?.copy(alpha = ALPHA) ?: Color.Unspecified), start, length)
                }
            }
        }
    }
}
