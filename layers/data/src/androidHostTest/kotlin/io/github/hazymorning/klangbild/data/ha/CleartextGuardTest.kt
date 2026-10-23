package io.github.hazymorning.klangbild.data.ha

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.net.InetAddress
import kotlin.test.Test

class CleartextGuardTest {
    @Test
    fun `a public name that resolves privately counts as local`() {
        val guard = CleartextGuard { host ->
            when (host) {
                "ha.myhome.example" -> listOf(InetAddress.getByAddress(host, byteArrayOf(192.toByte(), 168.toByte(), 1, 5)))
                else -> listOf(InetAddress.getByAddress(host, byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
            }
        }
        assertThat(guard.isLocalHost("ha.myhome.example")).isTrue()
        assertThat(guard.isLocalHost("cdn.example.org")).isFalse()
    }
}
