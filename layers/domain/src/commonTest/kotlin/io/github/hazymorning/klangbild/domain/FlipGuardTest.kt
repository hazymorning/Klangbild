package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test

class FlipGuardTest {
    @Test
    fun `a skip reported forth, back and forth shows only the new item`() {
        val g = FlipGuard()
        assertThat(g.admit("a", 0)).isTrue()
        assertThat(g.admit("b", 10_000)).isTrue()
        // The player catches up with its queue: the old item for a moment …
        assertThat(g.admit("a", 10_300)).isFalse()
        assertThat(g.passesAt).isEqualTo(10_300 + FlipGuard.HOLD_MS)
        // … then the new one for good: nothing was shown in between.
        assertThat(g.admit("b", 11_200)).isTrue()
        assertThat(g.passesAt).isNull()
    }

    @Test
    fun `a return that lasts is shown a moment later`() {
        val g = FlipGuard()
        g.admit("a", 0)
        g.admit("b", 10_000)
        assertThat(g.admit("a", 11_000)).isFalse()
        assertThat(g.admit("a", 11_000 + FlipGuard.HOLD_MS - 1)).isFalse()
        assertThat(g.admit("a", 11_000 + FlipGuard.HOLD_MS)).isTrue()
        // From then on it is what plays.
        assertThat(g.admit("a", 14_000)).isTrue()
    }

    @Test
    fun `a return long after leaving passes at once`() {
        val g = FlipGuard()
        g.admit("a", 0)
        g.admit("b", 10_000)
        assertThat(g.admit("a", 10_000 + FlipGuard.WINDOW_MS)).isTrue()
    }

    @Test
    fun `previous from the remote is wanted, not a flicker`() {
        val g = FlipGuard()
        g.admit("a", 0)
        g.admit("b", 10_000)
        g.expectReturn(10_500)
        assertThat(g.admit("a", 10_800)).isTrue()
    }

    @Test
    fun `new items always pass, and a stale one two skips back is held too`() {
        val g = FlipGuard()
        for ((i, key) in listOf("a", "b", "c", "d").withIndex()) assertThat(g.admit(key, i * 400L)).isTrue()
        assertThat(g.admit("b", 1_500)).isFalse()
        assertThat(g.admit("d", 1_700)).isTrue()
    }

    @Test
    fun `nothing playing passes and changes nothing`() {
        val g = FlipGuard()
        g.admit("a", 0)
        assertThat(g.admit(null, 1_000)).isTrue()
        assertThat(g.admit("a", 1_200)).isTrue()
    }
}
