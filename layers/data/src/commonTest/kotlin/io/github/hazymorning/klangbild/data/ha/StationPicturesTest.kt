package io.github.hazymorning.klangbild.data.ha

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.hazymorning.klangbild.data.ha.StationPictures.Offer
import io.github.hazymorning.klangbild.data.ha.StationPictures.Rank
import kotlin.test.Test

/**
 * A station's picture as Music Assistant offers it through Home Assistant: the station's first,
 * the song's own a moment later, sometimes the station's again for a while. The song's picture
 * wins and stays while the song plays.
 */
class StationPicturesTest {
    /** Home Assistant's proxy for the picture with [hash], with a token that changes now and then. */
    private fun proxy(hash: String, token: String = "t1") =
        Offer("http://ha:8123/api/media_player_proxy/media_player.wz?token=$token&cache=$hash", null, Rank.UNKNOWN)

    private val station = proxy("station")
    private val art = proxy("art")

    @Test
    fun `the song's own picture follows the station's and stays when the station's comes back`() {
        val p = StationPictures()
        assertThat(p.choose("song 1", listOf(station))).isEqualTo(station)
        assertThat(p.choose("song 1", listOf(art))).isEqualTo(art)
        assertThat(p.choose("song 1", listOf(station))).isEqualTo(art)
        assertThat(p.choose("song 1", listOf(art))).isEqualTo(art)
    }

    @Test
    fun `a picture seen with another song is the station's, and the next song's own one wins over it`() {
        val p = StationPictures()
        p.choose("song 1", listOf(station))
        p.choose("song 1", listOf(art))
        // The next song: the station's picture first (seen before with song 1, so it is the station's).
        assertThat(p.choose("song 2", listOf(station))).isEqualTo(station)
        val second = proxy("art 2")
        assertThat(p.choose("song 2", listOf(second))).isEqualTo(second)
        // The station's comes back for a while, even before the song's had shown: it never wins.
        assertThat(p.choose("song 2", listOf(station))).isEqualTo(second)
        assertThat(p.choose("song 3", listOf(proxy("art 3")))).isEqualTo(proxy("art 3"))
        assertThat(p.choose("song 3", listOf(station))).isEqualTo(proxy("art 3"))
    }

    @Test
    fun `an address of its own is the song's picture and never gives way to the proxy`() {
        val p = StationPictures()
        val own = Offer("https://img.example/love-and-hate.jpg", "http://ha:8123/api/media_player_proxy/media_player.wz?token=t1&cache=x", Rank.SONG)
        assertThat(p.choose("song", listOf(station))).isEqualTo(station)
        assertThat(p.choose("song", listOf(own))).isEqualTo(own)
        assertThat(p.choose("song", listOf(station))).isEqualTo(own)
    }

    @Test
    fun `the queue's station picture stands behind Home Assistant's, and is taken when there is nothing else`() {
        val p = StationPictures()
        val queued = Offer("https://cdn.example/station.png", null, Rank.STATION, certain = true)
        assertThat(p.choose("song", listOf(art, queued))).isEqualTo(art)
        assertThat(p.choose("other", listOf(queued))).isEqualTo(queued)
        // Home Assistant showing that very address is the station's picture too.
        val same = Offer("https://cdn.example/station.png", null, Rank.SONG)
        val later = proxy("later")
        assertThat(p.choose("third", listOf(same, queued))).isEqualTo(same)
        assertThat(p.choose("third", listOf(later, queued))).isEqualTo(later)
    }

    @Test
    fun `nothing on offer keeps the song's picture`() {
        val p = StationPictures()
        p.choose("song", listOf(art))
        assertThat(p.choose("song", emptyList())).isEqualTo(art)
        assertThat(p.choose("next", emptyList())).isNull()
    }

    @Test
    fun `a renewed token is the same picture`() {
        val p = StationPictures()
        p.choose("song", listOf(station))
        p.choose("song", listOf(art))
        val renewed = proxy("art", token = "t2")
        assertThat(p.choose("song", listOf(renewed))?.url).isEqualTo(renewed.url)
        // ...and the station's, renewed too, is still the one the song left.
        assertThat(p.choose("song", listOf(proxy("station", token = "t2")))?.url).isEqualTo(renewed.url)
    }

    @Test
    fun `Music Assistant's own word for the song's picture holds even when the picture recurs`() {
        val p = StationPictures()
        val said = Offer("https://img.example/album.jpg", null, Rank.SONG, certain = true)
        p.choose("song 1", listOf(said))
        assertThat(p.choose("song 2", listOf(said, station))).isEqualTo(said)
    }
}
