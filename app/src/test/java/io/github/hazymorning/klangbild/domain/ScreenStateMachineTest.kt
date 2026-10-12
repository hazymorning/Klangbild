package io.github.hazymorning.klangbild.domain

import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.SourceState
import org.junit.Test

class ScreenStateMachineTest {
    private val song = TrackInfo("Song", listOf("A"), durationMs = 200_000)
    private val timing = Timing(sleepAfterMs = 600_000, pauseToRestMs = 180_000, linkGraceMs = 20_000, linkGiveUpMs = 120_000)

    private fun src(
        status: PlayerStatus? = PlayerStatus.PLAYING,
        link: LinkState = LinkState.CONNECTED,
        track: TrackInfo? = song,
        downSince: Long? = null,
        missing: Boolean = false,
    ) = SourceState(
        link = link,
        player = status?.let { PlayerSnapshot(it, track, friendlyName = "Wohnzimmer") },
        entityMissing = missing,
        linkDownSinceMs = downSince,
    )

    @Test
    fun `playing shows the stage and keeps the screen on`() {
        val m = ScreenStateMachine(timing)
        val d = m.decide(src(), now = 0)
        assertThat(d.screen).isEqualTo(Screen.Stage(song, PlayerStatus.PLAYING, null))
        assertThat(d.keepScreenOn).isTrue()
        assertThat(d.asleep).isFalse()
    }

    @Test
    fun `pause dims first, rests later, sleeps after the timeout`() {
        val m = ScreenStateMachine(timing)
        m.decide(src(), 0)
        assertThat(m.decide(src(PlayerStatus.PAUSED), 1_000).screen).isInstanceOf(Screen.Stage::class.java)
        assertThat(m.decide(src(PlayerStatus.PAUSED), 1_000 + 180_000).screen).isEqualTo(Screen.Rest(RestReason.PAUSED))
        val late = m.decide(src(PlayerStatus.PAUSED), 1_000 + 600_000)
        assertThat(late.asleep).isTrue()
        assertThat(late.keepScreenOn).isFalse()
        // Music again: wakes up immediately.
        val again = m.decide(src(), 1_000 + 700_000)
        assertThat(again.screen).isInstanceOf(Screen.Stage::class.java)
        assertThat(again.keepScreenOn).isTrue()
    }

    @Test
    fun `a pause with a party's code on the stage never turns into the clock`() {
        val m = ScreenStateMachine(timing)
        m.decide(src(), 0)
        m.decide(src(PlayerStatus.PAUSED), 1_000, holdPause = true)
        assertThat(m.decide(src(PlayerStatus.PAUSED), 1_000 + 180_000, holdPause = true).screen).isInstanceOf(Screen.Stage::class.java)
        // The code gone, the clock comes as it would.
        assertThat(m.decide(src(PlayerStatus.PAUSED), 1_000 + 181_000).screen).isEqualTo(Screen.Rest(RestReason.PAUSED))
    }

    @Test
    fun `idle and off rest with the speaker name`() {
        val m = ScreenStateMachine(timing)
        assertThat(m.decide(src(PlayerStatus.IDLE, track = null), 0).screen).isEqualTo(Screen.Rest(RestReason.IDLE, "Wohnzimmer"))
        assertThat(m.decide(src(PlayerStatus.OFF, track = null), 0).screen).isEqualTo(Screen.Rest(RestReason.IDLE, "Wohnzimmer"))
        assertThat(m.decide(src(PlayerStatus.UNAVAILABLE, track = null), 0).screen)
            .isEqualTo(Screen.Rest(RestReason.UNAVAILABLE, "Wohnzimmer"))
    }

    @Test
    fun `between two songs the stage stays, so a skip never passes through the rest screen`() {
        val m = ScreenStateMachine(timing)
        m.decide(src(), 0)
        // The player says for a moment that nothing plays, or plays without a title.
        assertThat(m.decide(src(PlayerStatus.IDLE, track = null), 500).screen).isEqualTo(Screen.Stage(song, PlayerStatus.PLAYING, null))
        assertThat(m.decide(src(PlayerStatus.PLAYING, track = null), 1_500).screen).isInstanceOf(Screen.Stage::class.java)
        val next = song.copy(title = "Next")
        assertThat(m.decide(src(track = next), 2_000).screen).isEqualTo(Screen.Stage(next, PlayerStatus.PLAYING, null))
        // Stopped for good: the rest screen once the moment has passed.
        m.decide(src(PlayerStatus.IDLE, track = null), 3_000)
        assertThat(m.decide(src(PlayerStatus.IDLE, track = null), 3_000 + timing.betweenSongsMs).screen).isEqualTo(Screen.Rest(RestReason.IDLE, "Wohnzimmer"))
        // After a pause there is nothing to wait for.
        val p = ScreenStateMachine(timing)
        p.decide(src(PlayerStatus.PAUSED), 0)
        assertThat(p.decide(src(PlayerStatus.IDLE, track = null), 500).screen).isEqualTo(Screen.Rest(RestReason.IDLE, "Wohnzimmer"))
    }

    @Test
    fun `connection drops keep the stage for a grace period`() {
        val m = ScreenStateMachine(timing)
        m.decide(src(), 0)
        val short = m.decide(src(link = LinkState.RECONNECTING, downSince = 1_000), 5_000)
        assertThat(short.screen).isEqualTo(Screen.Stage(song, PlayerStatus.PLAYING, null, linkDown = false))
        val longer = m.decide(src(link = LinkState.RECONNECTING, downSince = 1_000), 30_000)
        assertThat(longer.screen).isEqualTo(Screen.Stage(song, PlayerStatus.PLAYING, null, linkDown = true))
        val long = m.decide(src(link = LinkState.RECONNECTING, downSince = 1_000), 130_000)
        assertThat(long.screen).isEqualTo(Screen.Rest(RestReason.OFFLINE, "Wohnzimmer"))
        // first connect without any data yet
        assertThat(m.decide(src(status = null, link = LinkState.CONNECTING), 0).screen).isEqualTo(Screen.Rest(RestReason.CONNECTING))
    }

    @Test
    fun `setup cases`() {
        val m = ScreenStateMachine(timing)
        assertThat(m.decide(src(status = null, link = LinkState.UNCONFIGURED), 0).screen).isEqualTo(Screen.Setup(SetupReason.FIRST_RUN))
        assertThat(m.decide(src(status = null, link = LinkState.AUTH_FAILED), 0).screen).isEqualTo(Screen.Setup(SetupReason.AUTH_FAILED))
        assertThat(m.decide(src(missing = true, status = null), 0).screen).isEqualTo(Screen.Rest(RestReason.ENTITY_MISSING))
        m.requestSetup(true)
        val d = m.decide(src(), 0)
        assertThat(d.screen).isEqualTo(Screen.Setup(SetupReason.REQUESTED))
        assertThat(d.keepScreenOn).isTrue()
        m.requestSetup(false)
        assertThat(m.decide(src(), 0).screen).isInstanceOf(Screen.Stage::class.java)
    }

    @Test
    fun `setup waits longer before releasing the screen`() {
        val m = ScreenStateMachine(timing)
        assertThat(m.decide(src(status = null, link = LinkState.UNCONFIGURED), 0).asleep).isFalse()
        assertThat(m.decide(src(status = null, link = LinkState.UNCONFIGURED), 700_000).asleep).isFalse()
        assertThat(m.decide(src(status = null, link = LinkState.UNCONFIGURED), 1_800_000).asleep).isTrue()
    }
}
