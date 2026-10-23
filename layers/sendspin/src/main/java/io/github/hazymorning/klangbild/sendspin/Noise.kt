package io.github.hazymorning.klangbild.sendspin

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/** A Noise message that cannot be read: a wrong key, a changed byte, or one that does not belong here. */
class NoiseException(message: String) : Exception(message)

/** A Curve25519 key pair: the private key's 32 bytes and the public key they give. */
class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray) {
    init {
        require(privateKey.size == X25519.SCALAR_SIZE && publicKey.size == X25519.POINT_SIZE)
    }

    companion object {
        /** A new key pair from [random] (a CSPRNG). */
        fun generate(random: SecureRandom): KeyPair {
            val private = ByteArray(X25519.SCALAR_SIZE)
            X25519.generatePrivateKey(random, private)
            return of(private)
        }

        /** The key pair of [privateKey]. */
        fun of(privateKey: ByteArray): KeyPair {
            val public = ByteArray(X25519.POINT_SIZE)
            X25519.scalarMultBase(privateKey, 0, public, 0)
            return KeyPair(privateKey.copyOf(), public)
        }
    }
}

/**
 * The Noise Protocol Framework (revision 34, https://noiseprotocol.org/noise.html) as Sendspin
 * uses it: the KKpsk2 pattern over Curve25519, ChaCha20-Poly1305 and SHA-256. The state machine
 * is written out here; the primitives under it are BouncyCastle's (X25519, ChaCha20Poly1305,
 * SHA-256, HMAC), used through its lightweight API without registering a provider.
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

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = SHA256Digest()
        for (p in parts) d.update(p, 0, p.size)
        return ByteArray(HASH_BYTES).also { d.doFinal(it, 0) }
    }

    internal fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        for (p in parts) mac.update(p, 0, p.size)
        return ByteArray(HASH_BYTES).also { mac.doFinal(it, 0) }
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
        if (public.size != X25519.POINT_SIZE) throw NoiseException("public key of ${public.size} bytes")
        val out = ByteArray(X25519.POINT_SIZE)
        if (!X25519.calculateAgreement(pair.privateKey, 0, public, 0, out, 0)) throw NoiseException("low-order public key")
        return out
    }
}

/**
 * One direction of a Noise channel: a key and the count of messages it has sealed or opened.
 * Each message is ChaCha20-Poly1305 with the count as its nonce (32 zero bits, then 64 bits
 * little-endian), so a message replayed, dropped or put out of order fails to open.
 */
class CipherState internal constructor(key: ByteArray? = null) {
    private var key: KeyParameter? = key?.let { KeyParameter(it) }
    private var nonce = 0L
    private val aead = ChaCha20Poly1305()
    private val nonceBytes = ByteArray(12)

    val hasKey: Boolean get() = key != null

    internal fun initializeKey(k: ByteArray) {
        key = KeyParameter(k)
        nonce = 0
    }

    /** [plaintext] sealed with [ad] as associated data (as it is, while there is no key). */
    fun encrypt(ad: ByteArray, plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size - offset): ByteArray {
        val k = key ?: return plaintext.copyOfRange(offset, offset + length)
        val out = ByteArray(length + Noise.TAG_BYTES)
        val written = seal(k, true, ad, plaintext, offset, length, out)
        check(written == out.size)
        return out
    }

    /** [ciphertext] opened with [ad] as associated data; throws [NoiseException] when it does not open. */
    fun decrypt(ad: ByteArray, ciphertext: ByteArray, offset: Int = 0, length: Int = ciphertext.size - offset): ByteArray {
        val k = key ?: return ciphertext.copyOfRange(offset, offset + length)
        if (length < Noise.TAG_BYTES) throw NoiseException("message shorter than its tag")
        val out = ByteArray(length - Noise.TAG_BYTES)
        val written = seal(k, false, ad, ciphertext, offset, length, out)
        check(written == out.size)
        return out
    }

    private fun seal(k: KeyParameter, encrypt: Boolean, ad: ByteArray, input: ByteArray, offset: Int, length: Int, out: ByteArray): Int {
        // The last nonce (2^64 - 1) is reserved; no connection lives long enough to reach it.
        if (nonce == -1L) throw NoiseException("nonces used up")
        var n = nonce
        for (i in 4 until 12) {
            nonceBytes[i] = n.toByte()
            n = n ushr 8
        }
        aead.init(encrypt, AEADParameters(k, Noise.TAG_BYTES * 8, nonceBytes, ad))
        return try {
            val len = aead.processBytes(input, offset, length, out, 0)
            val total = len + aead.doFinal(out, len)
            nonce++
            total
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw NoiseException("message does not open")
        }
    }
}

/** Noise's symmetric state during a handshake: the chaining key, the hash of everything so far, and the cipher. */
internal class SymmetricState(protocolName: String) {
    var ck: ByteArray
        private set
    var h: ByteArray
        private set
    val cipher = CipherState()

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
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
