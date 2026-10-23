package io.github.hazymorning.klangbild.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.data.httpClient
import io.github.hazymorning.klangbild.data.ma.MilkdropSound
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.PlayerSnapshot
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.domain.SpeakerStatus
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.sound.SoundFeed
import io.github.hazymorning.klangbild.sound.SoundFrame
import io.github.hazymorning.klangbild.sound.SoundTiming
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test

/** Which feed the sound comes from: this TV's own playback, Music Assistant's MilkDrop feed, or nothing. */
class SoundSelectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relay = MockWebServer().apply {
        enqueue(MockResponse.Builder().code(404).build())
        start()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        relay.close()
    }

    /** This TV's own playback, as far as it is read. */
    private class Own : SoundFeed {
        var readers = 0
        override fun read(nowUs: Long, out: SoundFrame): Boolean {
            out.silence()
            out.timing = SoundTiming.EXACT
            return true
        }

        override fun reading(on: Boolean) {
            readers += if (on) 1 else -1
        }
    }

    private val playing = PlayerSnapshot(PlayerStatus.PLAYING, TrackInfo("Lied"), activeQueue = "q1", isMusicAssistant = true)

    private fun shown(speaker: String, player: PlayerSnapshot = playing) =
        SourceState(LinkState.CONNECTED, player = player, speaker = speaker, ownSpeaker = "media_player.tv")

    private val here = SpeakerStatus(SpeakerLink.CONNECTED, clientId = "c", playing = true)

    @Test
    fun `this TV's own playback is read while the music shown plays here`() {
        val own = Own()
        val sound = SoundSelection { own }
        sound.reading(true)
        sound.update(shown("media_player.tv"), here)
        assertThat(own.readers).isEqualTo(1)
        val out = SoundFrame()
        assertThat(sound.read(0, out)).isTrue()
        assertThat(out.timing).isEqualTo(SoundTiming.EXACT)
        // Stopped here, or another speaker shown: nothing more is read from it.
        sound.update(shown("media_player.tv"), here.copy(playing = false))
        assertThat(own.readers).isEqualTo(0)
        assertThat(sound.read(0, out)).isFalse()
        assertThat(out.timing).isEqualTo(SoundTiming.NONE)
    }

    @Test
    fun `another Music Assistant speaker is heard through the MilkDrop feed, and only while read`() {
        val sound = SoundSelection { null }
        sound.useMilkdrop(MilkdropSound(httpClient(OkHttp.create()), scope, relay.url("/").toString().trimEnd('/'), "ma-token"))
        sound.update(shown("media_player.kitchen"), SpeakerStatus())
        assertThat(relay.takeRequest(300, TimeUnit.MILLISECONDS)).isNull()
        sound.reading(true)
        val asked = relay.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(asked.url.encodedPath).isEqualTo("/milkdrop_visualizer")
        assertThat(asked.url.queryParameter("player")).isEqualTo("q1")
        // Without the plugin (404), nothing is known.
        assertThat(sound.read(0, SoundFrame())).isFalse()
    }

    @Test
    fun `a player that is not Music Assistant's has no sound`() {
        val sound = SoundSelection { Own() }
        sound.useMilkdrop(MilkdropSound(httpClient(OkHttp.create()), scope, relay.url("/").toString().trimEnd('/'), "ma-token"))
        sound.reading(true)
        sound.update(shown("media_player.radio", playing.copy(activeQueue = null, isMusicAssistant = false)), SpeakerStatus())
        assertThat(sound.read(0, SoundFrame())).isFalse()
        assertThat(relay.takeRequest(300, TimeUnit.MILLISECONDS)).isNull()
    }
}
