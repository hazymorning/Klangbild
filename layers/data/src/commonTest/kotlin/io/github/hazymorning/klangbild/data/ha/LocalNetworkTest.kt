package io.github.hazymorning.klangbild.data.ha

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class LocalNetworkTest {
    @Test
    fun `recognizes local hosts syntactically`() {
        listOf("192.168.0.10", "10.1.2.3", "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.1.1",
            "100.100.1.1", "homeassistant", "homeassistant.local", "ha.lan", "box.home.arpa", "ha.fritz.box",
            "localhost", "::1", "fe80::1", "febf::1", "fc00::1", "fd12:3456::1", "[fd12::1]").forEach {
            assertThat(LocalNetwork.looksLocal(it), it).isTrue()
        }
        // fc::1 is 00fc::1 and fd1:: is 0fd1::, both public. A leading zero may mean octal to a resolver.
        listOf("8.8.8.8", "172.32.0.1", "010.1.2.3", "example.org", "example.box", "abc.ui.nabu.casa", "2001:db8::1", "fc::1", "fd1::", "::", "")
            .forEach {
                assertThat(LocalNetwork.looksLocal(it), it).isFalse()
            }
    }
}
