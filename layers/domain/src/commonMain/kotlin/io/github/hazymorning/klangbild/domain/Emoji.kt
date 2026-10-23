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
            val cp = codePointAt(text, i)
            if (isEmoji(cp) && cp != 0x200D && cp != 0xFE0F) return true
            i += charCount(cp)
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
            val cp = codePointAt(text, start)
            if (isEmoji(cp) || isSpace(cp)) start += charCount(cp) else break
        }
        while (end > start) {
            val cp = codePointBefore(text, end)
            if (isEmoji(cp) || isSpace(cp)) end -= charCount(cp) else break
        }
        val trimmed = text.substring(start, end)
        return if (trimmed.any { it.isLetterOrDigit() }) trimmed else text.trim()
    }

    /** White space as Java's Character.isWhitespace has it, and the no-break space. */
    private fun isSpace(cp: Int): Boolean = when (cp) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0xA0 -> true
        0x2007, 0x202F -> false
        else -> cp < 0x10000 && cp.toChar().category.let {
            it == CharCategory.SPACE_SEPARATOR || it == CharCategory.LINE_SEPARATOR || it == CharCategory.PARAGRAPH_SEPARATOR
        }
    }

    /** The code point at [i] in [text]: a surrogate pair is one. */
    fun codePointAt(text: String, i: Int): Int {
        val c = text[i]
        val next = text.getOrNull(i + 1)
        return if (c.isHighSurrogate() && next != null && next.isLowSurrogate()) pair(c, next) else c.code
    }

    /** The code point that ends just before [end] in [text]. */
    private fun codePointBefore(text: String, end: Int): Int {
        val c = text[end - 1]
        val before = text.getOrNull(end - 2)
        return if (c.isLowSurrogate() && before != null && before.isHighSurrogate()) pair(before, c) else c.code
    }

    private fun pair(high: Char, low: Char): Int = ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

    /** How many chars [cp] takes: two above the Basic Multilingual Plane. */
    fun charCount(cp: Int): Int = if (cp >= 0x10000) 2 else 1
}
