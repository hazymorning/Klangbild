/*
 * Ported from OkHttp 5.5.0 (okhttp3.HttpUrl and okhttp3.internal.url), Copyright Square, Inc.,
 * Apache License 2.0.
 */
package io.github.hazymorning.klangbild.data

/**
 * An `http` or `https` URL, parsed and written exactly as OkHttp's `HttpUrl` does it. Ported from
 * OkHttp 5.5.0, Copyright Square, Inc., Apache License 2.0, with only what the app uses: parsing,
 * the scheme, host, port, path and query parameters, resolving a link, adding a path segment and a
 * query parameter, and the canonical string.
 */
class WebUrl private constructor(
    /** Either `http` or `https`. */
    val scheme: String,
    private val encodedUsername: String,
    private val encodedPassword: String,
    /**
     * The host in canonical form: a name in lowercase ASCII with its international labels in
     * Punycode, like `xn--bcher-kva.local`, an IPv4 address, or an IPv6 address without brackets,
     * like `::1`.
     */
    val host: String,
    /** The port the URL names, or its scheme's default: 80 for `http` and 443 for `https`. */
    val port: Int,
    private val encodedPathSegments: List<String>,
    private val encodedQuery: String?,
    /** The decoded query's names and values in turn, a value null without `=`, or null without a query. */
    private val queryNamesAndValues: List<String?>?,
    private val encodedFragment: String?,
    private val url: String,
) {
    /** The path as it's written in the URL, like `/a/b%20c`. It starts with `/`. */
    val encodedPath: String
        get() {
            val pathStart = url.indexOf('/', scheme.length + 3) // "://".length == 3.
            val pathEnd = url.delimiterOffset("?#", pathStart, url.length)
            return url.substring(pathStart, pathEnd)
        }

    /**
     * The decoded value of the first query parameter named [name], or null if there's none or it
     * has no `=`. A `+` in the query decodes as a space.
     */
    fun queryParameter(name: String): String? {
        val namesAndValues = queryNamesAndValues ?: return null
        for (i in 0 until namesAndValues.size step 2) {
            if (name == namesAndValues[i]) return namesAndValues[i + 1]
        }
        return null
    }

    /** The URL that following [link] from this one leads to, or null if that isn't a valid URL. */
    fun resolve(link: String): WebUrl? =
        try {
            Builder().parse(this, link).build()
        } catch (_: IllegalArgumentException) {
            null
        }

    /** A builder that starts as this URL. */
    fun newBuilder(): Builder {
        val result = Builder()
        result.scheme = scheme
        result.encodedUsername = encodedUsername
        result.encodedPassword = encodedPassword
        result.host = host
        // The scheme's default port stays unset.
        result.port = if (port != defaultPort(scheme)) port else -1
        result.encodedPathSegments.clear()
        result.encodedPathSegments.addAll(encodedPathSegments)
        result.encodedQuery(encodedQuery)
        result.encodedFragment = encodedFragment
        return result
    }

    /** The URL in canonical form, as OkHttp writes it. */
    override fun toString(): String = url

    /** Builds a URL from another one, with [WebUrl.newBuilder]. */
    class Builder internal constructor() {
        internal var scheme: String? = null
        internal var encodedUsername = ""
        internal var encodedPassword = ""
        internal var host: String? = null
        internal var port = -1
        internal val encodedPathSegments = mutableListOf("")
        private var encodedQueryNamesAndValues: MutableList<String?>? = null
        internal var encodedFragment: String? = null

        /** Adds [pathSegment] to the path, encoded as needed. */
        fun addPathSegment(pathSegment: String): Builder = apply {
            push(pathSegment, 0, pathSegment.length, addTrailingSlash = false, alreadyEncoded = false)
        }

        /** Adds the query parameter [name] with [value], both encoded as needed. */
        fun addQueryParameter(name: String, value: String?): Builder = apply {
            val namesAndValues = encodedQueryNamesAndValues ?: mutableListOf<String?>().also { encodedQueryNamesAndValues = it }
            namesAndValues.add(name.canonicalize(encodeSet = QUERY_COMPONENT_ENCODE_SET, plusIsSpace = true))
            namesAndValues.add(value?.canonicalize(encodeSet = QUERY_COMPONENT_ENCODE_SET, plusIsSpace = true))
        }

        /** The URL. */
        fun build(): WebUrl {
            val scheme = checkNotNull(scheme) { "scheme == null" }
            val host = checkNotNull(host) { "host == null" }
            val effectivePort = if (port != -1) port else defaultPort(scheme)
            val encodedQuery = encodedQueryNamesAndValues?.let { buildString { it.appendQueryTo(this) } }
            val encodedFragment = encodedFragment
            val url = buildString {
                append(scheme).append("://")
                if (encodedUsername.isNotEmpty() || encodedPassword.isNotEmpty()) {
                    append(encodedUsername)
                    if (encodedPassword.isNotEmpty()) append(':').append(encodedPassword)
                    append('@')
                }
                // An IPv6 address goes in brackets.
                if (':' in host) append('[').append(host).append(']') else append(host)
                if (effectivePort != defaultPort(scheme)) append(':').append(effectivePort)
                for (segment in encodedPathSegments) append('/').append(segment)
                if (encodedQuery != null) append('?').append(encodedQuery)
                if (encodedFragment != null) append('#').append(encodedFragment)
            }
            return WebUrl(
                scheme = scheme,
                encodedUsername = encodedUsername,
                encodedPassword = encodedPassword,
                host = host,
                port = effectivePort,
                encodedPathSegments = encodedPathSegments.toList(),
                encodedQuery = encodedQuery,
                queryNamesAndValues = encodedQueryNamesAndValues?.map { it?.percentDecode(plusIsSpace = true) },
                encodedFragment = encodedFragment,
                url = url,
            )
        }

        internal fun encodedQuery(encodedQuery: String?) {
            encodedQueryNamesAndValues = encodedQuery
                ?.canonicalize(encodeSet = QUERY_ENCODE_SET, alreadyEncoded = true, plusIsSpace = true)
                ?.toQueryNamesAndValues()
        }

        /** Reads [input], on its own or as a link from [base]. Throws IllegalArgumentException if it isn't a valid URL. */
        internal fun parse(base: WebUrl?, input: String): Builder {
            var pos = input.indexOfFirstNonAsciiWhitespace()
            val limit = input.indexOfLastNonAsciiWhitespace(pos)

            // Scheme.
            val schemeDelimiterOffset = schemeDelimiterOffset(input, pos, limit)
            if (schemeDelimiterOffset != -1) {
                when {
                    input.startsWith("https:", ignoreCase = true, startIndex = pos) -> {
                        this.scheme = "https"
                        pos += "https:".length
                    }
                    input.startsWith("http:", ignoreCase = true, startIndex = pos) -> {
                        this.scheme = "http"
                        pos += "http:".length
                    }
                    else -> throw IllegalArgumentException(
                        "Expected URL scheme 'http' or 'https' but was '" + input.substring(0, schemeDelimiterOffset) + "'",
                    )
                }
            } else if (base != null) {
                this.scheme = base.scheme
            } else {
                val truncated = if (input.length > 6) input.take(6) + "..." else input
                throw IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for $truncated")
            }

            // Authority.
            var hasUsername = false
            var hasPassword = false
            val slashCount = input.slashCount(pos, limit)
            if (slashCount >= 2 || base == null || base.scheme != this.scheme) {
                // Read an authority if the input starts with two or more slashes (after its scheme,
                // if it has one), or if its scheme differs from the base URL's. An authority is
                // [username[:password]@]host[:port].
                pos += slashCount
                authority@ while (true) {
                    val componentDelimiterOffset = input.delimiterOffset("@/\\?#", pos, limit)
                    val c = if (componentDelimiterOffset != limit) input[componentDelimiterOffset].code else -1
                    when (c) {
                        '@'.code -> {
                            // User info precedes.
                            if (!hasPassword) {
                                val passwordColonOffset = input.delimiterOffset(':', pos, componentDelimiterOffset)
                                val canonicalUsername = input.canonicalize(
                                    pos = pos,
                                    limit = passwordColonOffset,
                                    encodeSet = USERNAME_ENCODE_SET,
                                    alreadyEncoded = true,
                                )
                                this.encodedUsername =
                                    if (hasUsername) this.encodedUsername + "%40" + canonicalUsername else canonicalUsername
                                if (passwordColonOffset != componentDelimiterOffset) {
                                    hasPassword = true
                                    this.encodedPassword = input.canonicalize(
                                        pos = passwordColonOffset + 1,
                                        limit = componentDelimiterOffset,
                                        encodeSet = PASSWORD_ENCODE_SET,
                                        alreadyEncoded = true,
                                    )
                                }
                                hasUsername = true
                            } else {
                                this.encodedPassword = this.encodedPassword + "%40" + input.canonicalize(
                                    pos = pos,
                                    limit = componentDelimiterOffset,
                                    encodeSet = PASSWORD_ENCODE_SET,
                                    alreadyEncoded = true,
                                )
                            }
                            pos = componentDelimiterOffset + 1
                        }
                        -1, '/'.code, '\\'.code, '?'.code, '#'.code -> {
                            // Host info precedes.
                            val portColonOffset = portColonOffset(input, pos, componentDelimiterOffset)
                            if (portColonOffset + 1 < componentDelimiterOffset) {
                                host = input.percentDecode(pos = pos, limit = portColonOffset).toCanonicalHost()
                                port = parsePort(input, portColonOffset + 1, componentDelimiterOffset)
                                require(port != -1) {
                                    "Invalid URL port: \"${input.substring(portColonOffset + 1, componentDelimiterOffset)}\""
                                }
                            } else {
                                host = input.percentDecode(pos = pos, limit = portColonOffset).toCanonicalHost()
                                port = defaultPort(this.scheme!!)
                            }
                            require(host != null) { "Invalid URL host: \"${input.substring(pos, portColonOffset)}\"" }
                            pos = componentDelimiterOffset
                            break@authority
                        }
                    }
                }
            } else {
                // This is a relative link. Copy over all authority components. Also maybe the path and query.
                this.encodedUsername = base.encodedUsername
                this.encodedPassword = base.encodedPassword
                this.host = base.host
                this.port = base.port
                this.encodedPathSegments.clear()
                this.encodedPathSegments.addAll(base.encodedPathSegments)
                if (pos == limit || input[pos] == '#') {
                    encodedQuery(base.encodedQuery)
                }
            }

            // Resolve the relative path.
            val pathDelimiterOffset = input.delimiterOffset("?#", pos, limit)
            resolvePath(input, pos, pathDelimiterOffset)
            pos = pathDelimiterOffset

            // Query.
            if (pos < limit && input[pos] == '?') {
                val queryDelimiterOffset = input.delimiterOffset('#', pos, limit)
                this.encodedQueryNamesAndValues = input.canonicalize(
                    pos = pos + 1,
                    limit = queryDelimiterOffset,
                    encodeSet = QUERY_ENCODE_SET,
                    alreadyEncoded = true,
                    plusIsSpace = true,
                ).toQueryNamesAndValues()
                pos = queryDelimiterOffset
            }

            // Fragment.
            if (pos < limit && input[pos] == '#') {
                this.encodedFragment = input.canonicalize(
                    pos = pos + 1,
                    limit = limit,
                    encodeSet = FRAGMENT_ENCODE_SET,
                    alreadyEncoded = true,
                    unicodeAllowed = true,
                )
            }

            return this
        }

        private fun resolvePath(input: String, startPos: Int, limit: Int) {
            var pos = startPos
            // Read a delimiter.
            if (pos == limit) {
                // Empty path: keep the base path as-is.
                return
            }
            val c = input[pos]
            if (c == '/' || c == '\\') {
                // Absolute path: reset to the default "/".
                encodedPathSegments.clear()
                encodedPathSegments.add("")
                pos++
            } else {
                // Relative path: clear everything after the last '/'.
                encodedPathSegments[encodedPathSegments.size - 1] = ""
            }

            // Read path segments.
            var i = pos
            while (i < limit) {
                val pathSegmentDelimiterOffset = input.delimiterOffset("/\\", i, limit)
                val segmentHasTrailingSlash = pathSegmentDelimiterOffset < limit
                push(input, i, pathSegmentDelimiterOffset, segmentHasTrailingSlash, true)
                i = pathSegmentDelimiterOffset
                if (segmentHasTrailingSlash) i++
            }
        }

        /** Adds a path segment. If the input is ".." or equivalent, this pops a path segment. */
        private fun push(input: String, pos: Int, limit: Int, addTrailingSlash: Boolean, alreadyEncoded: Boolean) {
            val segment = input.canonicalize(
                pos = pos,
                limit = limit,
                encodeSet = PATH_SEGMENT_ENCODE_SET,
                alreadyEncoded = alreadyEncoded,
            )
            if (isDot(segment)) {
                return // Skip '.' path segments.
            }
            if (isDotDot(segment)) {
                pop()
                return
            }
            if (encodedPathSegments[encodedPathSegments.size - 1].isEmpty()) {
                encodedPathSegments[encodedPathSegments.size - 1] = segment
            } else {
                encodedPathSegments.add(segment)
            }
            if (addTrailingSlash) {
                encodedPathSegments.add("")
            }
        }

        /**
         * Removes a path segment. When this method returns the last segment is always "", which
         * means the encoded path will have a trailing '/'.
         *
         * Popping "/a/b/c/" yields "/a/b/". In this case the list of path segments goes from
         * ["a", "b", "c", ""] to ["a", "b", ""].
         *
         * Popping "/a/b/c" also yields "/a/b/". The list of path segments goes from ["a", "b", "c"]
         * to ["a", "b", ""].
         */
        private fun pop() {
            val removed = encodedPathSegments.removeAt(encodedPathSegments.size - 1)

            // Make sure the path ends with a '/' by either adding an empty string or clearing a segment.
            if (removed.isEmpty() && encodedPathSegments.isNotEmpty()) {
                encodedPathSegments[encodedPathSegments.size - 1] = ""
            } else {
                encodedPathSegments.add("")
            }
        }

        private fun isDot(input: String): Boolean = input == "." || input.equals("%2e", ignoreCase = true)

        private fun isDotDot(input: String): Boolean =
            input == ".." ||
                input.equals("%2e.", ignoreCase = true) ||
                input.equals(".%2e", ignoreCase = true) ||
                input.equals("%2e%2e", ignoreCase = true)

        /**
         * Returns the index of the ':' in [input] that is after scheme characters. Returns -1 if
         * [input] does not have a scheme that starts at [pos].
         */
        private fun schemeDelimiterOffset(input: String, pos: Int, limit: Int): Int {
            if (limit - pos < 2) return -1

            val c0 = input[pos]
            if ((c0 < 'a' || c0 > 'z') && (c0 < 'A' || c0 > 'Z')) return -1 // Not a scheme start char.

            for (i in pos + 1 until limit) {
                when (input[i]) {
                    // A scheme character. Keep going.
                    in 'a'..'z', in 'A'..'Z', in '0'..'9', '+', '-', '.' -> continue
                    // The end of the scheme.
                    ':' -> return i
                    // Something else before the first ':'.
                    else -> return -1
                }
            }

            return -1 // No ':'; doesn't start with a scheme.
        }

        /** Returns the number of '/' and '\' slashes in this, starting at [pos]. */
        private fun String.slashCount(pos: Int, limit: Int): Int {
            var slashCount = 0
            for (i in pos until limit) {
                val c = this[i]
                if (c == '\\' || c == '/') slashCount++ else break
            }
            return slashCount
        }

        /** Finds the first ':' in [input], skipping characters between square brackets "[...]". */
        private fun portColonOffset(input: String, pos: Int, limit: Int): Int {
            var i = pos
            while (i < limit) {
                when (input[i]) {
                    '[' -> {
                        while (++i < limit) {
                            if (input[i] == ']') break
                        }
                    }
                    ':' -> return i
                }
                i++
            }
            return limit // No colon.
        }

        /** The port from [pos] to [limit], or -1 if it isn't one. A control or non-ASCII character makes it invalid. */
        private fun parsePort(input: String, pos: Int, limit: Int): Int {
            val port = input.canonicalize(pos = pos, limit = limit, encodeSet = "").toIntOrNull() ?: return -1
            return if (port in 1..65535) port else -1
        }
    }

    companion object {
        /** This as an `http` or `https` URL. Throws IllegalArgumentException if it isn't a valid one. */
        fun String.toWebUrl(): WebUrl = Builder().parse(null, this).build()

        /** This as an `http` or `https` URL, or null if it isn't a valid one. */
        fun String.toWebUrlOrNull(): WebUrl? =
            try {
                toWebUrl()
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}

private const val USERNAME_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
private const val PASSWORD_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#"
private const val PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#"
private const val QUERY_ENCODE_SET = " \"'<>#"
private const val QUERY_COMPONENT_ENCODE_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~"
private const val FRAGMENT_ENCODE_SET = ""

/** What a form encodes besides control characters, non-ASCII characters and `%`. A space becomes `+`. */
internal const val FORM_ENCODE_SET = " !\"#$&'()+,/:;<=>?@[\\]^`{|}~"

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun defaultPort(scheme: String): Int =
    when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }

/**
 * Cuts this string up into alternating parameter names and values. This divides a query string
 * like `subject=math&easy&problem=5-2=3` into the list `["subject", "math", "easy", null,
 * "problem", "5-2=3"]`. Note that values may be null and may contain '=' characters.
 */
private fun String.toQueryNamesAndValues(): MutableList<String?> {
    val result = mutableListOf<String?>()
    var pos = 0
    while (pos <= length) {
        var ampersandOffset = indexOf('&', pos)
        if (ampersandOffset == -1) ampersandOffset = length

        val equalsOffset = indexOf('=', pos)
        if (equalsOffset == -1 || equalsOffset > ampersandOffset) {
            result.add(substring(pos, ampersandOffset))
            result.add(null) // No value for this name.
        } else {
            result.add(substring(pos, equalsOffset))
            result.add(substring(equalsOffset + 1, ampersandOffset))
        }
        pos = ampersandOffset + 1
    }
    return result
}

/** Appends the query string of these alternating names and values to [out]. */
private fun List<String?>.appendQueryTo(out: StringBuilder) {
    for (i in 0 until size step 2) {
        val name = this[i]
        val value = this[i + 1]
        if (i > 0) out.append('&')
        out.append(name)
        if (value != null) out.append('=').append(value)
    }
}

/**
 * The range of this string from [pos] to [limit] as OkHttp canonicalizes a part of a URL or a
 * form field: control characters, the characters in [encodeSet] and, unless [unicodeAllowed],
 * non-ASCII characters are percent-encoded as UTF-8.
 *
 * @param alreadyEncoded whether a `%` stays as it is and tabs and line breaks are dropped.
 * @param plusIsSpace whether a `+` stands for a space, so that a literal one is encoded as `%2B`.
 * @param unicodeAllowed whether non-ASCII characters stay as they are.
 */
internal fun String.canonicalize(
    pos: Int = 0,
    limit: Int = length,
    encodeSet: String,
    alreadyEncoded: Boolean = false,
    plusIsSpace: Boolean = false,
    unicodeAllowed: Boolean = false,
): String {
    var i = pos
    while (i < limit) {
        val codePoint = codePointAt(i)
        if (codePoint < 0x20 ||
            codePoint == 0x7f ||
            codePoint >= 0x80 && !unicodeAllowed ||
            codePoint.toChar() in encodeSet ||
            codePoint == '%'.code && !alreadyEncoded ||
            codePoint == '+'.code && plusIsSpace
        ) {
            // Slow path: the character at i requires encoding.
            val out = StringBuilder()
            var j = pos
            while (j < i) {
                val c = codePointAt(j)
                out.appendUtf8CodePoint(c)
                j += charCount(c)
            }
            out.appendCanonicalized(this, i, limit, encodeSet, alreadyEncoded, plusIsSpace, unicodeAllowed)
            return out.toString()
        }
        i += charCount(codePoint)
    }

    // Fast path: no characters from pos to limit required encoding.
    return substring(pos, limit)
}

private fun StringBuilder.appendCanonicalized(
    input: String,
    pos: Int,
    limit: Int,
    encodeSet: String,
    alreadyEncoded: Boolean,
    plusIsSpace: Boolean,
    unicodeAllowed: Boolean,
) {
    var i = pos
    while (i < limit) {
        val codePoint = input.codePointAt(i)
        when {
            alreadyEncoded &&
                (codePoint == '\t'.code || codePoint == '\n'.code || codePoint == '\u000c'.code || codePoint == '\r'.code) -> {
                // Skip this character.
            }
            codePoint == ' '.code && encodeSet == FORM_ENCODE_SET -> {
                // Encode ' ' as '+'.
                append('+')
            }
            codePoint == '+'.code && plusIsSpace -> {
                // '+' means a space here. It stays if the input is already encoded, and becomes %2B if not.
                append(if (alreadyEncoded) "+" else "%2B")
            }
            codePoint < 0x20 ||
                codePoint == 0x7f ||
                codePoint >= 0x80 && !unicodeAllowed ||
                codePoint.toChar() in encodeSet ||
                codePoint == '%'.code && !alreadyEncoded -> {
                // Percent encode this character.
                forEachUtf8Byte(codePoint) { b ->
                    append('%').append(HEX_DIGITS[b shr 4 and 0xf]).append(HEX_DIGITS[b and 0xf])
                }
            }
            else -> {
                // This character doesn't need encoding. Just copy it over.
                appendUtf8CodePoint(codePoint)
            }
        }
        i += charCount(codePoint)
    }
}

/**
 * The range of this string from [pos] to [limit] with each `%XX` decoded, and each `+` if
 * [plusIsSpace], as OkHttp decodes it. The bytes are read with [decodeToString], which on the JVM
 * and Android is the platform's decoder, as in OkHttp. Elsewhere a surrogate written in UTF-8 (ED,
 * then A0 to BF) reads as one U+FFFD per byte instead of one for the sequence.
 */
private fun String.percentDecode(pos: Int = 0, limit: Int = length, plusIsSpace: Boolean = false): String {
    for (i in pos until limit) {
        val c = this[i]
        if (c == '%' || c == '+' && plusIsSpace) {
            // Slow path: the character at i requires decoding.
            val bytes = ByteArray((limit - pos) * 3)
            var size = 0
            var j = pos
            while (j < limit) {
                val codePoint = codePointAt(j)
                if (codePoint == '%'.code && j + 2 < limit) {
                    val d1 = this[j + 1].parseHexDigit()
                    val d2 = this[j + 2].parseHexDigit()
                    if (d1 != -1 && d2 != -1) {
                        bytes[size++] = ((d1 shl 4) + d2).toByte()
                        j += 3
                        continue
                    }
                } else if (codePoint == '+'.code && plusIsSpace) {
                    bytes[size++] = ' '.code.toByte()
                    j++
                    continue
                }
                forEachUtf8Byte(codePoint) { bytes[size++] = it.toByte() }
                j += charCount(codePoint)
            }
            return bytes.decodeToString(0, size)
        }
    }

    // Fast path: no characters from pos to limit required decoding.
    return substring(pos, limit)
}

/**
 * The UTF-8 bytes of [codePoint], as okio and Java write them: a lone surrogate is written as `?`.
 * Unlike [encodeToByteArray], which writes U+FFFD for it outside the JVM, this is the same on every
 * platform.
 */
private inline fun forEachUtf8Byte(codePoint: Int, action: (Int) -> Unit) {
    when {
        codePoint < 0x80 -> action(codePoint)
        codePoint < 0x800 -> {
            action(codePoint shr 6 or 0xc0)
            action(codePoint and 0x3f or 0x80)
        }
        codePoint in 0xd800..0xdfff -> action('?'.code)
        codePoint < 0x10000 -> {
            action(codePoint shr 12 or 0xe0)
            action(codePoint shr 6 and 0x3f or 0x80)
            action(codePoint and 0x3f or 0x80)
        }
        else -> {
            action(codePoint shr 18 or 0xf0)
            action(codePoint shr 12 and 0x3f or 0x80)
            action(codePoint shr 6 and 0x3f or 0x80)
            action(codePoint and 0x3f or 0x80)
        }
    }
}

/** Appends [codePoint] as it reads after OkHttp writes it as UTF-8 and reads it back: a lone surrogate becomes `?`. */
internal fun StringBuilder.appendUtf8CodePoint(codePoint: Int): StringBuilder =
    when {
        codePoint in 0xd800..0xdfff -> append('?')
        codePoint < 0x10000 -> append(codePoint.toChar())
        else -> append((0xd7c0 + (codePoint shr 10)).toChar()).append((0xdc00 + (codePoint and 0x3ff)).toChar())
    }

/** The code point at [index], as Java's `String.codePointAt` reads it: a lone surrogate stands for itself. */
internal fun String.codePointAt(index: Int): Int {
    val c = this[index]
    if (c.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return 0x10000 + ((c.code - 0xd800) shl 10) + (low.code - 0xdc00)
    }
    return c.code
}

/** How many UTF-16 characters [codePoint] takes. */
internal fun charCount(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

/** The value of this hex digit, or -1 if it isn't one. */
internal fun Char.parseHexDigit(): Int =
    when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> -1
    }

/** The index of the first character that isn't ASCII whitespace, or [endIndex]. */
private fun String.indexOfFirstNonAsciiWhitespace(startIndex: Int = 0, endIndex: Int = length): Int {
    for (i in startIndex until endIndex) {
        when (this[i]) {
            '\t', '\n', '\u000C', '\r', ' ' -> Unit
            else -> return i
        }
    }
    return endIndex
}

/** The index after the last character that isn't ASCII whitespace, or [startIndex]. */
private fun String.indexOfLastNonAsciiWhitespace(startIndex: Int = 0, endIndex: Int = length): Int {
    for (i in endIndex - 1 downTo startIndex) {
        when (this[i]) {
            '\t', '\n', '\u000C', '\r', ' ' -> Unit
            else -> return i + 1
        }
    }
    return startIndex
}

/** The index of the first character from [startIndex] to [endIndex] that's one of [delimiters], or [endIndex]. */
private fun String.delimiterOffset(delimiters: String, startIndex: Int, endIndex: Int): Int {
    for (i in startIndex until endIndex) {
        if (this[i] in delimiters) return i
    }
    return endIndex
}

/** The index of the first [delimiter] from [startIndex] to [endIndex], or [endIndex]. */
private fun String.delimiterOffset(delimiter: Char, startIndex: Int, endIndex: Int): Int {
    for (i in startIndex until endIndex) {
        if (this[i] == delimiter) return i
    }
    return endIndex
}
