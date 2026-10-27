package io.github.hazymorning.klangbild.sendspin

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

class MessagesTest {
    @Test
    fun `a name is cut to 64 bytes, never inside a character`() {
        assertThat(Outgoing.cap("Wohnzimmer")).isEqualTo("Wohnzimmer")
        // Two bytes each in UTF-8, and four for each note: one more would go over.
        assertThat(Outgoing.cap("ü".repeat(40))).isEqualTo("ü".repeat(32))
        assertThat(Outgoing.cap("a" + "🎵".repeat(20))).isEqualTo("a" + "🎵".repeat(15))
    }
}
