package io.github.hazymorning.klangbild.sendspin

import kotlin.io.encoding.Base64

/** Sendspin's base64url without padding: client and server ids, PSK ids, Noise messages. */
object B64 {
    private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    fun encode(bytes: ByteArray): String = encoder.encode(bytes)

    /** The bytes of [text]; null when it is not base64url (padding is tolerated). */
    fun decode(text: String): ByteArray? = try {
        decoder.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** A 32-byte key as its 43 characters, the form of `client_id` and `server_id`. */
    fun key(text: String?): ByteArray? = text?.takeIf { it.length == 43 }?.let(::decode)?.takeIf { it.size == Noise.KEY_BYTES }
}

/** What kind of PSK the server mixes into a handshake (`psk_category`). */
enum class PskCategory(val code: String) {
    LONG_TERM("lt"),
    PAIRING("pr"),
    SENTINEL("sn"),
    ;

    companion object {
        fun of(code: String?): PskCategory? = entries.firstOrNull { it.code == code }
    }
}

/** Pre-shared keys as connection.md defines them. */
object Psk {
    /**
     * The published PSK for a server with no other PSK to use: it authenticates nothing, it only
     * lets the handshake complete (`SHA-256("sendspin-sentinel-psk-v1")`).
     */
    val SENTINEL: ByteArray = Noise.sha256("sendspin-sentinel-psk-v1".encodeToByteArray())

    /** The id the server names a PSK by in its first handshake message. */
    fun id(psk: ByteArray): String = B64.encode(Noise.sha256("sendspin-psk-id-v1".encodeToByteArray(), psk))
}

/**
 * The pairing token (pairing.md, version 0): this speaker's public key and its pairing PSK, for
 * a person to enter into Music Assistant when it asks for pairing. `SP:0`, then the 64 bytes in
 * base32 without padding, every 2 written as 9.
 */
object PairingToken {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun of(clientKey: ByteArray, pairingPsk: ByteArray): String {
        require(clientKey.size == Noise.KEY_BYTES && pairingPsk.size == Noise.KEY_BYTES)
        return "SP:0" + base32(clientKey + pairingPsk).replace('2', '9')
    }

    private fun base32(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                append(ALPHABET[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
    }
}
