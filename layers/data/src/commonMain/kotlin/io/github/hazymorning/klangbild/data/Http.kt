package io.github.hazymorning.klangbild.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray

/** [server] answered with an HTTP status that is neither success nor a refused token. */
class HttpStatusException(server: String, val code: Int) : IOException("$server answered $code")

/**
 * The client for requests and WebSockets on [engine], as the platform sets it up. The engine
 * follows redirects where it should and keeps the cache; Ktor itself follows none.
 */
fun httpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    followRedirects = false
    install(HttpTimeout)
    install(WebSockets)
}

/**
 * Runs the request [describe] sets up and reads its answer with [read]. Cancelable: canceling the
 * coroutine cancels the request, so a cover nobody waits for any more is not downloaded to the
 * end. The whole call, reading included, ends after [timeoutMs] at the latest. With
 * [readTimeoutMs] it may wait that long for the next bytes, instead of as long as the client's
 * engine lets it.
 */
suspend fun <T> HttpClient.fetch(
    timeoutMs: Long,
    describe: HttpRequestBuilder.() -> Unit,
    readTimeoutMs: Long? = null,
    read: suspend (HttpResponse) -> T,
): T = prepareRequest {
    describe()
    timeout {
        requestTimeoutMillis = timeoutMs
        socketTimeoutMillis = readTimeoutMs
    }
}.execute(read)

/**
 * Carries [token] in the Authorization header. A token with a character no header holds is
 * refused as OkHttp refuses it, leaving the token out of the message, which may be logged.
 */
fun HttpRequestBuilder.bearer(token: String) {
    val value = "Bearer $token"
    for ((i, c) in value.withIndex()) {
        require(c == '\t' || c in '\u0020'..'\u007e') { "Unexpected char 0x${c.code.toString(16).padStart(2, '0')} at $i in Authorization value" }
    }
    header("Authorization", value)
}

/** Up to [limit] bytes of the body: all of it when there are fewer. A body cut off by a failure throws it. */
suspend fun HttpResponse.bodyBytes(limit: Long): ByteArray {
    val body = bodyAsChannel()
    val bytes = body.readBuffer(limit).readByteArray()
    body.closedCause?.let { throw it }
    return bytes
}

/** The body as UTF-8 text, or an [IOException] when it has [limit] bytes or more. */
suspend fun HttpResponse.textBelow(limit: Long): String {
    val bytes = bodyBytes(limit)
    if (bytes.size >= limit) throw IOException("Answer too large")
    return bytes.decodeToString()
}
