package io.github.hazymorning.klangbild.speaker

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.sendspin.ClockMap
import io.github.hazymorning.klangbild.sendspin.Hello
import io.github.hazymorning.klangbild.sendspin.KeyPair
import io.github.hazymorning.klangbild.sendspin.PairingRecord
import io.github.hazymorning.klangbild.sendspin.PairingToken
import io.github.hazymorning.klangbild.sendspin.PlayerState
import io.github.hazymorning.klangbild.sendspin.Session
import io.github.hazymorning.klangbild.sendspin.SessionEnd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The speaker's connection against the reference implementation Music Assistant runs: aiosendspin
 * 10.2.0 as ci/sendspin_server.py starts it, on this machine. It runs only when given a Python
 * with that package (`-Pklangbild.sendspin=/path/to/venv/bin/python`), as CI does.
 */
class SendspinEndToEndTest {
    private val python: String? = System.getProperty("klangbild.sendspin")

    @Before
    fun needsTheServer() = assumeTrue("needs -Pklangbild.sendspin=<python with aiosendspin 10.2.0>", python != null)

    /** The server, its events line by line as they come. */
    private inner class Server(vararg args: String) : AutoCloseable {
        private val process = ProcessBuilder(listOf(python!!, "-I", File("../ci/sendspin_server.py").absolutePath) + args)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        private val lines = LinkedBlockingQueue<String>()
        val all = CopyOnWriteArrayList<String>()

        init {
            Thread {
                process.inputStream.bufferedReader().forEachLine { lines += it }
            }.apply { isDaemon = true }.start()
        }

        /** The next event named [name], waiting at most [seconds]; earlier ones are kept in [all]. */
        fun await(name: String, seconds: Long = 30): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                val left = deadline - System.nanoTime()
                val line = lines.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("no $name within $seconds s; events: $all")
                all += line
                if (line.startsWith("ERROR")) throw AssertionError("server: $line")
                if (line.split(' ').first() == name) return line
            }
        }

        override fun close() {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun field(line: String, key: String): String = line.split(' ').first { it.startsWith("$key=") }.substringAfter('=')

    /** What the speaker was told, from the connection's thread. */
    private inner class Speaker : Session.Listener {
        var volume = 100
        var muted = false
        var delay = 0
        val chunks = CopyOnWriteArrayList<Chunk>()
        val events = CopyOnWriteArrayList<String>()
        val records = CopyOnWriteArrayList<PairingRecord>()
        @Volatile var clock: ClockMap? = null
        var end: ((SessionEnd, String) -> Unit)? = null

        override fun playerState() = PlayerState(volume, muted, delay, requiredLeadTimeMs = 500, minBufferMs = 300)
        override fun clock(map: ClockMap) {
            clock = map
        }
        override fun playerActive(active: Boolean) { events += "player $active" }
        override fun streamStart() { events += "start" }
        override fun audio(timestampUs: Long, sendAheadUs: Long, bytes: ByteArray, offset: Int, length: Int, arrivalUs: Long) {
            val first = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
            // Every sample of a chunk follows the one before (both channels alike).
            var steady = true
            for (i in 0 until length / 4) {
                val left = (bytes[offset + 4 * i].toInt() and 0xff) or ((bytes[offset + 4 * i + 1].toInt() and 0xff) shl 8)
                val right = (bytes[offset + 4 * i + 2].toInt() and 0xff) or ((bytes[offset + 4 * i + 3].toInt() and 0xff) shl 8)
                if (left != (first + i) and 0xffff || right != left) steady = false
            }
            chunks += Chunk(timestampUs, first, length / 4, arrivalUs, clock?.clientTime(timestampUs), steady, events.count { it == "clear" })
        }
        override fun clear() { events += "clear" }
        override fun streamEnd() { events += "end" }
        override fun volume(volume: Int) {
            this.volume = volume
            events += "volume $volume"
        }
        override fun mute(muted: Boolean) {
            this.muted = muted
            events += "mute $muted"
        }
        override fun outputDelay(ms: Int) {
            delay = ms
            events += "delay $ms"
        }
        override fun group(playing: Boolean, groupId: String, name: String) = Unit
        override fun beat(timestampUs: Long, downbeat: Boolean) = Unit
        override fun beatsCleared() = Unit
        override fun paired(record: PairingRecord) {
            records += record
            events += "paired"
        }
        override fun used(serverId: String) { events += "used $serverId" }
        override fun unpaired(serverId: String) = Unit
        override fun ended(end: SessionEnd, detail: String) {
            events += "ended $end"
            this.end?.invoke(end, detail)
        }
    }

    private class Chunk(val serverUs: Long, val firstFrame: Int, val frames: Int, val arrivalUs: Long, val dueUs: Long?, val steady: Boolean, val clears: Int)

    private val random = SecureRandom()
    private val identity = KeyPair.generate(random)
    private val pairingPsk = ByteArray(32).also(random::nextBytes)
    private val hello = Hello("Klangbild test", "Test box", "Klangbild", "test", bufferCapacity = 2_000_000, beats = false, beatBufferCapacity = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun connect(server: Server, speaker: Speaker): SpeakerConnection {
        val port = field(server.await("READY"), "port")
        val connection = SpeakerConnection(SpeakerConnection.client(OkHttpClient()), scope, "ws://127.0.0.1:$port/sendspin", { transport, ended ->
            speaker.end = ended
            Session(transport, identity, pairingPsk, { speaker.records }, hello, speaker, { System.nanoTime() / 1_000 }, random)
        })
        connection.start()
        return connection
    }

    @Test
    fun `it plays a stream from the reference server, sample by sample, in time`() {
        val speaker = Speaker()
        Server("--play-seconds", "3", "--volume-command", "30", "--mute-command", "--output-delay-command", "20", "--clear-after", "2", "--end", "--linger", "2").use { server ->
            val connection = connect(server, speaker)
            try {
                server.await("PLAYER_READY")
                assertThat(connection.link.value).isEqualTo(SpeakerLink.CONNECTED)
                // The first state says available, once the clock has settled.
                assertThat(server.all.first { it.startsWith("CLIENT_STATE") }).contains("\"available\":true")
                server.await("STREAM_CLEARED")
                val sent = server.await("SENT_CHUNKS")
                // The last chunks arrive after the server has counted them.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (speaker.chunks.size < field(sent, "n").toInt() && System.nanoTime() < deadline) Thread.sleep(20)
                assertThat(speaker.chunks).hasSize(field(sent, "n").toInt())
                assertWithMessage("chunks with a sample out of place").that(speaker.chunks.filterNot { it.steady }).isEmpty()

                // Before and after the clear, each chunk follows the one before: in its samples and its time.
                for (part in speaker.chunks.groupBy { it.clears }.values) {
                    for ((a, b) in part.zipWithNext()) {
                        assertThat(b.firstFrame).isEqualTo((a.firstFrame + a.frames) and 0xffff)
                        assertThat(b.serverUs - a.serverUs).isEqualTo(a.frames * 1_000_000L / 48_000)
                    }
                }
                // Each came before it was due, by about the lead the server keeps: the clock maps the server's time right.
                for (c in speaker.chunks) {
                    val ahead = c.dueUs!! - c.arrivalUs
                    assertThat(ahead).isGreaterThan(0L)
                    assertThat(ahead).isLessThan(3_000_000L)
                }
                assertThat(speaker.events).containsAtLeast("player true", "start", "volume 30", "mute true", "delay 20", "mute false", "clear", "end").inOrder()
                // The commands came back in the speaker's state.
                assertThat(server.all.last { it.startsWith("CLIENT_STATE") }).contains("\"volume\":30,\"muted\":false,\"output_delay_ms\":20")
            } finally {
                connection.stop("shutdown")
            }
            assertThat(field(server.await("CLIENT_DISCONNECTED"), "goodbye_reason")).isEqualTo("shutdown")
            assertThat(server.await("DONE")).contains("exit_code=0")
        }
    }

    @Test
    fun `it pairs with its token and goes on with the long-term PSK`() {
        val speaker = Speaker()
        Server("--pair-token", PairingToken.of(identity.publicKey, pairingPsk), "--linger", "2").use { server ->
            val connection = connect(server, speaker)
            try {
                val serverId = field(server.all.first { it.startsWith("READY") }, "server_id")
                server.await("PAIRED")
                server.await("PLAYER_READY")
                assertThat(speaker.records.map { it.serverId }).containsExactly(serverId)
                assertThat(speaker.events).containsAtLeast("paired", "used $serverId", "player true").inOrder()
                assertThat(connection.link.value).isEqualTo(SpeakerLink.CONNECTED)
            } finally {
                connection.stop("shutdown")
            }
            assertThat(server.await("DONE")).contains("exit_code=0")
        }
        scope.cancel()
    }
}
