package io.github.hazymorning.klangbild.sendspin

/** The initiator's side of a KKpsk2 handshake ([KkPsk2Responder]), for the tests that play the server. */
class KkPsk2Initiator(
    prologue: ByteArray,
    private val static: KeyPair,
    private val remoteStatic: ByteArray,
    private val ephemeral: () -> KeyPair,
) {
    private val ss = kkStart(prologue, static.publicKey, remoteStatic)
    private var e: KeyPair? = null

    /** The first message, carrying [payload]. */
    fun writeFirst(payload: ByteArray): ByteArray {
        check(e == null)
        val eph = ephemeral().also { e = it }
        ss.mixHash(eph.publicKey)
        ss.mixKey(eph.publicKey)
        ss.mixKey(Noise.dh(eph, remoteStatic))
        ss.mixKey(Noise.dh(static, remoteStatic))
        return eph.publicKey + ss.encryptAndHash(payload)
    }

    /** The answer's payload, read with [psk], and the channel it opens. */
    fun readSecond(psk: ByteArray, message: ByteArray): Pair<ByteArray, NoiseChannel> {
        val eph = checkNotNull(e)
        if (message.size < Noise.KEY_BYTES + Noise.TAG_BYTES) throw NoiseException("second message of ${message.size} bytes")
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
