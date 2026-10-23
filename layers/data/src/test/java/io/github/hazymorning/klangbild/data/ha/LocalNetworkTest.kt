package io.github.hazymorning.klangbild.data.ha

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.net.InetAddress

class LocalNetworkTest {
    @Test
    fun `recognizes local hosts syntactically`() {
        listOf("192.168.0.10", "10.1.2.3", "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.1.1",
            "100.100.1.1", "homeassistant", "homeassistant.local", "ha.lan", "box.home.arpa", "fritz.box",
            "localhost", "fe80::1", "fd12:3456::1", "[fd12::1]").forEach {
            assertWithMessage(it).that(LocalNetwork.looksLocal(it)).isTrue()
        }
        listOf("8.8.8.8", "172.32.0.1", "example.org", "abc.ui.nabu.casa", "2001:db8::1", "").forEach {
            assertWithMessage(it).that(LocalNetwork.looksLocal(it)).isFalse()
        }
    }

    @Test
    fun `a public name that resolves privately counts as local`() {
        val guard = LocalNetwork.CleartextGuard { host ->
            when (host) {
                "ha.myhome.example" -> listOf(InetAddress.getByAddress(host, byteArrayOf(192.toByte(), 168.toByte(), 1, 5)))
                else -> listOf(InetAddress.getByAddress(host, byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
            }
        }
        assertThat(guard.isLocalHost("ha.myhome.example")).isTrue()
        assertThat(guard.isLocalHost("cdn.example.org")).isFalse()
    }
}
