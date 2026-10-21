package io.github.hazymorning.klangbild.sendspin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The constants and the reference vector of connection.md and pairing.md. */
class KeysTest {
    @Test
    fun `the Sentinel PSK and its id are the published ones`() {
        assertThat(Psk.SENTINEL.joinToString("") { "%02x".format(it) })
            .isEqualTo("1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3")
        assertThat(Psk.id(Psk.SENTINEL)).isEqualTo("GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo")
    }

    @Test
    fun `the pairing token matches the reference vector`() {
        val key = ByteArray(32) { it.toByte() }
        val psk = ByteArray(32) { (0xe0 + it).toByte() }
        assertThat(PairingToken.of(key, psk))
            .isEqualTo("SP:0AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYP6BYPC4PSOLZXH5DU6V97M5XXO74HR6LZ7J5PW674PT6X37T6757Y")
    }

    @Test
    fun `a key reads only as its 43 characters`() {
        val id = B64.encode(ByteArray(32) { 7 })
        assertThat(id).hasLength(43)
        assertThat(B64.key(id)).isEqualTo(ByteArray(32) { 7 })
        assertThat(B64.key(id + "A")).isNull()
        assertThat(B64.key("!".repeat(43))).isNull()
        assertThat(B64.key(null)).isNull()
    }
}
