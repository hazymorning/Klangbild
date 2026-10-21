package io.github.hazymorning.klangbild.sendspin

import io.github.hazymorning.klangbild.json.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A JSON message: its `type` and `payload` (messaging.md, "Communication"). */
class Message(val type: String, val payload: JsonObject) {
    fun string(key: String): String? = payload.string(key)

    fun long(key: String): Long? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    fun int(key: String): Int? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    fun bool(key: String): Boolean? = (payload[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    fun obj(key: String): JsonObject? = payload[key] as? JsonObject

    fun strings(key: String): List<String>? = (payload[key] as? JsonArray)?.map { it.string() ?: return null }

    fun encode(): String = buildJsonObject {
        put("type", type)
        put("payload", payload)
    }.toString()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The message in [text], or a [ProtocolException] when it is not an object with a `type` string and a `payload` object. */
        fun parse(text: String): Message {
            val root = try {
                json.parseToJsonElement(text)
            } catch (_: Exception) {
                throw ProtocolException("not JSON")
            }
            val o = root as? JsonObject ?: throw ProtocolException("not an object")
            val type = o.string("type") ?: throw ProtocolException("no type")
            val payload = o["payload"] as? JsonObject ?: throw ProtocolException("no payload")
            return Message(type, payload)
        }

        fun of(type: String, payload: JsonObject = JsonObject(emptyMap())) = Message(type, payload)
    }
}

/** The audio format Klangbild plays: 16-bit PCM, stereo, 48 kHz. Nothing to decode, no gap between songs. */
object PcmFormat {
    const val CODEC = "pcm"
    const val RATE = 48_000
    const val CHANNELS = 2
    const val BITS = 16
    const val FRAME_BYTES = CHANNELS * BITS / 8

    fun matches(o: JsonObject?): Boolean = o != null &&
        o.string("codec") == CODEC &&
        (o["sample_rate"] as? JsonPrimitive)?.intOrNull == RATE &&
        (o["channels"] as? JsonPrimitive)?.intOrNull == CHANNELS &&
        (o["bit_depth"] as? JsonPrimitive)?.intOrNull == BITS

    fun json(): JsonObject = buildJsonObject {
        put("codec", CODEC)
        put("channels", CHANNELS)
        put("sample_rate", RATE)
        put("bit_depth", BITS)
    }
}

/** How this speaker introduces itself in `client/hello`. */
class Hello(
    /** Its name, as the TV is called (at most 64 bytes in UTF-8). */
    val name: String,
    val productName: String?,
    val manufacturer: String?,
    val softwareVersion: String?,
    /** Bytes of audio it can hold (player@v1's `buffer_capacity`). */
    val bufferCapacity: Int,
    /** Whether it asks for beats (visualizer@v1, beats only). */
    val beats: Boolean,
    val beatBufferCapacity: Int,
)

/** The messages Klangbild sends. */
object Outgoing {
    fun clientInit(clientId: String) = Message.of(
        "client/init",
        buildJsonObject {
            put("client_id", clientId)
            put("version", 1)
            put("suite", Noise.SUITE)
        },
    )

    fun noiseHandshake(data: ByteArray) = Message.of("noise/handshake", buildJsonObject { put("data", B64.encode(data)) })

    fun clientHello(h: Hello) = Message.of(
        "client/hello",
        buildJsonObject {
            put("name", cap(h.name))
            putJsonObject("device_info") {
                h.productName?.let { put("product_name", cap(it)) }
                h.manufacturer?.let { put("manufacturer", cap(it)) }
                h.softwareVersion?.let { put("software_version", cap(it)) }
            }
            putJsonArray("supported_roles") {
                add(JsonPrimitive(Roles.PLAYER))
                if (h.beats) add(JsonPrimitive(Roles.VISUALIZER))
            }
            putJsonObject("${Roles.PLAYER}_support") {
                putJsonArray("supported_formats") { add(PcmFormat.json()) }
                put("buffer_capacity", h.bufferCapacity)
            }
            if (h.beats) putJsonObject("${Roles.VISUALIZER}_support") { put("buffer_capacity", h.beatBufferCapacity) }
            putJsonObject("supported_pair_methods") { putJsonObject("pairing_psk") { } }
            putJsonObject("unpaired_access") { put("enabled", true) }
        },
    )

    fun clientTime(nowUs: Long) = Message.of("client/time", buildJsonObject { put("client_transmitted", nowUs) })

    /** `client/state`: available or not, with the player's (and while asked for, the beats') state. */
    fun clientState(available: Boolean, player: PlayerState?, beats: Boolean) = Message.of(
        "client/state",
        buildJsonObject {
            put("available", available)
            if (player != null) {
                putJsonObject("player") {
                    put("volume", player.volume)
                    put("muted", player.muted)
                    put("output_delay_ms", player.outputDelayMs)
                    put("required_lead_time_ms", player.requiredLeadTimeMs)
                    put("min_buffer_ms", player.minBufferMs)
                    putJsonArray("supported_commands") {
                        add(JsonPrimitive("volume"))
                        add(JsonPrimitive("mute"))
                        add(JsonPrimitive("set_output_delay"))
                    }
                }
            }
            if (beats) {
                putJsonObject("visualizer") {
                    putJsonArray("types") { add(JsonPrimitive("beat")) }
                    put("rate_max", 1)
                }
            }
        },
    )

    fun goodbye(reason: String) = Message.of("client/goodbye", buildJsonObject { put("reason", reason) })

    fun pairInit(pairingIndex: Int) = Message.of("client/pair-init", buildJsonObject { put("pairing_index", pairingIndex) })

    fun pairFinalize(longTermPsk: ByteArray) = Message.of("client/pair-finalize", buildJsonObject { put("long_term_psk", B64.encode(longTermPsk)) })

    fun pairAbort(reason: String) = Message.of("pair/abort", buildJsonObject { put("reason", reason) })

    /** [text] cut to at most 64 bytes of UTF-8, never inside a character. */
    internal fun cap(text: String, bytes: Int = 64): String {
        if (text.toByteArray(Charsets.UTF_8).size <= bytes) return text
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + len > bytes) break
            out.appendCodePoint(cp)
            used += len
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}

/** What the player reports about itself in `client/state`. */
data class PlayerState(
    val volume: Int,
    val muted: Boolean,
    val outputDelayMs: Int,
    val requiredLeadTimeMs: Int,
    val minBufferMs: Int,
)

object Roles {
    const val PLAYER = "player@v1"
    const val VISUALIZER = "visualizer@v1"
}
