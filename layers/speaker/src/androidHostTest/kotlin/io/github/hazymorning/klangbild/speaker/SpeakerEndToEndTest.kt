package io.github.hazymorning.klangbild.speaker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.startsWith
import io.github.hazymorning.klangbild.config.SecretBox
import io.github.hazymorning.klangbild.data.AndroidLog
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.sound.SoundFrame
import io.github.hazymorning.klangbild.sound.SoundTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The speaker as a whole (its store, connection, playback and audio thread) against aiosendspin
 * 10.2.0 ([SendspinServer]). Runs only when given a Python with that package
 * (`-Pklangbild.sendspin=/path/to/venv/bin/python`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SpeakerEndToEndTest {
    private object PlainBox : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(stored: String) = stored
        override fun isPlain(stored: String) = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = SpeakerStore(speakerValues(ApplicationProvider.getApplicationContext<Context>()), PlainBox)

    private fun speaker(server: SendspinServer, sink: AudioSink) = Speaker(
        scope, speakerClient(OkHttpClient()), store, server.url(),
        SpeakerIdentity("Living room TV", "Test box", "Klangbild", "test"),
        sinkFactory = { sink },
        audioThread = AndroidAudioThread(setup = {}),
        sleptUs = { 0 },
        log = AndroidLog,
    )

    @BeforeTest
    fun needsTheServer() = SendspinServer.assumeAvailable()

    @AfterTest
    fun stopSpeakers() = scope.cancel()

    @Test
    fun `it plays, keeps the volume it is given, and goes quiet at the end`(): Unit = runBlocking {
        SendspinServer("--play-seconds", "3", "--volume-command", "40", "--end", "--linger", "3").use { server ->
            val sink = RealTimeSink()
            val speaker = speaker(server, sink)
            try {
                speaker.start()
                server.await("PLAYER_READY")
                assertThat(speaker.status.value.link).isEqualTo(SpeakerLink.CONNECTED)
                assertThat(speaker.status.value.clientId).isNotNull()
                assertThat(speaker.pairingToken).isNotNull().startsWith("SP:0")
                // Its own playback as a sound feed: what is heard now, timed by the output.
                speaker.sound.reading(true)
                server.await("STREAM_STARTED")
                val heard = SoundFrame()
                val heardBy = System.nanoTime() + 5_000_000_000L
                // The silence before the music's first moment is heard too: what counts is the music.
                fun music() = speaker.sound.read(System.nanoTime() / 1_000, heard) && heard.bands.sum() > 0f
                while (!music() && System.nanoTime() < heardBy) Thread.sleep(20)
                assertThat(heard.bands.sum()).isGreaterThan(0f)
                assertThat(heard.timing).isEqualTo(SoundTiming.EXACT)
                assertThat(System.nanoTime() / 1_000 - heard.timeUs).isBetween(0L, 40_000L)
                server.await("COMMAND")
                server.await("STREAM_ENDED")
                // Playing while the stream ran, quiet once it ended.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (speaker.status.value.playing && System.nanoTime() < deadline) Thread.sleep(20)
                assertThat(speaker.status.value.playing).isFalse()
                // The whole stream played, the volume ramped in its middle: about 4 s of music.
                assertThat(sink.musicFrames).isGreaterThan(48_000 * 3)
                // The volume Music Assistant set is kept for the next start.
                assertThat(store.load().volume).isEqualTo(40)
            } finally {
                speaker.stop("shutdown")
            }
        }
    }
}
