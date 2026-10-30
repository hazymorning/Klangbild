package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

/** When no one counts as being in the room, and what brings someone back. */
class PresenceTest {
    @Test
    fun `no one is there once every sensor has been off for a while`() {
        val p = Presence(delayMs = 60_000)
        assertThat(p.nobodyThere(listOf("on", "off"), 0)).isFalse()
        assertThat(p.nobodyThere(listOf("off", "off"), 1_000)).isFalse()
        assertThat(p.nobodyThere(listOf("off", "off"), 60_999)).isFalse()
        assertThat(p.nobodyThere(listOf("off", "off"), 61_000)).isTrue()
        assertThat(p.nobodySince).isEqualTo(61_000)
        // Someone comes in: at once.
        assertThat(p.nobodyThere(listOf("off", "on"), 70_000)).isFalse()
        assertThat(p.nobodySince).isNull()
    }

    @Test
    fun `a key counts as someone there`() {
        val p = Presence(delayMs = 60_000)
        p.nobodyThere(listOf("off"), 0)
        assertThat(p.nobodyThere(listOf("off"), 60_000)).isTrue()
        p.keyPressed(70_000)
        assertThat(p.nobodyThere(listOf("off"), 70_000)).isFalse()
        assertThat(p.nobodyThere(listOf("off"), 129_999)).isFalse()
        assertThat(p.nobodyThere(listOf("off"), 130_000)).isTrue()
    }

    @Test
    fun `a sensor that can't tell, or none at all, keeps the screen on`() {
        val p = Presence(delayMs = 0)
        assertThat(p.nobodyThere(listOf("off", "unavailable"), 0)).isFalse()
        assertThat(p.nobodyThere(listOf("off", null), 10)).isFalse()
        assertThat(p.nobodyThere(emptyList(), 20)).isFalse()
        assertThat(p.nobodyThere(listOf("off"), 30)).isTrue()
    }
}
