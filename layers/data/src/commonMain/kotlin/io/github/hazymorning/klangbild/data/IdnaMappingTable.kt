/*
 * Ported from OkHttp 5.5.0 (okhttp3.internal.idn.IdnaMappingTable), Copyright Square, Inc.,
 * Apache License 2.0.
 */
package io.github.hazymorning.klangbild.data

/**
 * The IDNA mapping table that turns each code point of a host name into what it stands for, like
 * `A` into `a`, or rejects it. Ported from OkHttp 5.5.0, Copyright Square, Inc., Apache License
 * 2.0. The data is in [IDNA_MAPPING_TABLE].
 *
 * A code point splits into a 14-bit section and a 7-bit position in it. [sections] holds four
 * characters per section: the section (14 bits) and where its ranges start in [ranges] (14 bits,
 * counted in entries). A section that's left out has the same ranges as the one before it.
 *
 * [ranges] holds four characters per range, b0 to b3. A range starts at position b0 of its section
 * and ends where the next one starts. b1 says what happens to its code points:
 *
 *  * 0 to 63: they map to the b1 UTF-16 characters of [mappings] at (b2 << 7) + b3.
 *  * 64 to 79: they move down by ((b1 & 0xF) << 14) + (b2 << 7) + b3.
 *  * 80 to 95: they move up by the same amount.
 *  * 119: they're left out.
 *  * 120: they stay.
 *  * 121: they aren't allowed.
 *  * 122: they map to the ASCII character b2.
 *  * 124: they map to the ASCII characters b2 and b3.
 *  * 127: they map to the character whose UTF-8 bytes are b2 + 0x80 and b3 + 0x80.
 */
internal class IdnaMappingTable(
    private val sections: String,
    private val ranges: String,
    private val mappings: String,
) {
    /** Appends what [codePoint] maps to to [sink]. Returns false if it isn't allowed. */
    fun map(codePoint: Int, sink: StringBuilder): Boolean {
        val sectionsIndex = findSectionsIndex(codePoint)
        val rangesPosition = sections.read14BitInt(sectionsIndex + 2)
        val rangesLimit = when {
            sectionsIndex + 4 < sections.length -> sections.read14BitInt(sectionsIndex + 6)
            else -> ranges.length / 4
        }
        val rangesIndex = findRangesOffset(codePoint, rangesPosition, rangesLimit)

        when (val b1 = ranges[rangesIndex + 1].code) {
            in 0..63 -> {
                val beginIndex = ranges.read14BitInt(rangesIndex + 2)
                sink.append(mappings, beginIndex, beginIndex + b1)
            }
            in 64..79 -> {
                val b2 = ranges[rangesIndex + 2].code
                val b3 = ranges[rangesIndex + 3].code
                sink.appendUtf8CodePoint(codePoint - ((b1 and 0xF shl 14) or (b2 shl 7) or b3))
            }
            in 80..95 -> {
                val b2 = ranges[rangesIndex + 2].code
                val b3 = ranges[rangesIndex + 3].code
                sink.appendUtf8CodePoint(codePoint + ((b1 and 0xF shl 14) or (b2 shl 7) or b3))
            }
            119 -> Unit
            120 -> sink.appendUtf8CodePoint(codePoint)
            121 -> return false
            122 -> sink.append(ranges[rangesIndex + 2])
            124 -> sink.append(ranges[rangesIndex + 2]).append(ranges[rangesIndex + 3])
            127 -> {
                val b2 = ranges[rangesIndex + 2].code
                val b3 = ranges[rangesIndex + 3].code
                sink.append(((b2 and 0x1f shl 6) or (b3 and 0x3f)).toChar())
            }
            else -> error("unexpected rangesIndex for $codePoint")
        }
        return true
    }

    /** Binary searches [sections] for the section of [codePoint], its top 14 bits. */
    private fun findSectionsIndex(codePoint: Int): Int {
        val target = (codePoint and 0x1fff80) shr 7
        val offset = binarySearch(position = 0, limit = sections.length / 4) { index ->
            target.compareTo(sections.read14BitInt(index * 4))
        }
        // Not found? Use the preceding section.
        return if (offset >= 0) offset * 4 else (-offset - 2) * 4
    }

    /** Binary searches [ranges] for the range of [codePoint], by its bottom 7 bits. */
    private fun findRangesOffset(codePoint: Int, position: Int, limit: Int): Int {
        val target = codePoint and 0x7f
        val offset = binarySearch(position = position, limit = limit) { index ->
            target.compareTo(ranges[index * 4].code)
        }
        // Not found? Use the preceding range.
        return if (offset >= 0) offset * 4 else (-offset - 2) * 4
    }
}

private fun String.read14BitInt(index: Int): Int = (this[index].code shl 7) + this[index + 1].code

/**
 * The index of the match in `position until limit`, which [compare] checks, or
 * `-1 - insertionPoint` if there's none.
 */
private inline fun binarySearch(position: Int, limit: Int, compare: (Int) -> Int): Int {
    var low = position
    var high = limit - 1
    while (low <= high) {
        val mid = (low + high) / 2
        val compareResult = compare(mid)
        when {
            compareResult < 0 -> high = mid - 1
            compareResult > 0 -> low = mid + 1
            else -> return mid
        }
    }
    return -low - 1
}
