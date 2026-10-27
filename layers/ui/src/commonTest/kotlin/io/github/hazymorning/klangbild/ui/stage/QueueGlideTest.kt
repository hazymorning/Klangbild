package io.github.hazymorning.klangbild.ui.stage

import assertk.assertThat
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test

/** Which rows of the queue's list glide to their new places, and which go and come again. */
class QueueGlideTest {
    @Test
    fun `a new song moves every row that stays, and a song put in moves the rows below it`() {
        assertThat(glidingIds(listOf("b", "c", "d", "e"), listOf("c", "d", "e", "f"))).containsExactlyInAnyOrder("c", "d", "e")
        assertThat(glidingIds(listOf("b", "c", "d"), listOf("x", "b", "c"))).containsExactlyInAnyOrder("b", "c")
    }

    @Test
    fun `rows that changed order never pass through each other`() {
        // Two swapped: one of them glides, the other goes and comes again at its new place.
        val swap = glidingIds(listOf("a", "b", "c", "d"), listOf("b", "a", "c", "d"))
        assertThat(swap).hasSize(3)
        assertThat(swap).containsAtLeast("c", "d")
        // Turned around: only one row can glide.
        assertThat(glidingIds(listOf("a", "b", "c"), listOf("c", "b", "a"))).hasSize(1)
        // Every pair that glides keeps its order.
        val before = listOf("a", "b", "c", "d", "e", "f")
        val now = listOf("e", "a", "f", "b", "c", "d")
        val glide = now.filter { it in glidingIds(before, now) }
        val places = glide.map { before.indexOf(it) }
        assertThat(places).isEqualTo(places.sorted())
        assertThat(glide).containsExactly("a", "b", "c", "d")
    }

    @Test
    fun `a list with nothing in common moves nothing`() {
        assertThat(glidingIds(listOf("a", "b"), listOf("c", "d"))).isEmpty()
        assertThat(glidingIds(emptyList(), listOf("a"))).isEmpty()
    }
}
