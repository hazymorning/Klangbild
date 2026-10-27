package io.github.hazymorning.klangbild.app

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.github.hazymorning.klangbild.domain.UpdateState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test

/** Klangbild's own new versions: asked for at most once a day, offered in the menu, installed on OK. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesTest {
    private val test = TestScope(StandardTestDispatcher())
    private val store = MemoryValues()
    private var clock = 1_800_000_000_000L
    private var newest: String? = "v2026.11.0"
    private var asked = 0
    private val installed = ArrayList<String>()
    private var installs = true

    private fun updates(version: String = "2026.10.5") = Updates(
        test, store, version,
        latest = {
            asked++
            newest ?: throw IllegalStateException("no answer")
        },
        install = { tag ->
            installed += tag
            installs
        },
        now = { clock },
        log = QuietLog,
    )

    @Test
    fun `GitHub is asked as a screen starts, at most once a day, and what it said is kept`() {
        val u = updates()
        u.checkIfDue()
        test.runCurrent()
        assertThat(asked).isEqualTo(1)
        assertThat(u.info.value.available).isEqualTo("v2026.11.0")
        // A start later the same day asks nothing, and knows what GitHub said.
        clock += 23 * 3_600_000L
        val again = updates()
        again.checkIfDue()
        test.runCurrent()
        assertThat(asked).isEqualTo(1)
        assertThat(again.info.value.available).isEqualTo("v2026.11.0")
        // A day after the answer, it asks again.
        clock += 3_600_000L
        updates().checkIfDue()
        test.runCurrent()
        assertThat(asked).isEqualTo(2)
    }

    @Test
    fun `without an answer it asks again at the next start, and a build of one's own never asks`() {
        newest = null
        updates().checkIfDue()
        test.runCurrent()
        updates().checkIfDue()
        test.runCurrent()
        assertThat(asked).isEqualTo(2)
        updates(version = "2026.10.24 (abc1234)").checkIfDue()
        test.runCurrent()
        assertThat(asked).isEqualTo(2)
    }

    @Test
    fun `OK installs the new version, a failure says so and OK tries again, nothing new is asked for anew`() {
        val u = updates()
        u.ok()
        test.runCurrent()
        assertThat(asked).isEqualTo(1)
        assertThat(installed).isEmpty()
        installs = false
        u.ok()
        test.runCurrent()
        assertThat(u.info.value.state).isEqualTo(UpdateState.FAILED)
        installs = true
        u.ok()
        test.runCurrent()
        assertThat(installed).containsExactly("v2026.11.0", "v2026.11.0")
        assertThat(u.info.value.state).isEqualTo(UpdateState.IDLE)
    }
}
