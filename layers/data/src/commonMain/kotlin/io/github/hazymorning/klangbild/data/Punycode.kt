/*
 * Ported from OkHttp 5.5.0 (okhttp3.internal.idn.Punycode), Copyright Square, Inc., Apache
 * License 2.0.
 */
package io.github.hazymorning.klangbild.data

/**
 * Punycode ([RFC 3492](https://datatracker.ietf.org/doc/html/rfc3492)), which writes the labels of
 * international host names in ASCII, like `bücher` as `xn--bcher-kva`. Ported from OkHttp 5.5.0,
 * Copyright Square, Inc., Apache License 2.0.
 */
internal object Punycode {
    private const val PREFIX = "xn--"

    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 0x80

    /**
     * Encodes each `.`-separated label of [string] that isn't ASCII. Returns null if a label is so
     * long that encoding it would overflow, which no label within DNS's limits is.
     */
    fun encode(string: String): String? {
        var pos = 0
        val limit = string.length
        val result = StringBuilder()
        while (pos < limit) {
            var dot = string.indexOf('.', startIndex = pos)
            if (dot == -1) dot = limit
            if (!encodeLabel(string, pos, dot, result)) return null
            if (dot < limit) {
                result.append('.')
                pos = dot + 1
            } else {
                break
            }
        }
        return result.toString()
    }

    private fun encodeLabel(string: String, pos: Int, limit: Int, result: StringBuilder): Boolean {
        if (!string.requiresEncode(pos, limit)) {
            result.appendRange(string, pos, limit)
            return true
        }

        result.append(PREFIX)

        val input = string.codePoints(pos, limit)

        // Copy all the basic code points to the output.
        var b = 0
        for (codePoint in input) {
            if (codePoint < INITIAL_N) {
                result.append(codePoint.toChar())
                b++
            }
        }

        // Copy a delimiter if any basic code points were emitted.
        if (b > 0) result.append('-')

        var n = INITIAL_N
        var delta = 0
        var bias = INITIAL_BIAS
        var h = b
        while (h < input.size) {
            val m = input.minBy { if (it >= n) it else Int.MAX_VALUE }

            val increment = (m - n) * (h + 1)
            if (delta > Int.MAX_VALUE - increment) return false // Prevent overflow.
            delta += increment

            n = m

            for (c in input) {
                if (c < n) {
                    if (delta == Int.MAX_VALUE) return false // Prevent overflow.
                    delta++
                } else if (c == n) {
                    var q = delta

                    for (k in BASE until Int.MAX_VALUE step BASE) {
                        val t = when {
                            k <= bias -> TMIN
                            k >= bias + TMAX -> TMAX
                            else -> k - bias
                        }
                        if (q < t) break
                        result.append((t + ((q - t) % (BASE - t))).punycodeDigit)
                        q = (q - t) / (BASE - t)
                    }

                    result.append(q.punycodeDigit)
                    bias = adapt(delta, h + 1, h == b)
                    delta = 0
                    h++
                }
            }
            delta++
            n++
        }

        return true
    }

    /** Decodes each `.`-separated label of [string] that starts with `xn--`. Returns null if one is malformed. */
    fun decode(string: String): String? {
        var pos = 0
        val limit = string.length
        val result = StringBuilder()
        while (pos < limit) {
            var dot = string.indexOf('.', startIndex = pos)
            if (dot == -1) dot = limit
            if (!decodeLabel(string, pos, dot, result)) return null
            if (dot < limit) {
                result.append('.')
                pos = dot + 1
            } else {
                break
            }
        }
        return result.toString()
    }

    /** Decodes the label of [string] from [start] to [limit]. Returns false if it's malformed. */
    private fun decodeLabel(string: String, start: Int, limit: Int, result: StringBuilder): Boolean {
        if (!string.regionMatches(start, PREFIX, 0, 4, ignoreCase = true)) {
            result.appendRange(string, start, limit)
            return true
        }

        var pos = start + 4

        // The code points are decoded in increasing order, not in the order they appear in.
        val codePoints = mutableListOf<Int>()

        // Copy the basic code points before the last delimiter, if there's one.
        val lastDelimiter = string.lastIndexOf('-', limit)
        if (lastDelimiter >= pos) {
            while (pos < lastDelimiter) {
                when (val codePoint = string[pos++]) {
                    in 'a'..'z', in 'A'..'Z', in '0'..'9', '-' -> codePoints += codePoint.code
                    else -> return false
                }
            }
            pos++ // Consume '-'.
        }

        var n = INITIAL_N
        var i = 0
        var bias = INITIAL_BIAS

        while (pos < limit) {
            val oldi = i
            var w = 1
            for (k in BASE until Int.MAX_VALUE step BASE) {
                if (pos == limit) return false
                val c = string[pos++]
                val digit = when (c) {
                    in 'a'..'z' -> c - 'a'
                    in 'A'..'Z' -> c - 'A'
                    in '0'..'9' -> c - '0' + 26
                    else -> return false
                }
                val deltaI = digit * w
                if (i > Int.MAX_VALUE - deltaI) return false // Prevent overflow.
                i += deltaI
                val t = when {
                    k <= bias -> TMIN
                    k >= bias + TMAX -> TMAX
                    else -> k - bias
                }
                if (digit < t) break
                val scaleW = BASE - t
                if (w > Int.MAX_VALUE / scaleW) return false // Prevent overflow.
                w *= scaleW
            }
            bias = adapt(i - oldi, codePoints.size + 1, oldi == 0)
            val deltaN = i / (codePoints.size + 1)
            if (n > Int.MAX_VALUE - deltaN) return false // Prevent overflow.
            n += deltaN
            i %= (codePoints.size + 1)

            if (n > 0x10ffff) return false // Not a valid code point.

            codePoints.add(i, n)

            i++
        }

        for (codePoint in codePoints) {
            result.appendUtf8CodePoint(codePoint)
        }

        return true
    }

    /** Returns a new bias. */
    private fun adapt(delta: Int, numpoints: Int, first: Boolean): Int {
        var scaled = if (first) delta / DAMP else delta / 2
        scaled += (scaled / numpoints)
        var k = 0
        while (scaled > ((BASE - TMIN) * TMAX) / 2) {
            scaled /= (BASE - TMIN)
            k += BASE
        }
        return k + (((BASE - TMIN + 1) * scaled) / (scaled + SKEW))
    }

    private fun String.requiresEncode(pos: Int, limit: Int): Boolean {
        for (i in pos until limit) {
            if (this[i].code >= INITIAL_N) return true
        }
        return false
    }

    /** The code points from [pos] to [limit], with `?` for a lone surrogate. */
    private fun String.codePoints(pos: Int, limit: Int): List<Int> {
        val result = mutableListOf<Int>()
        var i = pos
        while (i < limit) {
            val c = this[i]
            result += when {
                c.isSurrogate() -> {
                    val low = if (i + 1 < limit) this[i + 1] else '\u0000'
                    if (c.isLowSurrogate() || !low.isLowSurrogate()) {
                        '?'.code
                    } else {
                        i++
                        0x010000 + (c.code and 0x03ff shl 10 or (low.code and 0x03ff))
                    }
                }
                else -> c.code
            }
            i++
        }
        return result
    }

    private val Int.punycodeDigit: Char
        get() = when {
            this < 26 -> 'a' + this
            this < 36 -> '0' + (this - 26)
            else -> error("unexpected digit: $this")
        }
}
