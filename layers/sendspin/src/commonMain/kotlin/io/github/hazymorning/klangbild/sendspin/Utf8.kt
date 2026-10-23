package io.github.hazymorning.klangbild.sendspin

/**
 * [text] in UTF-8 as the JVM writes it: a surrogate without its pair becomes a question mark,
 * where Kotlin's own encoder would write a replacement character.
 */
internal fun utf8(text: String): ByteArray {
    var lone = false
    for (i in text.indices) {
        val c = text[i]
        if (c.isHighSurrogate() && !(i + 1 < text.length && text[i + 1].isLowSurrogate())) lone = true
        if (c.isLowSurrogate() && !(i > 0 && text[i - 1].isHighSurrogate())) lone = true
    }
    if (!lone) return text.encodeToByteArray()
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            out.append(c).append(text[i + 1])
            i += 2
            continue
        }
        out.append(if (c.isSurrogate()) '?' else c)
        i++
    }
    return out.toString().encodeToByteArray()
}
