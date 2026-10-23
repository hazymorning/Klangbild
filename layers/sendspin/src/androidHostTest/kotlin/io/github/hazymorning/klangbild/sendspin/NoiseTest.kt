package io.github.hazymorning.klangbild.sendspin

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The Noise layer against the published test vector for Noise_KKpsk2_25519_ChaChaPoly_SHA256
 * from the cacophony test suite (https://github.com/haskell-cryptography/cacophony, as shipped
 * in snow's tests/vectors/cacophony.txt): both sides, every message byte for byte.
 */
class NoiseTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private val prologue = hex("4a6f686e2047616c74")
    private val psk = hex("54686973206973206d7920417573747269616e20706572737065637469766521")
    private val initStatic = KeyPair.of(hex("e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1"))
    private val initEphemeral = KeyPair.of(hex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a"))
    private val respStatic = KeyPair.of(hex("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893"))
    private val respEphemeral = KeyPair.of(hex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b"))
    private val handshakeHash = "7f3c5fdcdd3767e2835473a2683971490339f5bbeee82c3690bc606e14db70ed"

    /** Payload and ciphertext of each message: two of the handshake, then transport messages, initiator first. */
    private val messages = listOf(
        "4c756477696720766f6e204d69736573" to
            "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944babf6443250c604872e33233c3b9a29df5c6d334ae2d53f1bd7f0b265a716b37",
        "4d757272617920526f746862617264" to
            "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f14480884366a1f5f0d79fe93ae476bd1897a7a8ae92764898aa5d49e07b5849f35865ba",
        "462e20412e20486179656b" to "2eb2686b8814a7c0178fe18bfeeafe3e07312d69486d45e6572546",
        "4361726c204d656e676572" to "eea5791a890cd573a5c2e2345a8f98b0d1f0727acd24584fcddde5",
        "4a65616e2d426170746973746520536179" to "ab2e1a411abaaa3df9cb497dffe4cfb70af6c71f0815b3c33b35e22329dee72f3e",
        "457567656e2042f6686d20766f6e2042617765726b" to "ed22a0392c6afbfd6a6adea92b1faf13c4df24072f7060a20b1500609621c6957ac86d82f9",
    ).map { (p, c) -> hex(p) to hex(c) }

    private fun initiator() = KkPsk2Handshake(true, prologue, initStatic, respStatic.publicKey) { initEphemeral }
    private fun responder() = KkPsk2Handshake(false, prologue, respStatic, initStatic.publicKey) { respEphemeral }

    @Test
    fun `the responder reads and writes the vector's messages`() {
        val r = responder()
        assertThat(r.readFirst(messages[0].second)).isEqualTo(messages[0].first)
        val (second, channel) = r.writeSecond(psk, messages[1].first)
        assertThat(second).isEqualTo(messages[1].second)
        assertThat(channel.handshakeHash).isEqualTo(hex(handshakeHash))
        // Transport: the initiator speaks on even messages, the responder answers.
        for (i in 2 until messages.size) {
            val (payload, cipher) = messages[i]
            if (i % 2 == 0) {
                assertThat(channel.receive.decrypt(ByteArray(0), cipher)).isEqualTo(payload)
            } else {
                assertThat(channel.send.encrypt(ByteArray(0), payload)).isEqualTo(cipher)
            }
        }
    }

    @Test
    fun `the initiator writes and reads the vector's messages`() {
        val i = initiator()
        assertThat(i.writeFirst(messages[0].first)).isEqualTo(messages[0].second)
        val (payload, channel) = i.readSecond(psk, messages[1].second)
        assertThat(payload).isEqualTo(messages[1].first)
        assertThat(channel.handshakeHash).isEqualTo(hex(handshakeHash))
        for (n in 2 until messages.size) {
            val (p, cipher) = messages[n]
            if (n % 2 == 0) {
                assertThat(channel.send.encrypt(ByteArray(0), p)).isEqualTo(cipher)
            } else {
                assertThat(channel.receive.decrypt(ByteArray(0), cipher)).isEqualTo(p)
            }
        }
    }

    @Test
    fun `another PSK fails the handshake on the initiator's side`() {
        val r = responder()
        r.readFirst(messages[0].second)
        val other = psk.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val (second, _) = r.writeSecond(other, messages[1].first)
        assertFailsWith<NoiseException> { initiator().apply { writeFirst(messages[0].first) }.readSecond(psk, second) }
    }

    @Test
    fun `a changed byte does not open, and neither does a message replayed`() {
        val i = initiator()
        val first = i.writeFirst(ByteArray(0))
        val r = responder()
        r.readFirst(first)
        val (second, rc) = r.writeSecond(psk, "{}".toByteArray())
        val (_, ic) = i.readSecond(psk, second)
        val sealed = ic.send.encrypt(ByteArray(0), byteArrayOf(0, 1, 2, 3))
        val changed = sealed.copyOf().also { it[2] = (it[2].toInt() xor 1).toByte() }
        assertFailsWith<NoiseException> { rc.receive.decrypt(ByteArray(0), changed) }
        assertThat(rc.receive.decrypt(ByteArray(0), sealed)).isEqualTo(byteArrayOf(0, 1, 2, 3))
        assertFailsWith<NoiseException> { rc.receive.decrypt(ByteArray(0), sealed) }
    }

    @Test
    fun `fresh keys agree with each other`() {
        val random = CryptographyRandom.Default
        val server = KeyPair.generate(random)
        val client = KeyPair.generate(random)
        val i = KkPsk2Handshake(true, prologue, server, client.publicKey) { KeyPair.generate(random) }
        val r = KkPsk2Handshake(false, prologue, client, server.publicKey) { KeyPair.generate(random) }
        assertThat(r.readFirst(i.writeFirst("hello".toByteArray()))).isEqualTo("hello".toByteArray())
        val (second, rc) = r.writeSecond(psk, "{}".toByteArray())
        val (payload, ic) = i.readSecond(psk, second)
        assertThat(payload).isEqualTo("{}".toByteArray())
        assertThat(rc.handshakeHash).isEqualTo(ic.handshakeHash)
        assertThat(rc.receive.decrypt(ByteArray(0), ic.send.encrypt(ByteArray(0), byteArrayOf(9)))).isEqualTo(byteArrayOf(9))
    }
}
