package io.github.hazymorning.klangbild.sendspin

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import kotlin.random.Random

/** A Noise message that cannot be read: a wrong key, a changed byte, or one that does not belong here. */
class NoiseException(message: String) : Exception(message)

/** A Curve25519 key pair: the private key's 32 bytes and the public key they give. */
class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    init {
        require(privateKey.size == Noise.KEY_BYTES && publicKey.size == Noise.KEY_BYTES)
    }

    companion object {
        /** A new key pair from [random] (a CSPRNG), its private key clamped as RFC 7748 has it. */
        fun generate(random: Random): KeyPair {
            val private = random.nextBytes(Noise.KEY_BYTES)
            private[0] = (private[0].toInt() and 0xF8).toByte()
            private[31] = (private[31].toInt() and 0x7F or 0x40).toByte()
            return of(private)
        }

        /** The key pair of [privateKey]: its public key is the private key times the curve's base point. */
        fun of(privateKey: ByteArray): KeyPair = KeyPair(privateKey.copyOf(), Noise.x25519(privateKey, BASE_POINT))

        /** The base point of Curve25519: u = 9. */
        private val BASE_POINT = ByteArray(Noise.KEY_BYTES).also { it[0] = 9 }
    }
}

/**
 * The Noise Protocol Framework (revision 34, https://noiseprotocol.org/noise.html) as Sendspin
 * uses it: the KKpsk2 pattern over Curve25519, ChaCha20-Poly1305 and SHA-256. The state machine
 * is written out here; the primitives under it are cryptography-kotlin's (X25519,
 * ChaCha20-Poly1305, SHA-256, HMAC), on Android and the JVM by way of BouncyCastle. They are
 * used as the session is, one call after the other; a browser's WebCrypto has no
 * ChaCha20-Poly1305 and answers only asynchronously, so there the session can't open.
 */
object Noise {
    /** The suite Klangbild picks in `client/init`: the one that needs no AES hardware. */
    const val SUITE = "25519_ChaChaPoly_SHA256"
    const val PROTOCOL_NAME = "Noise_KKpsk2_$SUITE"

    /** A Noise message is at most this long, the 16 bytes of its tag included. */
    const val MAX_MESSAGE = 65_535
    const val TAG_BYTES = 16
    const val KEY_BYTES = 32
    const val HASH_BYTES = 32

    private val provider = CryptographyProvider.Default
    private val curve = provider.get(XDH)
    private val hash = provider.get(SHA256).hasher()
    private val macKeys = provider.get(HMAC).keyDecoder(SHA256)
    internal val cipherKeys = provider.get(ChaCha20Poly1305).keyDecoder()

    fun sha256(vararg parts: ByteArray): ByteArray = hash.createHashFunction().use { d ->
        for (p in parts) d.update(p)
        d.hashToByteArray()
    }

    internal fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        macKeys.decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key).signatureGenerator().createSignFunction().use { mac ->
            for (p in parts) mac.update(p)
            mac.signToByteArray()
        }

    /** Noise's HKDF: two or three outputs of 32 bytes from [chainingKey] and [input]. */
    internal fun hkdf(chainingKey: ByteArray, input: ByteArray, outputs: Int): List<ByteArray> {
        val temp = hmac(chainingKey, input)
        val one = hmac(temp, byteArrayOf(1))
        val two = hmac(temp, one, byteArrayOf(2))
        if (outputs == 2) return listOf(one, two)
        return listOf(one, two, hmac(temp, two, byteArrayOf(3)))
    }

    /** Diffie-Hellman between [pair]'s private key and [public]; an all-zero result (a low-order point) is refused. */
    internal fun dh(pair: KeyPair, public: ByteArray): ByteArray {
        if (public.size != KEY_BYTES) throw NoiseException("public key of ${public.size} bytes")
        return x25519(pair.privateKey, public)
    }

    /** RFC 7748's X25519 of [scalar] and the point [u]; throws [NoiseException] for a point of low order. */
    internal fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        val shared = try {
            val private = curve.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArrayBlocking(XDH.PrivateKey.Format.RAW, scalar)
            val public = curve.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArrayBlocking(XDH.PublicKey.Format.RAW, u)
            private.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(public)
        } catch (_: IllegalStateException) {
            // BouncyCastle refuses an agreement on a point of low order.
            throw NoiseException("low-order public key")
        }
        if (shared.all { it == 0.toByte() }) throw NoiseException("low-order public key")
        return shared
    }
}

/**
 * One direction of a Noise channel: a key and the count of messages it has sealed or opened.
 * Each message is ChaCha20-Poly1305 with the count as its nonce (32 zero bits, then 64 bits
 * little-endian), so a message replayed, dropped or put out of order fails to open.
 */
class CipherState internal constructor(key: ByteArray? = null) {
    private var key: ChaCha20Poly1305.Key? = key?.let(::decode)
    private var nonce = 0L
    private val nonceBytes = ByteArray(12)

    val hasKey: Boolean get() = key != null

    internal fun initializeKey(k: ByteArray) {
        key = decode(k)
        nonce = 0
    }

    /** [plaintext] sealed with [ad] as associated data (as it is, while there is no key). */
    fun encrypt(ad: ByteArray, plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size - offset): ByteArray {
        val k = key ?: return plaintext.copyOfRange(offset, offset + length)
        val out = seal(k, true, ad, plaintext.part(offset, length))
        check(out.size == length + Noise.TAG_BYTES)
        return out
    }

    /** [ciphertext] opened with [ad] as associated data; throws [NoiseException] when it does not open. */
    fun decrypt(ad: ByteArray, ciphertext: ByteArray, offset: Int = 0, length: Int = ciphertext.size - offset): ByteArray {
        val k = key ?: return ciphertext.copyOfRange(offset, offset + length)
        if (length < Noise.TAG_BYTES) throw NoiseException("message shorter than its tag")
        val out = seal(k, false, ad, ciphertext.part(offset, length))
        check(out.size == length - Noise.TAG_BYTES)
        return out
    }

    @OptIn(DelicateCryptographyApi::class)
    private fun seal(k: ChaCha20Poly1305.Key, encrypt: Boolean, ad: ByteArray, input: ByteArray): ByteArray {
        // The last nonce (2^64 - 1) is reserved; no connection lives long enough to reach it.
        if (nonce == -1L) throw NoiseException("nonces used up")
        var n = nonce
        for (i in 4 until 12) {
            nonceBytes[i] = n.toByte()
            n = n ushr 8
        }
        val cipher = k.cipher()
        val out = if (encrypt) {
            cipher.encryptWithIvBlocking(nonceBytes, input, ad)
        } else {
            try {
                cipher.decryptWithIvBlocking(nonceBytes, input, ad)
            } catch (_: Exception) {
                // The tag doesn't match: the platform's own exception, whatever it is called.
                throw NoiseException("message does not open")
            }
        }
        nonce++
        return out
    }

    private fun decode(k: ByteArray): ChaCha20Poly1305.Key = Noise.cipherKeys.decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, k)

    /** The bytes from [offset], [length] of them: the array itself when that is all of it. */
    private fun ByteArray.part(offset: Int, length: Int): ByteArray = if (offset == 0 && length == size) this else copyOfRange(offset, offset + length)
}

/** Noise's symmetric state during a handshake: the chaining key, the hash of everything so far, and the cipher. */
internal class SymmetricState(protocolName: String) {
    var ck: ByteArray
        private set
    var h: ByteArray
        private set
    val cipher = CipherState()

    init {
        val name = protocolName.encodeToByteArray()
        h = if (name.size <= Noise.HASH_BYTES) name.copyOf(Noise.HASH_BYTES) else Noise.sha256(name)
        ck = h.copyOf()
    }

    fun mixKey(input: ByteArray) {
        val (ck2, k) = Noise.hkdf(ck, input, 2)
        ck = ck2
        cipher.initializeKey(k)
    }

    fun mixHash(data: ByteArray) {
        h = Noise.sha256(h, data)
    }

    fun mixKeyAndHash(input: ByteArray) {
        val (ck2, temp, k) = Noise.hkdf(ck, input, 3)
        ck = ck2
        mixHash(temp)
        cipher.initializeKey(k)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray = cipher.encrypt(h, plaintext).also { mixHash(it) }

    fun decryptAndHash(ciphertext: ByteArray, offset: Int): ByteArray =
        cipher.decrypt(h, ciphertext, offset).also { mixHash(ciphertext.copyOfRange(offset, ciphertext.size)) }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = Noise.hkdf(ck, ByteArray(0), 2)
        return CipherState(k1) to CipherState(k2)
    }
}

/** The two directions of a channel once its handshake is done, and the handshake's hash. */
class NoiseChannel(val send: CipherState, val receive: CipherState, val handshakeHash: ByteArray)

/**
 * A KKpsk2 handshake: both static public keys are known beforehand, the initiator's first
 * message carries `e, es, ss`, the responder's answer `e, ee, se, psk`. The PSK is needed only
 * for the second message, so the responder reads the first message's payload (which names the
 * PSK) before choosing it.
 *
 * In Sendspin the server is always the initiator and Klangbild the responder; the initiator's
 * side is here for the tests, which play the server.
 */
class KkPsk2Handshake(
    private val initiator: Boolean,
    prologue: ByteArray,
    private val static: KeyPair,
    private val remoteStatic: ByteArray,
    private val ephemeral: () -> KeyPair,
) {
    private val ss = SymmetricState(Noise.PROTOCOL_NAME)
    private var e: KeyPair? = null
    private var re: ByteArray? = null
    private var step = 0

    init {
        require(remoteStatic.size == Noise.KEY_BYTES)
        ss.mixHash(prologue)
        // The pre-messages: the initiator's static key, then the responder's.
        ss.mixHash(if (initiator) static.publicKey else remoteStatic)
        ss.mixHash(if (initiator) remoteStatic else static.publicKey)
    }

    /** The handshake's hash so far (after the second message, the channel's `h`). */
    val handshakeHash: ByteArray get() = ss.h.copyOf()

    /** Initiator: the first message, carrying [payload]. */
    fun writeFirst(payload: ByteArray): ByteArray {
        check(initiator && step == 0)
        step = 1
        val eph = ephemeral().also { e = it }
        ss.mixHash(eph.publicKey)
        ss.mixKey(eph.publicKey)
        ss.mixKey(Noise.dh(eph, remoteStatic))
        ss.mixKey(Noise.dh(static, remoteStatic))
        return eph.publicKey + ss.encryptAndHash(payload)
    }

    /** Responder: the first message's payload. */
    fun readFirst(message: ByteArray): ByteArray {
        check(!initiator && step == 0)
        step = 1
        if (message.size < Noise.KEY_BYTES + Noise.TAG_BYTES || message.size > Noise.MAX_MESSAGE) throw NoiseException("first message of ${message.size} bytes")
        val remote = message.copyOfRange(0, Noise.KEY_BYTES).also { re = it }
        ss.mixHash(remote)
        ss.mixKey(remote)
        ss.mixKey(Noise.dh(static, remote))
        ss.mixKey(Noise.dh(static, remoteStatic))
        return ss.decryptAndHash(message, Noise.KEY_BYTES)
    }

    /** Responder: the answer with [psk] mixed in, carrying [payload], and the channel it opens. */
    fun writeSecond(psk: ByteArray, payload: ByteArray): Pair<ByteArray, NoiseChannel> {
        check(!initiator && step == 1)
        step = 2
        require(psk.size == Noise.KEY_BYTES)
        val remote = checkNotNull(re)
        val eph = ephemeral().also { e = it }
        ss.mixHash(eph.publicKey)
        ss.mixKey(eph.publicKey)
        ss.mixKey(Noise.dh(eph, remote))
        ss.mixKey(Noise.dh(eph, remoteStatic))
        ss.mixKeyAndHash(psk)
        val message = eph.publicKey + ss.encryptAndHash(payload)
        val (c1, c2) = ss.split()
        return message to NoiseChannel(send = c2, receive = c1, handshakeHash = ss.h.copyOf())
    }

    /** Initiator: the answer's payload, read with [psk], and the channel it opens. */
    fun readSecond(psk: ByteArray, message: ByteArray): Pair<ByteArray, NoiseChannel> {
        check(initiator && step == 1)
        step = 2
        if (message.size < Noise.KEY_BYTES + Noise.TAG_BYTES || message.size > Noise.MAX_MESSAGE) throw NoiseException("second message of ${message.size} bytes")
        val eph = checkNotNull(e)
        val remote = message.copyOfRange(0, Noise.KEY_BYTES)
        ss.mixHash(remote)
        ss.mixKey(remote)
        ss.mixKey(Noise.dh(eph, remote))
        ss.mixKey(Noise.dh(static, remote))
        ss.mixKeyAndHash(psk)
        val payload = ss.decryptAndHash(message, Noise.KEY_BYTES)
        val (c1, c2) = ss.split()
        return payload to NoiseChannel(send = c1, receive = c2, handshakeHash = ss.h.copyOf())
    }
}
