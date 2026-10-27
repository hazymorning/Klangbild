/*
 * Ported from OkHttp 5.5.0 (okhttp3.internal.-HostnamesCommon), Copyright Square, Inc., Apache
 * License 2.0.
 */
package io.github.hazymorning.klangbild.data

import doist.x.normalize.Form
import doist.x.normalize.normalize

/**
 * This host in canonical form, as OkHttp writes it, or null if it isn't a valid host. An IPv6
 * address, with or without brackets, comes back in its shortest form without brackets (or as IPv4
 * if it maps one). Anything else goes through IDNA: it's mapped, lowercased and normalized, and
 * its international labels are written in Punycode, so `☃.net` becomes `xn--n3h.net` and
 * `WwW.GoOgLe.cOm` becomes `www.google.com`.
 */
internal fun String.toCanonicalHost(): String? {
    val host: String = this

    // If the input contains a ':', it's an IPv6 address.
    if (":" in host) {
        // If the input is in square brackets "[...]", drop them.
        val bracketed = host.startsWith("[") && host.endsWith("]")
        val address = (if (bracketed) decodeIpv6(host, 1, host.length - 1) else decodeIpv6(host, 0, host.length))
            ?: return null
        return if (address.isMappedIpv4Address()) inet4AddressToAscii(address, 12) else inet6AddressToAscii(address)
    }

    val result = idnToAscii(host) ?: return null
    if (result.isEmpty()) return null
    if (result.containsInvalidHostnameAsciiCodes()) return null
    if (result.containsInvalidLabelLengths()) return null

    return result
}

/**
 * Whether this is empty or longer than DNS allows (253 characters), or has a label that's empty or
 * longer than 63 characters. A trailing dot is allowed.
 */
private fun String.containsInvalidLabelLengths(): Boolean {
    if (length !in 1..253) return true

    var labelStart = 0
    while (true) {
        val dot = indexOf('.', startIndex = labelStart)
        val labelLength = if (dot == -1) length - labelStart else dot - labelStart
        if (labelLength !in 1..63) return true
        if (dot == -1) break
        if (dot == length - 1) break // Trailing '.' is allowed.
        labelStart = dot + 1
    }

    return false
}

/** Whether this has a character that can't be in a host: a control character, non-ASCII, or one of ` #%/:?@[\]`. */
private fun String.containsInvalidHostnameAsciiCodes(): Boolean {
    for (i in 0 until length) {
        val c = this[i]
        if (c <= '\u001f' || c >= '\u007f') return true
        if (" #%/:?@[\\]".indexOf(c) != -1) return true
    }
    return false
}

/** The bytes of this IP address, IPv4 in dotted decimal or IPv6 without brackets, or null if it isn't one. */
internal fun String.decodeIpAddress(): ByteArray? =
    if (':' in this) decodeIpv6(this, 0, length) else ByteArray(4).takeIf { decodeIpv4Suffix(this, 0, length, it, 0) }

/** Decodes an IPv6 address like 1111:2222:3333:4444:5555:6666:7777:8888 or ::1. */
private fun decodeIpv6(input: String, pos: Int, limit: Int): ByteArray? {
    val address = ByteArray(16)
    var b = 0
    var compress = -1
    var groupOffset = -1

    var i = pos
    while (i < limit) {
        if (b == address.size) return null // Too many groups.

        // Read a delimiter.
        if (i + 2 <= limit && input.startsWith("::", startIndex = i)) {
            // Compression "::" delimiter, which is anywhere in the input, including its prefix.
            if (compress != -1) return null // Multiple "::" delimiters.
            i += 2
            b += 2
            compress = b
            if (i == limit) break
        } else if (b != 0) {
            // Group separator ":" delimiter.
            if (input.startsWith(":", startIndex = i)) {
                i++
            } else if (input.startsWith(".", startIndex = i)) {
                // If we see a '.', rewind to the beginning of the previous group and parse as IPv4.
                if (!decodeIpv4Suffix(input, groupOffset, limit, address, b - 2)) return null
                b += 2 // We rewound two bytes and then added four.
                break
            } else {
                return null // Wrong delimiter.
            }
        }

        // Too many groups after a "::", like 1:2:3:4:5:6:7::8. OkHttp 5.5.0 writes past the end of
        // the address here and throws ArrayIndexOutOfBoundsException, which its parsers don't catch.
        if (b == address.size) return null

        // Read a group, one to four hex digits.
        var value = 0
        groupOffset = i
        while (i < limit) {
            val hexDigit = input[i].parseHexDigit()
            if (hexDigit == -1) break
            value = (value shl 4) + hexDigit
            i++
        }
        val groupLength = i - groupOffset
        if (groupLength == 0 || groupLength > 4) return null // Group is the wrong size.

        // We've successfully read a group. Assign its value to our byte array.
        address[b++] = (value.ushr(8) and 0xff).toByte()
        address[b++] = (value and 0xff).toByte()
    }

    // If compression happened, move the bytes after it to the end of the address, as in
    // 1111:2222:3333::7777:8888, which is read as 1111:2222:3333:7777:8888:0:0:0 at first.
    if (b != address.size) {
        if (compress == -1) return null // Address didn't have compression or enough groups.
        address.copyInto(address, address.size - (b - compress), compress, b)
        address.fill(0.toByte(), compress, compress + (address.size - b))
    }

    return address
}

/**
 * Decodes the IPv4 address from [pos] to [limit] into [address] from [addressOffset]: a whole one, or
 * the end of an IPv6 address like 1111::5555:6666:192.168.0.1. Leading zeros are refused.
 */
private fun decodeIpv4Suffix(input: String, pos: Int, limit: Int, address: ByteArray, addressOffset: Int): Boolean {
    var b = addressOffset

    var i = pos
    while (i < limit) {
        if (b == address.size) return false // Too many groups.

        // Read a delimiter.
        if (b != addressOffset) {
            if (input[i] != '.') return false // Wrong delimiter.
            i++
        }

        // Read 1 or more decimal digits for a value in 0..255.
        var value = 0
        val groupOffset = i
        while (i < limit) {
            val c = input[i]
            if (c < '0' || c > '9') break
            if (value == 0 && groupOffset != i) return false // Reject unnecessary leading '0's.
            value = value * 10 + c.code - '0'.code
            if (value > 255) return false // Value out of range.
            i++
        }
        val groupLength = i - groupOffset
        if (groupLength == 0) return false // No digits.

        // We've successfully read a byte.
        address[b++] = value.toByte()
    }

    // Check for too few groups. We wanted exactly four.
    return b == addressOffset + 4
}

/** Encodes an IPv6 address in canonical form according to RFC 5952. */
private fun inet6AddressToAscii(address: ByteArray): String {
    // Go through the address looking for the longest run of 0s. Each group is two bytes.
    // A run must be longer than one group (section 4.2.2).
    // If there are multiple equal runs, the first one must be used (section 4.2.3).
    var longestRunOffset = -1
    var longestRunLength = 0
    var i = 0
    while (i < address.size) {
        val currentRunOffset = i
        while (i < 16 && address[i].toInt() == 0 && address[i + 1].toInt() == 0) {
            i += 2
        }
        val currentRunLength = i - currentRunOffset
        if (currentRunLength > longestRunLength && currentRunLength >= 4) {
            longestRunOffset = currentRunOffset
            longestRunLength = currentRunLength
        }
        i += 2
    }

    // Emit each group of two bytes in hex, separated by ':'. The longest run of zeroes is "::".
    val result = StringBuilder()
    i = 0
    while (i < address.size) {
        if (i == longestRunOffset) {
            result.append(':')
            i += longestRunLength
            if (i == 16) result.append(':')
        } else {
            if (i > 0) result.append(':')
            val group = (address[i].toInt() and 0xff shl 8) or (address[i + 1].toInt() and 0xff)
            result.append(group.toString(16))
            i += 2
        }
    }
    return result.toString()
}

/** Whether this IPv6 address maps an IPv4 address, like `::ffff:192.168.1.1`. */
private fun ByteArray.isMappedIpv4Address(): Boolean {
    for (i in 0 until 10) {
        if (this[i] != 0.toByte()) return false
    }
    return this[10] == 255.toByte() && this[11] == 255.toByte()
}

/** Writes the four bytes of [address] from [offset] on as an IPv4 address. */
private fun inet4AddressToAscii(address: ByteArray, offset: Int): String =
    (offset until offset + 4).joinToString(".") { (address[it].toInt() and 0xff).toString() }

/**
 * The ASCII form of an international host name, by IDNA (UTS #46) as OkHttp does it: map each code
 * point, normalize to NFC, decode the labels already in Punycode, check that the result is still
 * NFC, and encode the labels that aren't ASCII in Punycode. Null if the name isn't allowed.
 *
 * NFC comes from `java.text.Normalizer` on the JVM and Android, as in OkHttp, and from JavaScript's
 * `String.normalize` in the browser. The two can follow different Unicode versions. That only
 * matters for a label already in Punycode that decodes to characters assigned in between, like
 * `xn--9i0fa`, which a newer version composes and so rejects.
 */
private fun idnToAscii(host: String): String? {
    // 1. Map. A lone surrogate reads as '?', as OkHttp's UTF-8 round trip reads it.
    val mapped = StringBuilder()
    var i = 0
    while (i < host.length) {
        val codePoint = host.codePointAt(i)
        val readable = if (codePoint in 0xd800..0xdfff) '?'.code else codePoint
        if (!IDNA_MAPPING_TABLE.map(readable, mapped)) return null
        i += charCount(codePoint)
    }

    // 2. Normalize.
    val normalized = mapped.toString().normalize(Form.NFC)

    // 3. For each label, convert and validate Punycode.
    val decoded = Punycode.decode(normalized) ?: return null

    // 4. Validate that it's still NFC.
    if (decoded != decoded.normalize(Form.NFC)) return null

    return Punycode.encode(decoded)
}
