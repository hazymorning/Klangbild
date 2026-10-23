package io.github.hazymorning.klangbild.data

import io.github.hazymorning.klangbild.data.WebUrl.Companion.toWebUrl
import io.github.hazymorning.klangbild.data.WebUrl.Companion.toWebUrlOrNull
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol

/**
 * Points this request at [url], read as OkHttp's `Request.Builder().url(String)` reads it, so that
 * OkHttp requests exactly what it would for [url]. A `ws:` or `wss:` URL stays a WebSocket's for
 * Ktor, and OkHttp reads it as `http:` or `https:`. Throws IllegalArgumentException where OkHttp does.
 *
 * Ktor's own parser changes some URLs. It writes a stray `%` as `%25`, drops empty query
 * parameters and trims spaces, among other things. So this sets the builder's encoded parts
 * instead, with the whole query as one parameter name, which Ktor writes as it is.
 *
 * Two kinds of URL go whole into the host instead, after the scheme, which Ktor also writes as it
 * is. One has a `%` without two hex digits after it, which OkHttp keeps but Ktor can't decode when
 * it builds the request. The other has a canonical form that OkHttp reads differently, like a host
 * that mixes non-ASCII letters with `_`, and goes as it was given.
 */
fun HttpRequestBuilder.address(url: String) {
    val webSocket = url.startsWith("ws:", ignoreCase = true) || url.startsWith("wss:", ignoreCase = true)
    val input = when {
        url.startsWith("ws:", ignoreCase = true) -> "http:" + url.substring(3)
        url.startsWith("wss:", ignoreCase = true) -> "https:" + url.substring(4)
        else -> url
    }
    val parsed = input.toWebUrl()
    val secure = parsed.scheme == "https"
    val target = this.url
    target.protocol = when {
        webSocket -> if (secure) URLProtocol.WSS else URLProtocol.WS
        else -> if (secure) URLProtocol.HTTPS else URLProtocol.HTTP
    }
    target.encodedParameters = ParametersBuilder()
    target.trailingQuery = false
    val canonical = parsed.toString()
    if (canonical.toWebUrlOrNull()?.toString() != canonical) {
        // OkHttp reads its canonical form differently, so it gets the URL as it was given.
        val schemeEnd = input.indexOf(':') + 1
        target.whole(input.substring(schemeEnd).trimStart('/', '\\'))
    } else if (!parsed.ktorCanDecode()) {
        target.whole(canonical.substringAfter("://"))
    } else {
        target.host = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
        target.port = parsed.port
        val user = parsed.encodedUsername
        val password = parsed.encodedPassword
        target.encodedUser = if (user.isEmpty() && password.isEmpty()) null else user
        target.encodedPassword = password.ifEmpty { null }
        // Ktor leaves out an empty fragment, so its '#' goes at the end of the query or the path.
        val hash = if (parsed.encodedFragment == "") "#" else ""
        val query = parsed.encodedQuery
        val segments = parsed.encodedPathSegments
        target.encodedPathSegments = listOf("") + if (query == null) segments.dropLast(1) + (segments.last() + hash) else segments
        if (query != null) target.encodedParameters.appendAll(query + hash, emptyList())
        target.encodedFragment = parsed.encodedFragment.orEmpty()
    }
}

/** Sets [rest], everything after the scheme's `://`, as the host, which Ktor writes as it is. */
private fun URLBuilder.whole(rest: String) {
    host = rest
    port = DEFAULT_PORT
    encodedUser = null
    encodedPassword = null
    encodedPathSegments = emptyList()
    encodedFragment = ""
}

/** Whether Ktor can decode each part of this URL, as it does when it builds a request. */
private fun WebUrl.ktorCanDecode(): Boolean =
    encodedUsername.hasOnlyValidEscapes() &&
        encodedPassword.hasOnlyValidEscapes() &&
        encodedPathSegments.all { it.hasOnlyValidEscapes() } &&
        encodedQuery?.hasOnlyValidEscapes() != false &&
        encodedFragment?.hasOnlyValidEscapes() != false

/** Whether each `%` in this has two hex digits after it. */
private fun String.hasOnlyValidEscapes(): Boolean {
    var i = indexOf('%')
    while (i != -1) {
        if (i + 2 >= length || this[i + 1].parseHexDigit() == -1 || this[i + 2].parseHexDigit() == -1) return false
        i = indexOf('%', i + 3)
    }
    return true
}
