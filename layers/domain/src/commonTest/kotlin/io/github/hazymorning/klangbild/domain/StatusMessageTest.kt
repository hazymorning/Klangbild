package io.github.hazymorning.klangbild.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

/** What the status line shows of a notification, and for how long. */
class StatusMessageTest {
    private fun board() = StatusMessage(showMs = 15_000, waitMs = 600_000)

    @Test
    fun `a notification shows on one line, its title first, for a while from when it is seen`() {
        val m = board()
        m.arrive("Laundry", "Done,\n  ready to\thang up", 0)
        assertThat(m.line(visible = true, t = 1_000)).isEqualTo("Laundry${TimeText.SEPARATOR}Done, ready to hang up")
        assertThat(m.line(visible = true, t = 15_999)).isEqualTo("Laundry${TimeText.SEPARATOR}Done, ready to hang up")
        assertThat(m.line(visible = true, t = 16_000)).isNull()
        assertThat(m.line(visible = true, t = 17_000)).isNull()
    }

    @Test
    fun `the newest replaces the one showing`() {
        val m = board()
        m.arrive(null, "Front door", 0)
        assertThat(m.line(visible = true, t = 0)).isEqualTo("Front door")
        m.arrive("", "Back door", 10_000)
        assertThat(m.line(visible = true, t = 10_000)).isEqualTo("Back door")
        assertThat(m.line(visible = true, t = 24_999)).isEqualTo("Back door")
    }

    @Test
    fun `one that can't be seen waits, but not for long`() {
        val m = board()
        m.arrive(null, "Front door", 0)
        assertThat(m.line(visible = false, t = 300_000)).isNull()
        assertThat(m.line(visible = true, t = 300_000)).isEqualTo("Front door")
        m.arrive(null, "Laundry done", 400_000)
        assertThat(m.line(visible = false, t = 1_000_001)).isNull()
        assertThat(m.line(visible = true, t = 1_000_002)).isNull()
    }

    @Test
    fun `the phones' commands are no words, and clear_notification takes the one showing away`() {
        val m = board()
        m.arrive(null, "request_location_update", 0)
        m.arrive(null, "command_volume_level", 0)
        assertThat(m.line(visible = true, t = 0)).isNull()
        m.arrive(null, "Front door", 0)
        m.arrive(null, "clear_notification", 1_000)
        assertThat(m.line(visible = true, t = 1_000)).isNull()
    }
}
