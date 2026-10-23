package io.github.hazymorning.klangbild.speaker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.first
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
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * The speaker as a whole (its store, connection, playback and audio thread) against aiosendspin
 * 10.2.0, with an output on the real clock. Runs only when given a Python with that package
 * (`-Pklangbild.sendspin=/path/to/venv/bin/python`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SpeakerEndToEndTest {
    private val python: String? = System.getProperty("klangbild.sendspin")

    /** The script that starts the reference server, ci/sendspin_server.py, as the build names it. */
    private val server: String? = System.getProperty("klangbild.sendspinServer")

    private object PlainBox : SecretBox {
        override fun seal(plain: String) = plain
        override fun open(stored: String) = stored
        override fun isPlain(stored: String) = true
    }

    @Test
    fun `it plays, keeps the volume it is given, and goes quiet at the end`(): Unit = runBlocking {
        assumeTrue("needs -Pklangbild.sendspin=<python with aiosendspin 10.2.0>", python != null)
        val process = ProcessBuilder(
            python!!, "-I", server!!,
            "--play-seconds", "3", "--volume-command", "40", "--end", "--linger", "3",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val lines = LinkedBlockingQueue<String>()
        Thread { process.inputStream.bufferedReader().forEachLine { lines += it } }.apply { isDaemon = true }.start()
        fun await(name: String): String {
            while (true) {
                val line = lines.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("no $name")
                if (line.startsWith("ERROR")) throw AssertionError(line)
                if (line.split(' ').first() == name) return line
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = SpeakerStore(speakerValues(context), PlainBox)
        val sink = RealTimeSink()
        val port = await("READY").split(' ').first { it.startsWith("port=") }.substringAfter('=')
        val speaker = Speaker(
            scope, speakerClient(OkHttpClient()), store, "ws://127.0.0.1:$port/sendspin",
            SpeakerIdentity("Living room TV", "Test box", "Klangbild", "test"),
            sinkFactory = { sink },
            audioThread = AndroidAudioThread(setup = {}),
            sleptUs = { 0 },
            log = AndroidLog,
        )
        try {
            speaker.start()
            await("PLAYER_READY")
            assertThat(speaker.status.value.link).isEqualTo(SpeakerLink.CONNECTED)
            assertThat(speaker.status.value.clientId).isNotNull()
            assertThat(speaker.pairingToken).isNotNull().startsWith("SP:0")
            // Its own playback as a sound feed: what is heard now, timed by the output.
            speaker.sound.reading(true)
            await("STREAM_STARTED")
            val heard = SoundFrame()
            val heardBy = System.nanoTime() + 5_000_000_000L
            // The silence before the music's first moment is heard too: what counts is the music.
            fun music() = speaker.sound.read(System.nanoTime() / 1_000, heard) && heard.bands.sum() > 0f
            while (!music() && System.nanoTime() < heardBy) Thread.sleep(20)
            assertThat(heard.bands.sum()).isGreaterThan(0f)
            assertThat(heard.timing).isEqualTo(SoundTiming.EXACT)
            assertThat(System.nanoTime() / 1_000 - heard.timeUs).isBetween(0L, 40_000L)
            await("COMMAND")
            await("STREAM_ENDED")
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
            scope.cancel()
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
