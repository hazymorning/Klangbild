package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

class UpNextTest {
    private fun song(title: String, id: String? = "q-$title") =
        TrackInfo(title, listOf("Amal Haddad"), album = "Cairo Nights", durationMs = 240_000L, queueItemId = id)

    @Test
    fun `nothing to come is no list, and a long queue keeps only the rows it can show`() {
        assertThat(UpNext.of(emptyList())).isNull()
        val many = (1..30).map { song("S$it") }
        assertThat(UpNext.of(many)!!.rows).hasSize(UpNext.ROWS)
    }

    @Test
    fun `a row keeps its identity as it moves up, the same song twice is two rows`() {
        val first = UpNext.of(listOf(song("A"), song("B"), song("A", id = "q-A2")))!!
        assertThat(first.rows.map { it.id }).containsExactly("q-A", "q-B", "q-A2")
        assertThat(UpNext.of(listOf(song("B"), song("A", id = "q-A2")))!!.rows.first().id).isEqualTo(first.rows[1].id)
        // Without Music Assistant's ids: the song and how often it came before.
        val bare = UpNext.of(listOf(song("A", id = null), song("B", id = null), song("A", id = null)))!!
        assertThat(bare.rows.map { it.id }.toSet()).hasSize(3)
    }
}
