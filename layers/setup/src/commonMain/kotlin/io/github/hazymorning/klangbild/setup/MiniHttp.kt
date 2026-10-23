package io.github.hazymorning.klangbild.setup

import io.github.hazymorning.klangbild.data.formDecode
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * A deliberately tiny HTTP/1.1 implementation for the setup page: one request per
 * connection, `Content-Length` bodies only, hard size limits. That is all a phone browser
 * filling in one form needs, and it keeps the attack surface small.
 */
object MiniHttp {
    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_BODY_BYTES = 64 * 1024

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val bodyText: String get() = body.decodeToString()
    }

    data class Response(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap(),
    ) {
        companion object {
            fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8") =
                Response(status, type, text.encodeToByteArray())

            fun json(status: Int, json: String) = text(status, json, "application/json; charset=utf-8")
            fun html(html: String) = text(200, html, "text/html; charset=utf-8")
        }
    }

    class BadRequest(message: String) : Exception(message)

    fun read(input: Source): Request {
        val head = Buffer()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            if (input.exhausted()) throw BadRequest("connection closed")
            val b = input.readByte()
            head.writeByte(b)
            if (head.size > MAX_HEADER_BYTES) throw BadRequest("headers too large")
            matched = if (b == terminator[matched]) matched + 1 else if (b == terminator[0]) 1 else 0
        }
        val lines = latin1(head.readByteArray()).split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw BadRequest("empty request")
        val parts = lines[0].split(' ')
        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) throw BadRequest("bad request line")
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i <= 0) throw BadRequest("bad header")
            headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw BadRequest("chunked bodies are not supported")
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) throw BadRequest("body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.readAtMostTo(body, read, length)
            if (n < 0) throw BadRequest("truncated body")
            read += n
        }
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        return Request(method, path, query, headers, body)
    }

    fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            decode(k) to decode(v)
        }
    }

    private fun decode(s: String) = try {
        formDecode(s)
    } catch (_: IllegalArgumentException) {
        s
    }

    /** ISO-8859-1, as HTTP's head is read: a character for each byte. */
    private fun latin1(bytes: ByteArray): String = CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()

    fun write(out: Sink, response: Response) {
        val reason = when (response.status) {
            200 -> "OK"
            204 -> "No Content"
            303 -> "See Other"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "Error"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(response.status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(response.contentType).append("\r\n")
        sb.append("Content-Length: ").append(response.body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("X-Frame-Options: DENY\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        sb.append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; form-action 'self'\r\n")
        response.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        // ISO-8859-1 as well: a question mark for each UTF-16 unit it has no byte for.
        out.write(sb.toString().let { h -> ByteArray(h.length) { (if (h[it].code <= 0xFF) h[it].code else '?'.code).toByte() } })
        out.write(response.body)
        out.flush()
    }
}
