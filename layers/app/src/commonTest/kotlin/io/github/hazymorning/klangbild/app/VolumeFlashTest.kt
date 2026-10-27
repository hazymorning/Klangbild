package io.github.hazymorning.klangbild.app

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.PlayerSnapshot
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.TrackInfo
import kotlin.test.Test

/** The volume shows for a moment after a volume key or a reported change, but not for another speaker's volume. */
class VolumeFlashTest {
    private var t = 0L
    private val flash = VolumeFlash { t }

    private fun at(speaker: String, volume: Float, muted: Boolean = false) =
        SourceState(LinkState.CONNECTED, player = PlayerSnapshot(PlayerStatus.PLAYING, TrackInfo("Song"), volume = volume, muted = muted), speaker = speaker)

    @Test
    fun `a volume changed anywhere shows for a moment`() {
        flash.reported(at("media_player.wz", 0.3f), null, t)
        assertThat(flash.shown(t)).isFalse()
        t = 1_000
        flash.reported(at("media_player.wz", 0.35f), "media_player.wz", t)
        assertThat(flash.shown(t)).isTrue()
        assertThat(flash.shown(t + VolumeFlash.SHOWN_MS)).isFalse()
        // Muting is a change too.
        t = 10_000
        flash.reported(at("media_player.wz", 0.35f, muted = true), "media_player.wz", t)
        assertThat(flash.shown(t)).isTrue()
    }

    @Test
    fun `another speaker's volume, or a new connection's first, is no change`() {
        flash.reported(at("media_player.wz", 0.3f), null, t)
        flash.reported(at("media_player.tv", 0.8f), "media_player.wz", t)
        assertThat(flash.shown(t)).isFalse()
        flash.reset()
        flash.reported(at("media_player.tv", 0.5f), "media_player.tv", t)
        assertThat(flash.shown(t)).isFalse()
    }

    @Test
    fun `a volume key shows it at once`() {
        flash.keyPressed()
        assertThat(flash.shown(t)).isTrue()
        assertThat(flash.shown(t + VolumeFlash.SHOWN_MS)).isFalse()
    }
}
