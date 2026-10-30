package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

/** When music from another room takes the screen, and when the chosen speaker gets it back. */
class OtherRoomsTest {
    private val song = TrackInfo(title = "Lied")

    private fun player(status: PlayerStatus, track: TrackInfo? = song) = PlayerSnapshot(status = status, track = track)

    private val idle = player(PlayerStatus.IDLE, null)
    private val playing = player(PlayerStatus.PLAYING)
    private val paused = player(PlayerStatus.PAUSED)
    private val kitchen = "media_player.kitchen"
    private val study = "media_player.study"

    /** Rooms where nothing has played since the start. */
    private fun quiet(vararg ids: String) = OtherRooms(backMs = 4_000, pauseMs = 180_000).also { r ->
        r.update(idle, ids.associateWith { idle }, 0)
    }

    @Test
    fun `music in another room shows while nothing plays here, and the chosen speaker takes it back at once`() {
        val r = quiet(kitchen)
        assertThat(r.update(idle, mapOf(kitchen to playing), 10_000)).isEqualTo(kitchen)
        assertThat(r.update(playing, mapOf(kitchen to playing), 11_000)).isNull()
    }

    @Test
    fun `a moment of silence between two tracks here hands nothing over`() {
        val r = quiet(kitchen)
        assertThat(r.update(playing, mapOf(kitchen to playing), 10_000)).isNull()
        assertThat(r.update(idle, mapOf(kitchen to playing), 11_000)).isNull()
        assertThat(r.changeAt).isEqualTo(15_000)
        assertThat(r.update(playing, mapOf(kitchen to playing), 13_000)).isNull()
        assertThat(r.changeAt).isNull()
        // The next silence counts from its own start.
        assertThat(r.update(idle, mapOf(kitchen to playing), 100_000)).isNull()
        assertThat(r.update(idle, mapOf(kitchen to playing), 104_000)).isEqualTo(kitchen)
    }

    @Test
    fun `a pause here keeps the screen as long as the stage stays`() {
        val r = quiet(kitchen)
        r.update(playing, mapOf(kitchen to idle), 10_000)
        assertThat(r.update(paused, mapOf(kitchen to playing), 11_000)).isNull()
        assertThat(r.update(paused, mapOf(kitchen to playing), 190_999)).isNull()
        assertThat(r.update(paused, mapOf(kitchen to playing), 191_000)).isEqualTo(kitchen)
    }

    @Test
    fun `music that plays keeps the screen, and the speaker that started last follows`() {
        val r = quiet(kitchen, study)
        assertThat(r.update(idle, mapOf(kitchen to playing, study to idle), 10_000)).isEqualTo(kitchen)
        // The study starts meanwhile: the kitchen still plays and keeps the screen.
        assertThat(r.update(idle, mapOf(kitchen to playing, study to playing), 20_000)).isEqualTo(kitchen)
        // The kitchen stops: after a moment the study shows.
        assertThat(r.update(idle, mapOf(kitchen to idle, study to playing), 30_000)).isEqualTo(kitchen)
        assertThat(r.update(idle, mapOf(kitchen to idle, study to playing), 34_000)).isEqualTo(study)
    }

    @Test
    fun `another room that goes quiet gives the screen back to the chosen speaker`() {
        val r = quiet(kitchen)
        r.update(idle, mapOf(kitchen to playing), 10_000)
        // Paused, it stays as a paused stage would; stopped, only a moment.
        assertThat(r.update(idle, mapOf(kitchen to paused), 20_000)).isEqualTo(kitchen)
        assertThat(r.update(idle, mapOf(kitchen to paused), 199_999)).isEqualTo(kitchen)
        assertThat(r.update(idle, mapOf(kitchen to paused), 200_000)).isNull()
        assertThat(r.update(idle, mapOf(kitchen to playing), 210_000)).isEqualTo(kitchen)
        assertThat(r.update(idle, mapOf(kitchen to null), 220_000)).isEqualTo(kitchen)
        assertThat(r.update(idle, mapOf(kitchen to null), 224_000)).isNull()
    }

    @Test
    fun `a speaker taken off the list gives the screen back at once`() {
        val r = quiet(kitchen)
        r.update(idle, mapOf(kitchen to playing), 10_000)
        assertThat(r.update(idle, emptyMap(), 11_000)).isNull()
        r.update(idle, mapOf(kitchen to playing), 20_000)
        r.reset()
        assertThat(r.shown).isNull()
    }
}
