package io.github.hazymorning.klangbild.domain

/** Emoji detection, good enough for typography decisions (not a full UTS #51 parser). */
object Emoji {
    fun isEmoji(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x20E3 || cp in 0xE0020..0xE007F

    /** True if [text] contains at least one visible emoji (not just joiners/selectors). */
    fun containsEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isEmoji(cp) && cp != 0x200D && cp != 0xFE0F) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /**
     * Removes emoji (and the spaces next to them) at the start and end of [text]:
     * "🌙 Moonlight Tape ✨" → "Moonlight Tape". Decoration at the edges of a title shouts
     * over the typography; emoji inside a title are part of it and stay. A title made only
     * of emoji is left alone.
     */
    fun trimEdges(text: String): String {
        var start = 0
        var end = text.length
        while (start < end) {
            val cp = text.codePointAt(start)
            if (isEmoji(cp) || Character.isWhitespace(cp) || cp == 0xA0) start += Character.charCount(cp) else break
        }
        while (end > start) {
            val cp = text.codePointBefore(end)
            if (isEmoji(cp) || Character.isWhitespace(cp) || cp == 0xA0) end -= Character.charCount(cp) else break
        }
        val trimmed = text.substring(start, end)
        return if (trimmed.any { it.isLetterOrDigit() }) trimmed else text.trim()
    }
}
