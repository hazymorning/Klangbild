package io.github.hazymorning.klangbild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpNextTest {
    private fun song(title: String, ms: Long? = 240_000L, id: String? = "q-$title") =
        TrackInfo(title, listOf("Amal Haddad"), album = "Cairo Nights", durationMs = ms, queueItemId = id)

    @Test
    fun `how many follow and how long they play, only as far as known`() {
        val coming = listOf(song("A", ms = 200_000L), song("B", ms = 100_000L))
        assertThat(UpNext.of(coming, remaining = 2)!!.count).isEqualTo(2)
        assertThat(UpNext.of(coming, remaining = 2)!!.durationMs).isEqualTo(300_000L)
        // More follows than was read: the count, but no length.
        assertThat(UpNext.of(coming, remaining = 57)!!.count).isEqualTo(57)
        assertThat(UpNext.of(coming, remaining = 57)!!.durationMs).isNull()
        // A song without a length: none for all of them.
        assertThat(UpNext.of(coming + song("C", ms = null), remaining = 3)!!.durationMs).isNull()
        // Without a total, the count is the songs read, or unknown when the read hit its limit.
        assertThat(UpNext.of(coming, remaining = null)!!.count).isEqualTo(2)
        val many = (1..40).map { song("S$it") }
        assertThat(UpNext.of(many, remaining = null)!!.count).isNull()
        // A count behind what was read is not believed.
        assertThat(UpNext.of(coming, remaining = 1)!!.count).isEqualTo(2)
    }

    @Test
    fun `nothing to come is no list, and a long queue keeps only the rows it can show`() {
        assertThat(UpNext.of(emptyList(), remaining = 0)).isNull()
        val many = (1..30).map { song("S$it") }
        assertThat(UpNext.of(many, remaining = 30)!!.rows).hasSize(UpNext.ROWS)
    }

    @Test
    fun `a row keeps its identity as it moves up, the same song twice is two rows`() {
        val first = UpNext.of(listOf(song("A"), song("B"), song("A", id = "q-A2")), remaining = 3)!!
        assertThat(first.rows.map { it.id }).containsExactly("q-A", "q-B", "q-A2").inOrder()
        assertThat(UpNext.of(listOf(song("B"), song("A", id = "q-A2")), remaining = 2)!!.rows.first().id).isEqualTo(first.rows[1].id)
        // Without Music Assistant's ids: the song and how often it came before.
        val bare = UpNext.of(listOf(song("A", id = null), song("B", id = null), song("A", id = null)), remaining = 3)!!
        assertThat(bare.rows.map { it.id }.toSet()).hasSize(3)
    }
}
