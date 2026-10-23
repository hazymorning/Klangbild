package io.github.hazymorning.klangbild.data

import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.data.ha.TrackMatch
import io.github.hazymorning.klangbild.domain.Catalog
import org.junit.Test

class MatchingTest {
    @Test
    fun `the test catalog covers the hard cases`() {
        val t = Catalog.tracks
        assertThat(t.any { it.artworkUrl == null && it.source != null }).isTrue() // radio without cover
        assertThat(t.any { it.title.contains(": ") && it.artists.size > 4 }).isTrue() // classical, many artists
        assertThat(t.any { it.album == null }).isTrue()
        assertThat(t.any { it.title.any { c -> c.code in 0x3040..0x9FFF } }).isTrue() // Japanese
        assertThat(t.any { it.title.any { c -> c.code in 0x0600..0x06FF } }).isTrue() // Arabic
        assertThat(t.any { it.title.contains("🌙") }).isTrue()
        assertThat(t.map { it.key }.toSet()).hasSize(t.size)
    }

    @Test
    fun `player and queue titles are matched loosely`() {
        assertThat(TrackMatch.sameTitle("Bohemian Rhapsody (Remastered 2011)", "Bohemian Rhapsody (Remastered 2011)")).isTrue()
        assertThat(TrackMatch.sameTitle("Café del Mar", "Cafe del Mar")).isTrue()
        assertThat(TrackMatch.sameTitle("Heroes", "Heroes (Remastered 2017)")).isTrue()
        assertThat(TrackMatch.sameTitle("Heroes", "Changes")).isFalse()
    }
}
