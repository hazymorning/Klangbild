package io.github.hazymorning.klangbild.ui.text

/**
 * One of ICU's rule sets for line break opportunities, run the way ICU runs it: ICU compiles
 * its rules into a state machine over categories of code points, and [LineBreakTables] holds
 * those machines as ICU4J ships them. Going forward through a text with them gives exactly the
 * boundaries ICU's line break iterator gives, without the dictionaries ICU adds for Thai, Lao,
 * Khmer and Myanmar.
 */
internal class LineBreakRules(
    /** The first code point of each range of categories, ascending, the first one 0. */
    private val starts: IntArray,
    private val categoryCount: Int,
    private val lookAheadSize: Int,
    /** The category of each range of [starts]. */
    private val categories: ByteArray,
    /** One row per state: accepting, look-ahead, tag, then the next state for each category. */
    private val table: ByteArray,
) {
    private val rowLength = 3 + categoryCount

    /** The category of the code point [c]. */
    private fun category(c: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (starts[middle] <= c) low = middle else high = middle - 1
        }
        return categories[low].toInt() and 0xff
    }

    /** The boundaries of [text] after its start, ascending; the last one is the text's end. */
    fun boundaries(text: CharSequence): IntArray {
        val out = IntArray(text.length)
        var count = 0
        val lookAhead = IntArray(lookAheadSize)
        var position = 0
        while (position < text.length) {
            position = next(text, position, lookAhead)
            out[count++] = position
        }
        return out.copyOf(count)
    }

    /** The boundary after [from], as ICU's RuleBasedBreakIterator.handleNext() finds it. */
    private fun next(text: CharSequence, from: Int, lookAhead: IntArray): Int {
        lookAhead.fill(-1)
        var index = from
        var c = codePointAt(text, index)
        index += charCount(c)
        var result = from
        var row = START_STATE * rowLength
        var mode = RUN
        var category = 0
        while (true) {
            if (c < 0) {
                if (mode == END) break
                mode = END
                category = END_OF_TEXT
            }
            if (mode == RUN) category = category(c)
            val state = table[row + 3 + category].toInt() and 0xff
            row = state * rowLength
            val accepting = table[row].toInt() and 0xff
            if (accepting == ACCEPTING) {
                result = index
            } else if (accepting > ACCEPTING) {
                val position = lookAhead[accepting]
                if (position >= 0) return position
            }
            val rule = table[row + 1].toInt() and 0xff
            if (rule > ACCEPTING) lookAhead[rule] = index
            if (state == STOP_STATE) break
            if (mode == RUN) {
                c = codePointAt(text, index)
                if (c >= 0) index += charCount(c)
            }
        }
        // The rules always move on by at least one code point; ICU makes sure of it.
        if (result == from) result = from + charCount(codePointAt(text, from))
        return result
    }

    private companion object {
        const val STOP_STATE = 0
        const val START_STATE = 1
        const val ACCEPTING = 1

        /** The category ICU reserves for the end of the text. */
        const val END_OF_TEXT = 1

        const val RUN = 1
        const val END = 2
    }
}

/** The code point at [index] (a lone surrogate as itself), or -1 at the end. */
internal fun codePointAt(text: CharSequence, index: Int): Int {
    if (index >= text.length) return -1
    val c = text[index]
    if (c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
        return 0x10000 + ((c.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
    }
    return c.code
}

/** The code point that ends at [index] (a lone surrogate as itself). */
internal fun codePointBefore(text: CharSequence, index: Int): Int {
    val c = text[index - 1]
    if (c.isLowSurrogate() && index >= 2 && text[index - 2].isHighSurrogate()) {
        return 0x10000 + ((text[index - 2].code - 0xD800) shl 10) + (c.code - 0xDC00)
    }
    return c.code
}

internal fun charCount(c: Int): Int = if (c >= 0x10000) 2 else 1

/** True if [c] is in [ranges], given as first and last of each range, ascending. */
internal fun inRanges(ranges: IntArray, c: Int): Boolean {
    var low = 0
    var high = ranges.size / 2 - 1
    while (low <= high) {
        val middle = (low + high) ushr 1
        when {
            c < ranges[2 * middle] -> high = middle - 1
            c > ranges[2 * middle + 1] -> low = middle + 1
            else -> return true
        }
    }
    return false
}

/** Bytes from base 64 text (RFC 4648 without padding), as the build writes [LineBreakTables]. */
internal fun decodeBase64(vararg chunks: String): ByteArray {
    val text = chunks.joinToString("")
    val out = ByteArray(text.length * 3 / 4)
    var bits = 0
    var count = 0
    var o = 0
    for (ch in text) {
        val value = when (ch) {
            in 'A'..'Z' -> ch - 'A'
            in 'a'..'z' -> ch - 'a' + 26
            in '0'..'9' -> ch - '0' + 52
            '+' -> 62
            else -> 63
        }
        bits = bits shl 6 or value
        count += 6
        if (count >= 8) {
            count -= 8
            out[o++] = (bits shr count).toByte()
        }
    }
    return out
}

/** The starts of the ranges of categories: differences from one to the next in groups of seven bits. */
internal fun readStarts(vararg chunks: String): IntArray {
    val bytes = decodeBase64(*chunks)
    val out = ArrayList<Int>()
    out.add(0)
    var value = 0
    var shift = 0
    var last = 0
    for (b in bytes) {
        val v = b.toInt() and 0xff
        value = value or ((v and 0x7f) shl shift)
        if (v and 0x80 != 0) {
            shift += 7
        } else {
            last += value
            out.add(last)
            value = 0
            shift = 0
        }
    }
    return out.toIntArray()
}
