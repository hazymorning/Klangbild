package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import kotlin.test.Test

class CoverStripTest {
    private fun song(title: String, album: String) = TrackInfo(title, listOf("A"), album = album)

    private val a1 = song("a1", "Alpha")
    private val a2 = song("a2", "Alpha")
    private val b1 = song("b1", "Beta")
    private val c1 = song("c1", "Gamma")
    private val d1 = song("d1", "Delta")

    private fun Strip.albums() = cards.map { it.track.album }

    @Test
    fun `the songs of one album are one card and keep it`() {
        val strip = CoverStrip()
        val first = strip.update(a1, coming = listOf(a2))
        assertThat(first.albums()).containsExactlyInAnyOrder("Alpha")
        val second = strip.update(a2, coming = listOf(b1))
        assertThat(second.middle.id).isEqualTo(first.middle.id)
        assertThat(second.middle.track).isEqualTo(a2)
        assertThat(second.albums()).containsExactlyInAnyOrder("Alpha", "Beta")
    }

    @Test
    fun `the next picture arriving is the card that stood to the right`() {
        val strip = CoverStrip()
        val before = strip.update(a1, coming = listOf(b1))
        val nextId = before.cards[before.current + 1].id
        val after = strip.update(b1, coming = listOf(c1))
        assertThat(after.middle.id).isEqualTo(nextId)
        // what played stands to the left now, with its old id
        assertThat(after.cards[after.current - 1].id).isEqualTo(before.middle.id)
        assertThat(after.albums()).containsExactlyInAnyOrder("Alpha", "Beta", "Gamma")
    }

    @Test
    fun `a step back brings back the card from the left`() {
        val strip = CoverStrip()
        strip.update(a1, coming = listOf(b1))
        val forward = strip.update(b1, coming = listOf(c1))
        val back = strip.update(a1, coming = listOf(b1))
        assertThat(back.middle.id).isEqualTo(forward.cards[forward.current - 1].id)
        // the card that was in the middle steps to the right again, still the same card
        assertThat(back.cards[back.current + 1].id).isEqualTo(forward.middle.id)
    }

    @Test
    fun `a jump brings a new card and keeps what played`() {
        val strip = CoverStrip()
        val first = strip.update(a1, coming = listOf(b1))
        val jump = strip.update(d1)
        assertThat(jump.middle.id).isNotEqualTo(first.cards.last().id)
        assertThat(jump.albums()).containsExactlyInAnyOrder("Alpha", "Delta")
    }

    @Test
    fun `at most two cards on each side, the queue's played part replaces the memory`() {
        val strip = CoverStrip()
        for (t in listOf(a1, b1, c1, d1)) strip.update(t)
        val s = strip.update(song("e1", "Epsilon"), coming = listOf(a1, b1, c1))
        assertThat(s.current).isEqualTo(2)
        assertThat(s.cards).hasSize(5)
        val fromQueue = strip.update(song("e1", "Epsilon"), before = listOf(c1, b1))
        assertThat(fromQueue.albums().take(fromQueue.current)).containsExactlyInAnyOrder("Gamma", "Beta")
    }

    @Test
    fun `while the queue is not known yet, the cards behind the next one stay, but after a jump none do`() {
        val strip = CoverStrip()
        val first = strip.update(a1, coming = listOf(b1, c1))
        val forward = strip.update(b1)
        assertThat(forward.albums()).containsExactlyInAnyOrder("Alpha", "Beta", "Gamma")
        assertThat(forward.cards.last().id).isEqualTo(first.cards.last().id)
        val back = strip.update(a1)
        assertThat(back.albums()).containsExactlyInAnyOrder("Alpha", "Beta", "Gamma")
        val jump = strip.update(d1)
        assertThat(jump.albums()).containsExactlyInAnyOrder("Alpha", "Delta")
    }

    @Test
    fun `a radio station is one card while its songs change`() {
        val strip = CoverStrip()
        val s1 = strip.update(TrackInfo("Song 1", listOf("X"), artworkUrl = "http://ha/logo.png", source = "Radio"))
        val s2 = strip.update(TrackInfo("Song 2", listOf("Y"), artworkUrl = "http://ha/logo.png", source = "Radio"))
        assertThat(s2.middle.id).isEqualTo(s1.middle.id)
    }

    @Test
    fun `a song keeps its card when its own picture takes the station's place, and the next song gets a new one`() {
        val strip = CoverStrip()
        val logo = TrackInfo("Song 1", listOf("X"), artworkUrl = "http://ha/api/media_player_proxy/m?token=a&cache=logo", source = "Radio", kind = MediaKind.RADIO)
        val first = strip.update(logo)
        val own = strip.update(logo.copy(artworkUrl = "https://img.example/song1.jpg", album = "Album 1"))
        assertThat(own.middle.id).isEqualTo(first.middle.id)
        assertThat(own.cards).hasSize(1)
        // A new token for the same picture is no new picture either.
        val next = TrackInfo("Song 2", listOf("Y"), artworkUrl = "http://ha/api/media_player_proxy/m?token=b&cache=logo", source = "Radio", kind = MediaKind.RADIO)
        val second = strip.update(next)
        assertThat(second.middle.id).isNotEqualTo(first.middle.id)
        assertThat(second.cards.map { it.id }).containsExactlyInAnyOrder(first.middle.id, second.middle.id)
        assertThat(strip.update(next.copy(artworkUrl = "http://ha/api/media_player_proxy/m?token=c&cache=logo")).middle.id).isEqualTo(second.middle.id)
    }

    @Test
    fun `a picture is the same picture whatever token its address carries`() {
        val a = TrackInfo("S", artworkUrl = "http://ha:8123/api/media_player_proxy/media_player.x?token=1&cache=abc")
        val b = a.copy(artworkUrl = "http://ha:8123/api/media_player_proxy/media_player.x?token=2&cache=abc")
        assertThat(a.artworkId).isEqualTo("http://ha:8123/api/media_player_proxy/media_player.x?cache=abc")
        assertThat(b.pictureKey).isEqualTo(a.pictureKey)
        assertThat(TrackInfo("S", artworkUrl = "https://cdn.example/a.jpg?size=512").artworkId).isEqualTo("https://cdn.example/a.jpg?size=512")
        // A station's scene goes with its picture; a song's with the song alone.
        assertThat(a.copy(kind = MediaKind.RADIO).sceneKey).isNotEqualTo(a.copy(kind = MediaKind.RADIO, artworkUrl = "https://img.example/b.jpg").sceneKey)
        assertThat(a.copy(kind = MediaKind.RADIO).sceneKey).isEqualTo(b.copy(kind = MediaKind.RADIO).sceneKey)
        assertThat(a.sceneKey).isEqualTo(a.copy(artworkUrl = "https://img.example/b.jpg").sceneKey)
    }
}
