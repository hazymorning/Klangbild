package io.github.hazymorning.klangbild.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LandscapePolicyTest {
    private val track = TrackInfo("Kreis, Quadrat, Linie", listOf("Ensemble Dessau"))
    private val playing = Screen.Stage(track, PlayerStatus.PLAYING, null)
    private val paused = Screen.Stage(track, PlayerStatus.PAUSED, null)
    private val pause = 180_000L
    private val off = 0L
    private val policy = LandscapePolicy()

    private fun shows(screen: Screen, at: Long, playingMs: Long = off, pauseMs: Long = pause) = policy.shows(screen, at, pauseMs, playingMs)

    @Test
    fun `a pause turns into the landscape after its delay`() {
        assertThat(shows(playing, 0)).isFalse()
        assertThat(shows(paused, 10_000)).isFalse()
        assertThat(shows(paused, 10_000 + pause - 1)).isFalse()
        assertThat(shows(paused, 10_000 + pause)).isTrue()
        // Music again: the stage.
        assertThat(shows(playing, 10_000 + pause + 1)).isFalse()
    }

    @Test
    fun `a key press ends it and starts the delay again`() {
        shows(paused, 0)
        assertThat(shows(paused, pause)).isTrue()
        policy.interaction(pause + 5_000)
        assertThat(shows(paused, pause + 6_000)).isFalse()
        assertThat(shows(paused, 2 * pause + 4_999)).isFalse()
        assertThat(shows(paused, 2 * pause + 5_000)).isTrue()
    }

    @Test
    fun `while music plays only when that is set`() {
        assertThat(shows(playing, 0, playingMs = 60_000)).isFalse()
        assertThat(shows(playing, 59_999, playingMs = 60_000)).isFalse()
        assertThat(shows(playing, 60_000, playingMs = 60_000)).isTrue()
        val other = LandscapePolicy()
        other.shows(playing, 0, pause, off)
        assertThat(other.shows(playing, 3_600_000, pause, off)).isFalse()
    }

    @Test
    fun `the quiet clock comes first, then the landscape`() {
        val idle = Screen.Rest(RestReason.IDLE)
        assertThat(shows(idle, 0)).isFalse()
        assertThat(shows(idle, pause - 1)).isFalse()
        assertThat(shows(idle, pause)).isTrue()
    }

    @Test
    fun `screens with something to say are never covered, not even when it was asked for`() {
        policy.request(true)
        assertThat(shows(Screen.Rest(RestReason.OFFLINE), 0)).isFalse()
        assertThat(shows(Screen.Rest(RestReason.UNAVAILABLE), pause * 10)).isFalse()
        assertThat(shows(Screen.Setup(SetupReason.REQUESTED), pause * 10)).isFalse()
        // The quick settings holding the landscape ask for it here too: it says so, and the hold gives way.
        assertThat(policy.trouble).isTrue()
        assertThat(shows(paused, pause * 11)).isTrue()
        assertThat(policy.trouble).isFalse()
        // A lost link dims the stage like a pause, but the landscape waits for it.
        val down = Screen.Stage(track, PlayerStatus.PAUSED, null, linkDown = true)
        val fresh = LandscapePolicy()
        fresh.shows(down, 0, pause, off)
        assertThat(fresh.shows(down, pause * 2, pause, off)).isFalse()
    }

    @Test
    fun `chosen, it shows at once and stays through other keys until it is left`() {
        policy.request(true)
        assertThat(shows(playing, 0)).isTrue()
        // Skipping a song, a pause: the keys do their work and the landscape stays.
        policy.interaction(1_000)
        assertThat(shows(playing, 1_001)).isTrue()
        assertThat(shows(paused, 2_000)).isTrue()
        policy.leave(3_000)
        assertThat(shows(paused, 3_001)).isFalse()
        assertThat(policy.requested).isFalse()
        // Left, it comes by itself again only after the whole delay.
        assertThat(shows(paused, 3_000 + pause - 1)).isFalse()
        assertThat(shows(paused, 3_000 + pause)).isTrue()
    }

    @Test
    fun `switched off, a pause stays a pause`() {
        assertThat(shows(paused, 0, pauseMs = off)).isFalse()
        assertThat(shows(paused, 3_600_000, pauseMs = off)).isFalse()
    }
}
