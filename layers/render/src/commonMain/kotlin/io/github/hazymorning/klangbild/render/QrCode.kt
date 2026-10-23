package io.github.hazymorning.klangbild.render

import kotlin.math.abs
import kotlin.math.max

/**
 * QR codes for links a phone opens: the setup page's, and a party's for its guests.
 *
 * The encoder is ZXing's (3.5.4, Copyright 2007 and 2008 ZXing authors, Apache License 2.0),
 * ported to common Kotlin and cut down to the way it's used here: error correction level M, the
 * whole text in one mode (digits, uppercase letters and a few symbols, or else ISO-8859-1
 * bytes), the smallest version that holds it, and the mask with the lowest penalty. Its codes
 * match ZXing's on Android module for module.
 */
object QrCode {

    /**
     * The modules of [text]'s code, row by row (true is dark), without a quiet zone around them.
     * Text that is empty or too long for a QR code throws an [IllegalArgumentException].
     */
    fun matrix(text: String): Array<BooleanArray> {
        require(text.isNotEmpty()) { "No text to encode" }
        val (version, data) = dataCodewords(text)
        val codewords = interleave(data, version)
        val best = (0 until 8).map { modules(codewords, version, it) }.minBy(::penalty)
        return Array(best.size) { y -> BooleanArray(best.size) { x -> best[y][x] == 1 } }
    }

    /** The smallest version that holds [text], and the data codewords of its code. */
    private fun dataCodewords(text: String): Pair<Int, ByteArray> {
        val mode = when {
            text.all { it in '0'..'9' } -> Mode.NUMERIC
            text.all { it in ALPHANUMERIC } -> Mode.ALPHANUMERIC
            else -> Mode.BYTE
        }
        // The text as values and their sizes in bits: three digits in 10 bits (two in 7, one in
        // 4), two characters in 11 (one in 6), a byte in 8.
        val chunks = when (mode) {
            Mode.NUMERIC -> text.chunked(3) { it.toString().toInt() to it.length * 3 + 1 }
            Mode.ALPHANUMERIC -> text.chunked(2) {
                val first = ALPHANUMERIC.indexOf(it[0])
                if (it.length == 2) first * 45 + ALPHANUMERIC.indexOf(it[1]) to 11 else first to 6
            }
            Mode.BYTE -> latin1(text).map { (it.toInt() and 0xFF) to 8 }
        }
        val count = if (mode == Mode.BYTE) chunks.size else text.length
        val length = 4 + chunks.sumOf { it.second }
        val version = (1..40).firstOrNull { length + mode.countBits(it) <= DATA[it - 1] * 8 }
            ?: throw IllegalArgumentException("Too long for a QR code")

        val data = ByteArray(DATA[version - 1])
        var bit = 0
        fun put(value: Int, size: Int) {
            for (i in size - 1 downTo 0) {
                if ((value shr i) and 1 == 1) {
                    data[bit / 8] = (data[bit / 8].toInt() or (0x80 shr (bit % 8))).toByte()
                }
                bit++
            }
        }
        put(mode.indicator, 4)
        put(count, mode.countBits(version))
        for ((value, size) in chunks) put(value, size)
        // Up to four zero bits end the data and zeros fill its last byte. Then the pad codewords
        // 0xEC and 0x11 take turns until the code is full.
        val padFrom = (bit + 4 + 7) / 8
        for (i in padFrom until data.size) {
            data[i] = (if ((i - padFrom) % 2 == 0) 0xEC else 0x11).toByte()
        }
        return version to data
    }

    /**
     * [data] in the blocks of [version] (the last few a codeword longer), each with its error
     * correction codewords, interleaved: the first data codeword of every block, then the
     * second, and so on, then the error correction codewords in the same way.
     */
    private fun interleave(data: ByteArray, version: Int): ByteArray {
        val count = BLOCKS[version - 1]
        val generator = generator(EC_PER_BLOCK[version - 1])
        val short = data.size / count
        val shortCount = count - data.size % count
        var offset = 0
        val blocks = List(count) {
            val size = if (it < shortCount) short else short + 1
            data.copyOfRange(offset, offset + size).also { offset += size }
        }
        val corrections = blocks.map { errorCorrection(it, generator) }
        val codewords = ByteArray(data.size + corrections.sumOf { it.size })
        var next = 0
        for (i in 0..short) for (block in blocks) if (i < block.size) codewords[next++] = block[i]
        for (i in corrections[0].indices) for (block in corrections) codewords[next++] = block[i]
        return codewords
    }

    /** The modules of [version] with [codewords] in them, masked with [mask]: 1 is dark. */
    private fun modules(codewords: ByteArray, version: Int, mask: Int): Array<IntArray> {
        val size = 17 + 4 * version
        val m = Array(size) { IntArray(size) { EMPTY } }
        // The finder patterns in three corners, each with a light border inside the code.
        for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
            for (y in cy - 4..cy + 4) for (x in cx - 4..cx + 4) {
                if (x !in 0 until size || y !in 0 until size) continue
                val ring = max(abs(x - cx), abs(y - cy))
                m[y][x] = if (ring == 2 || ring == 4) 0 else 1
            }
        }
        // One module beside the bottom left finder pattern is always dark.
        m[size - 8][8] = 1
        // The alignment patterns, where no finder pattern is.
        val centers = ALIGNMENT[version - 1]
        for (cy in centers) for (cx in centers) {
            if (m[cy][cx] != EMPTY) continue
            for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) {
                m[y][x] = if (max(abs(x - cx), abs(y - cy)) == 1) 0 else 1
            }
        }
        // The timing patterns between the finder patterns.
        for (i in 8 until size - 8) {
            if (m[6][i] == EMPTY) m[6][i] = (i + 1) % 2
            if (m[i][6] == EMPTY) m[i][6] = (i + 1) % 2
        }
        // The format (level M is 0, then the mask), twice: around the top left finder pattern,
        // and split between the other two.
        val format = ((mask shl 10) or bch(mask, 0x537)) xor 0x5412
        for (i in 0 until 15) {
            val bit = (format shr i) and 1
            when {
                i < 6 -> m[i][8] = bit
                i < 8 -> m[i + 1][8] = bit
                i == 8 -> m[8][7] = bit
                else -> m[8][14 - i] = bit
            }
            if (i < 8) m[8][size - 1 - i] = bit else m[size - 15 + i][8] = bit
        }
        // From version 7 on, the version, twice: beside the bottom left and the top right finder
        // patterns.
        if (version >= 7) {
            val info = (version shl 12) or bch(version, 0x1F25)
            for (i in 0 until 18) {
                val bit = (info shr i) and 1
                m[size - 11 + i % 3][i / 3] = bit
                m[i / 3][size - 11 + i % 3] = bit
            }
        }
        // The codewords' bits, masked, in columns two modules wide from the right, going up and
        // down by turns and skipping the vertical timing pattern. What's left over stays light
        // before the mask.
        var bit = 0
        var up = true
        var x = size - 1
        while (x > 0) {
            if (x == 6) x--
            for (k in 0 until size) {
                val y = if (up) size - 1 - k else k
                for (xx in x downTo x - 1) {
                    if (m[y][xx] != EMPTY) continue
                    val dark = bit < codewords.size * 8 &&
                        (codewords[bit / 8].toInt() shr (7 - bit % 8)) and 1 == 1
                    bit++
                    m[y][xx] = if (dark != masked(mask, xx, y)) 1 else 0
                }
            }
            up = !up
            x -= 2
        }
        return m
    }

    /** Whether [mask] flips the module at [x], [y]. */
    private fun masked(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0
        6 -> (x * y % 2 + x * y % 3) % 2 == 0
        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    /**
     * How hard [m] is to read, by the standard's four rules as ZXing counts them: the lower, the
     * better.
     */
    private fun penalty(m: Array<IntArray>): Int {
        val size = m.size
        var penalty = 0
        for (line in m + Array(size) { x -> IntArray(size) { y -> m[y][x] } }) {
            // A run of five or more modules of one color in a row or column: 3, and 1 for each
            // module more.
            var run = 1
            for (i in 1..size) {
                if (i < size && line[i] == line[i - 1]) {
                    run++
                } else {
                    if (run >= 5) penalty += run - 2
                    run = 1
                }
            }
            // The middle row of a finder pattern with four light modules before or after it: 40.
            // ZXing doesn't count the space outside the code as light.
            for (i in 0..size - 7) {
                if (finderLike(line, i) && (light(line, i - 4) || light(line, i + 7))) penalty += 40
            }
        }
        // Every square of two by two modules of one color: 3.
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = m[y][x]
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) penalty += 3
        }
        // 10 for each full 5% the share of dark modules is away from half.
        val dark = m.sumOf { row -> row.count { it == 1 } }
        return penalty + abs(dark * 2 - size * size) * 10 / (size * size) * 10
    }

    /** Whether [line] has the middle row of a finder pattern from [from] on. */
    private fun finderLike(line: IntArray, from: Int): Boolean {
        for (i in FINDER.indices) if (line[from + i] != FINDER[i]) return false
        return true
    }

    /** Whether the four modules of [line] from [from] on are all inside it and light. */
    private fun light(line: IntArray, from: Int): Boolean {
        if (from < 0 || from + 4 > line.size) return false
        for (i in from until from + 4) if (line[i] == 1) return false
        return true
    }

    /**
     * The BCH code of [value]: the remainder of [value], times x to the degree of [poly], divided
     * by [poly] (bits as coefficients).
     */
    private fun bch(value: Int, poly: Int): Int {
        val degree = 31 - poly.countLeadingZeroBits()
        var r = value shl degree
        while (31 - r.countLeadingZeroBits() >= degree) {
            r = r xor (poly shl (31 - r.countLeadingZeroBits() - degree))
        }
        return r
    }

    /**
     * The Reed-Solomon generator polynomial (x + 1)(x + a)...(x + a^(degree - 1)) in GF(256), its
     * coefficients from the highest power down.
     */
    private fun generator(degree: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(g.size + 1)
            for (j in g.indices) {
                next[j] = next[j] xor g[j]
                next[j + 1] = next[j + 1] xor times(g[j], EXP[i])
            }
            g = next
        }
        return g
    }

    /**
     * The error correction codewords of [block]: the remainder of its polynomial, times x to the
     * degree of [generator], divided by [generator].
     */
    private fun errorCorrection(block: ByteArray, generator: IntArray): ByteArray {
        val r = IntArray(generator.size - 1)
        for (b in block) {
            val factor = (b.toInt() and 0xFF) xor r[0]
            for (j in 0 until r.size - 1) r[j] = r[j + 1] xor times(generator[j + 1], factor)
            r[r.size - 1] = times(generator[r.size], factor)
        }
        return ByteArray(r.size) { r[it].toByte() }
    }

    /** [a] times [b] in GF(256). */
    private fun times(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[(LOG[a] + LOG[b]) % 255]

    /**
     * [text] as ISO-8859-1 bytes, as Android gives them to ZXing: a question mark for each UTF-16
     * unit that has no byte, so two for an emoji.
     */
    private fun latin1(text: String): ByteArray =
        ByteArray(text.length) { (if (text[it].code <= 0xFF) text[it].code else '?'.code).toByte() }

    /**
     * How the text is written, with its [indicator] in front. Its length takes as many bits as
     * [count] says for versions 1 to 9, 10 to 26 and 27 to 40.
     */
    private enum class Mode(val indicator: Int, private val count: IntArray) {
        NUMERIC(1, intArrayOf(10, 12, 14)),
        ALPHANUMERIC(2, intArrayOf(9, 11, 13)),
        BYTE(4, intArrayOf(8, 16, 16));

        fun countBits(version: Int): Int =
            count[if (version <= 9) 0 else if (version <= 26) 1 else 2]
    }

    /** A module that nothing has been placed in yet. */
    private const val EMPTY = -1

    /** The characters of the alphanumeric mode, each worth its index. */
    private const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    /** A finder pattern's middle row: dark, light, three dark, light, dark. */
    private val FINDER = intArrayOf(1, 0, 1, 1, 1, 0, 1)

    /** Data codewords at level M, for versions 1 to 40. */
    private val DATA = intArrayOf(
        16, 28, 44, 64, 86, 108, 124, 154, 182, 216, 254, 290, 334, 365, 415, 453, 507, 563, 627,
        669, 714, 782, 860, 914, 1000, 1062, 1128, 1193, 1267, 1373, 1455, 1541, 1631, 1725, 1812,
        1914, 1992, 2102, 2216, 2334,
    )

    /** Error correction codewords per block at level M, for versions 1 to 40. */
    private val EC_PER_BLOCK = intArrayOf(
        10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
        26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
    )

    /** Blocks at level M, for versions 1 to 40. */
    private val BLOCKS = intArrayOf(
        1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
        17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
    )

    /** The rows and columns of the alignment patterns' centers, for versions 1 to 40. */
    private val ALIGNMENT = arrayOf(
        intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
        intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50), intArrayOf(6, 30, 54), intArrayOf(6, 32, 58), intArrayOf(6, 34, 62),
        intArrayOf(6, 26, 46, 66), intArrayOf(6, 26, 48, 70), intArrayOf(6, 26, 50, 74),
        intArrayOf(6, 30, 54, 78), intArrayOf(6, 30, 56, 82), intArrayOf(6, 30, 58, 86),
        intArrayOf(6, 34, 62, 90), intArrayOf(6, 28, 50, 72, 94), intArrayOf(6, 26, 50, 74, 98),
        intArrayOf(6, 30, 54, 78, 102), intArrayOf(6, 28, 54, 80, 106),
        intArrayOf(6, 32, 58, 84, 110), intArrayOf(6, 30, 58, 86, 114),
        intArrayOf(6, 34, 62, 90, 118), intArrayOf(6, 26, 50, 74, 98, 122),
        intArrayOf(6, 30, 54, 78, 102, 126), intArrayOf(6, 26, 52, 78, 104, 130),
        intArrayOf(6, 30, 56, 82, 108, 134), intArrayOf(6, 34, 60, 86, 112, 138),
        intArrayOf(6, 30, 58, 86, 114, 142), intArrayOf(6, 34, 62, 90, 118, 146),
        intArrayOf(6, 30, 54, 78, 102, 126, 150), intArrayOf(6, 24, 50, 76, 102, 128, 154),
        intArrayOf(6, 28, 54, 80, 106, 132, 158), intArrayOf(6, 32, 58, 84, 110, 136, 162),
        intArrayOf(6, 26, 54, 82, 110, 138, 166), intArrayOf(6, 30, 58, 86, 114, 142, 170),
    )

    /** The powers of a, the field's generator, in GF(256) with x^8 + x^4 + x^3 + x^2 + 1. */
    private val EXP = IntArray(255).apply {
        var x = 1
        for (i in indices) {
            this[i] = x
            x = x shl 1
            if (x > 0xFF) x = x xor 0x11D
        }
    }

    /** The logarithms to the base a, the inverse of [EXP]. */
    private val LOG = IntArray(256).apply { for (i in EXP.indices) this[EXP[i]] = i }
}
