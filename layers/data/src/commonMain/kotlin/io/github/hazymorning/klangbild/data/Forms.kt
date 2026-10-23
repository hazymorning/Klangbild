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
 * [text] decoded from a form or a query: `+` becomes a space, and each run of `%XX` becomes the
 * characters of its UTF-8 bytes, with U+FFFD for a malformed sequence. A `%` without two hex
 * digits (0-9, a-f, A-F) after it throws IllegalArgumentException.
 *
 * It follows `java.net.URLDecoder.decode(text, "UTF-8")` on Android, messages included.
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
                    val high = text[i + 1].parseHexDigit()
                    val low = text[i + 2].parseHexDigit()
                    if (high == -1 || low == -1) {
                        throw IllegalArgumentException(
                            "URLDecoder: Illegal hex characters in escape (%) pattern : " + text.substring(i, i + 3),
                        )
                    }
                    buffer[size++] = (high * 16 + low).toByte()
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
