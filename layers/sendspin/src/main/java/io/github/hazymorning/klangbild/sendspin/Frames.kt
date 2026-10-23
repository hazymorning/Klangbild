package io.github.hazymorning.klangbild.sendspin

/** A message that breaks the protocol: the connection is closed without another word. */
class ProtocolException(message: String) : Exception(message)

/** The ids of binary messages (messaging.md, "Binary Message ID Structure") that Klangbild reads. */
object MessageId {
    const val JSON = 0
    const val FRAGMENT = 1
    const val AUDIO = 4
    const val BEAT = 17

    /** An audio chunk's header: the id, the timestamp (int64) and `send_ahead` (uint32). */
    const val AUDIO_HEADER = 13

    /** A visualizer frame's header: the id and the timestamp. */
    const val VISUALIZER_HEADER = 9
}

/**
 * Puts fragmented messages back together (messaging.md, "Fragmentation"): one in flight at a
 * time, its fragments in order, every malformed sequence a [ProtocolException]. Messages of an
 * id nobody reads are followed through their fragments without being kept.
 */
class Reassembler(
    /** The longest message kept; a longer one ends the connection. */
    private val limit: Int,
    /** Whether messages with this id are read at all. */
    private val wanted: (Int) -> Boolean,
) {
    private var inFlight = -1
    private var buffer = ByteArray(0)
    private var size = 0

    /**
     * Takes one decrypted binary message. Returns a whole message, its id in the first byte as
     * on the wire (the same array when it came in one piece), or null while a fragmented one is
     * still coming or one nobody reads ends.
     */
    fun accept(message: ByteArray): ByteArray? {
        if (message.isEmpty()) throw ProtocolException("empty message")
        val id = message[0].toInt() and 0xff
        if (id != MessageId.FRAGMENT) {
            if (inFlight >= 0) throw ProtocolException("message $id while a fragmented message is in flight")
            return message
        }
        if (message.size < 2) throw ProtocolException("fragment without flags")
        val flags = message[1].toInt() and 0xff
        if (flags and RESERVED != 0) throw ProtocolException("fragment with reserved flags")
        val first = flags and FIRST != 0
        val last = flags and LAST != 0
        val dataFrom: Int
        if (first) {
            if (inFlight >= 0) throw ProtocolException("first fragment while one is in flight")
            if (message.size < 3) throw ProtocolException("first fragment without its type")
            val orig = message[2].toInt() and 0xff
            if (orig == MessageId.FRAGMENT) throw ProtocolException("fragment of fragments")
            inFlight = orig
            size = 0
            dataFrom = 3
        } else {
            if (inFlight < 0) throw ProtocolException("fragment with none in flight")
            dataFrom = 2
        }
        val keep = wanted(inFlight)
        if (keep) append(message, dataFrom)
        if (!last) return null
        val type = inFlight
        inFlight = -1
        if (!keep) return null
        val whole = ByteArray(size + 1)
        whole[0] = type.toByte()
        System.arraycopy(buffer, 0, whole, 1, size)
        size = 0
        return whole
    }

    private fun append(message: ByteArray, from: Int) {
        val n = message.size - from
        if (size + n > limit) throw ProtocolException("fragmented message beyond $limit bytes")
        if (size + n > buffer.size) buffer = buffer.copyOf(maxOf(size + n, buffer.size * 2))
        System.arraycopy(message, from, buffer, size, n)
        size += n
    }

    private companion object {
        const val LAST = 1
        const val FIRST = 2
        const val RESERVED = 0xfc
    }
}

/** Big-endian reads from a message's bytes. */
fun ByteArray.int64(at: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (this[at + i].toLong() and 0xff)
    return v
}

internal fun ByteArray.uint32(at: Int): Long {
    var v = 0L
    for (i in 0 until 4) v = (v shl 8) or (this[at + i].toLong() and 0xff)
    return v
}
