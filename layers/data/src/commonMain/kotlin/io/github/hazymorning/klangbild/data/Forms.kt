package io.github.hazymorning.klangbild.data

/**
 * [text] encoded for a form or a query, exactly as `java.net.URLEncoder.encode(text, "UTF-8")`
 * encodes it: letters, digits and `-_.*` stay, a space becomes `+`, and every other character
 * becomes `%XX` for each of its UTF-8 bytes, `%3F` for a lone surrogate.
 */
fun formEncode(text: String): String = text.canonicalize(encodeSet = FORM_ENCODE_SET)

/**
 * The body of a form with these names and values, exactly as OkHttp's `FormBody` writes it:
 * `name=value` pairs joined by `&`, each part encoded as [formEncode] encodes it.
 */
fun formBody(vararg fields: Pair<String, String>): String =
    fields.joinToString("&") { (name, value) -> formEncode(name) + "=" + formEncode(value) }

/**
 * [text] decoded from a form or a query, exactly as `java.net.URLDecoder.decode(text, "UTF-8")`
 * decodes it: `+` becomes a space, and each run of `%XX` becomes the characters of its UTF-8
 * bytes, with U+FFFD for a malformed sequence. Like it, this throws IllegalArgumentException for a
 * `%` without two hex digits after it.
 *
 * The bytes are read with [decodeToString], which on the JVM and Android is the platform's own
 * decoder, the one URLDecoder uses. Elsewhere a surrogate written in UTF-8 (ED, then A0 to BF)
 * reads as one U+FFFD per byte instead of one for the sequence.
 */
fun formDecode(text: String): String {
    val out = StringBuilder(text.length)
    var bytes: ByteArray? = null
    var i = 0
    while (i < text.length) {
        var c = text[i]
        when (c) {
            '+' -> {
                out.append(' ')
                i++
            }
            '%' -> {
                // (text.length - i) / 3 bytes at most are left.
                val buffer = bytes ?: ByteArray((text.length - i) / 3).also { bytes = it }
                var size = 0
                while (i + 2 < text.length && c == '%') {
                    buffer[size++] = hexByte(text, i + 1).toByte()
                    i += 3
                    if (i < text.length) c = text[i]
                }
                if (i < text.length && c == '%') {
                    throw IllegalArgumentException("URLDecoder: Incomplete trailing escape (%) pattern")
                }
                out.append(buffer.decodeToString(0, size))
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/**
 * The two characters of [text] at [start] read as a number in hex, as URLDecoder reads them with
 * `Integer.parseInt(text, start, start + 2, 16)`: a sign may come first, and any digit Java knows
 * counts, like a fullwidth one.
 */
private fun hexByte(text: String, start: Int): Int {
    var i = start
    var negative = false
    if (text[i] < '0') {
        when (text[i]) {
            '-' -> negative = true
            '+' -> Unit
            else -> throw illegalHex(text, start, i)
        }
        i++
    }
    var value = 0
    while (i < start + 2) {
        val digit = text[i].digitToIntOrNull(16) ?: throw illegalHex(text, start, i)
        value = value * 16 + digit
        i++
    }
    if (negative && value != 0) {
        throw IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - negative value")
    }
    return value
}

private fun illegalHex(text: String, start: Int, index: Int) =
    IllegalArgumentException(
        "URLDecoder: Illegal hex characters in escape (%) pattern - Error at index ${index - start} in: " +
            "\"${text.substring(start, start + 2)}\"",
    )
