package io.github.hazymorning.klangbild.ui.stage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which rows of the queue's list glide to their new places, and which go and come again. */
class QueueGlideTest {
    @Test
    fun `a new song moves every row that stays, and a song put in moves the rows below it`() {
        assertThat(gliding(listOf("b", "c", "d", "e"), listOf("c", "d", "e", "f"))).containsExactly("c", "d", "e")
        assertThat(gliding(listOf("b", "c", "d"), listOf("x", "b", "c"))).containsExactly("b", "c")
    }

    @Test
    fun `rows that changed order never pass through each other`() {
        // Two swapped: one of them glides, the other goes and comes again at its new place.
        val swap = gliding(listOf("a", "b", "c", "d"), listOf("b", "a", "c", "d"))
        assertThat(swap).hasSize(3)
        assertThat(swap).containsAtLeast("c", "d")
        // Turned around: only one row can glide.
        assertThat(gliding(listOf("a", "b", "c"), listOf("c", "b", "a"))).hasSize(1)
        // Every pair that glides keeps its order.
        val before = listOf("a", "b", "c", "d", "e", "f")
        val now = listOf("e", "a", "f", "b", "c", "d")
        val glide = now.filter { it in gliding(before, now) }
        assertThat(glide.map { before.indexOf(it) }).isInOrder()
        assertThat(glide).containsExactly("a", "b", "c", "d").inOrder()
    }

    @Test
    fun `a list with nothing in common moves nothing`() {
        assertThat(gliding(listOf("a", "b"), listOf("c", "d"))).isEmpty()
        assertThat(gliding(emptyList(), listOf("a"))).isEmpty()
    }
}
