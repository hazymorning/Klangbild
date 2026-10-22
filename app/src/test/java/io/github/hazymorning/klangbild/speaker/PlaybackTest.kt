package io.github.hazymorning.klangbild.speaker

import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.sendspin.ClockMap
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The playback against a simulated output on a simulated clock: an output whose crystal runs
 * [SimSink.ppm] fast or slow, a server clock [OFFSET_US] ahead, and audio whose every frame
 * carries its own number, so the test sees when each frame leaves the device and how far that
 * is from when the server meant it to.
 */
class PlaybackTest {
    private var now = 1_000_000L

    /** The output: frames leave at [ppm] off the nominal rate, [startUs] after opening, [bufferFrames] queued at most. */
    inner class SimSink(
        val ppm: Double = 0.0,
        private val startUs: Long = 60_000,
        override val bufferFrames: Int = 4_800,
        /** How far each timestamp is off, at most (the output's own jitter). */
        private val jitterUs: Long = 0,
    ) : AudioSink {
        override val rate = 48_000
        private val trueRate get() = rate * (1 + ppm / 1e6)
        private var openedAt = 0L
        var written = 0L
            private set

        /** Each frame written that carried audio: its number and when it left the device. */
        val presented = mutableListOf<Pair<Long, Long>>()

        /** The left sample of every frame written. */
        val levels = mutableListOf<Int>()
        private var count = 0
        var flushes = 0
            private set

        /** How often the output ran dry, as it reports it. */
        var underrunCount = 0

        /** Timestamps from before the output started, as Android 9 gives them at first. */
        var staleReads = 0

        /** While set, the timestamp stands still at what it last said. */
        var frozen = false
        private var frozenFrame = 0L
        private var frozenTime = 0L

        /** Added to the frames of every timestamp: the output's count jumping. */
        var frameShift = 0L

        private fun origin() = openedAt + startUs

        private fun presentedBy(t: Long): Long = if (t < origin()) 0 else floor((t - origin()) * trueRate / 1e6).toLong()

        fun leaves(frame: Long): Long = origin() + (frame * 1e6 / trueRate).toLong()

        override fun open(): Boolean {
            openedAt = now
            written = 0
            return true
        }

        override fun write(pcm: ShortArray, frames: Int): Int {
            // Blocks until the frames fit: the simulated clock moves on meanwhile.
            val needed = written + frames - bufferFrames
            if (presentedBy(now) < needed) now = maxOf(now, origin() + ceil(needed * 1e6 / trueRate).toLong())
            for (i in 0 until frames) {
                levels += pcm[2 * i].toInt()
                val n = ((pcm[2 * i].toLong() and 0x7fff) or ((pcm[2 * i + 1].toLong() and 0x7fff) shl 15))
                if (n != 0L) presented += n to leaves(written + i)
            }
            written += frames
            return frames
        }

        override fun timestamp(out: SinkTimestamp): Boolean {
            if (now < origin() + 20_000) return false
            if (staleReads > 0) {
                staleReads--
                out.frame = 1_000
                out.timeUs = openedAt - 500_000
                return true
            }
            if (frozen) {
                out.frame = frozenFrame
                out.timeUs = frozenTime
                return true
            }
            out.frame = presentedBy(now) + frameShift
            val jitter = if (jitterUs == 0L) 0 else ((count++ * 7919) % (2 * jitterUs + 1)) - jitterUs
            out.timeUs = leaves(out.frame - frameShift) + jitter
            frozenFrame = out.frame
            frozenTime = out.timeUs
            return true
        }

        override fun underruns() = underrunCount

        override fun flush() {
            // What the output held and had not played yet never leaves it.
            presented.removeAll { it.second > now }
            flushes++
            openedAt = now
            written = 0
        }

        override fun close() = Unit
    }

    /** The server: a stream from [startServerUs] in chunks of [chunkFrames], each frame numbered from 1. */
    private inner class Server(val queue: ChunkQueue, val startServerUs: Long, val chunkFrames: Int = 960) {
        var next = 0L
        var skipped: LongRange = LongRange.EMPTY
        var numberFrom = 1L

        /** Sends every chunk due within [aheadUs] of the server's now. */
        fun send(aheadUs: Long = 500_000) {
            while (true) {
                val ts = startServerUs + next * chunkFrames * 1_000_000L / 48_000
                if (ts > now + OFFSET_US + aheadUs) return
                if (next !in skipped) {
                    val bytes = ByteArray(chunkFrames * 4)
                    for (i in 0 until chunkFrames) {
                        val n = numberFrom + next * chunkFrames + i
                        val l = (n and 0x7fff).toInt()
                        val r = ((n shr 15) and 0x7fff).toInt()
                        bytes[4 * i] = l.toByte()
                        bytes[4 * i + 1] = (l shr 8).toByte()
                        bytes[4 * i + 2] = r.toByte()
                        bytes[4 * i + 3] = (r shr 8).toByte()
                    }
                    queue.add(ts, bytes, 0, chunkFrames)
                }
                next++
            }
        }

        /** When frame [n] (numbered from [numberFrom]) is meant to leave the device, on the local clock. */
        fun meantUs(n: Long, delayUs: Long): Long = startServerUs + ((n - numberFrom) * 1e6 / 48_000).toLong() - OFFSET_US - delayUs
    }

    private val clock = ClockMap(offset = OFFSET_US.toDouble(), drift = 0.0, lastUpdate = 0, errorUs = 100.0, count = 100)

    private fun playback(sink: SimSink): Playback {
        val p = Playback(sink, ChunkQueue(48_000 * 4), { now })
        p.clock = clock
        assertThat(p.open()).isTrue()
        return p
    }

    /** Runs the playback for [seconds] of simulated time, the server sending as it goes ([each] before every block). */
    private fun run(p: Playback, server: Server, seconds: Double, each: () -> Unit = {}) {
        val end = now + (seconds * 1e6).toLong()
        while (now < end) {
            each()
            server.send()
            check(p.step())
        }
    }

    private fun errorsAfter(sink: SimSink, server: Server, fromUs: Long, delayUs: Long = 0): List<Long> =
        sink.presented.filter { it.second >= fromUs }.map { (n, t) -> t - server.meantUs(n, delayUs) }

    @Test
    fun `the first frame leaves when the server meant it to`() {
        val sink = SimSink()
        val p = playback(sink)
        val server = Server(p.queue, startServerUs = now + OFFSET_US + 300_000)
        run(p, server, 1.0)
        val (first, at) = sink.presented.first()
        assertThat(first).isEqualTo(1L)
        assertThat(abs(at - server.meantUs(1, 0))).isAtMost(25L)
        assertThat(errorsAfter(sink, server, 0).maxOf { abs(it) }).isAtMost(25L)
    }

    @Test
    fun `an output 100 ppm fast or slow is held within a millisecond`() {
        for (ppm in listOf(100.0, -100.0)) {
            now = 1_000_000L
            val sink = SimSink(ppm = ppm, jitterUs = 150)
            val p = playback(sink)
            val server = Server(p.queue, startServerUs = now + OFFSET_US + 300_000)
            run(p, server, 120.0)
            val errors = errorsAfter(sink, server, 0)
            assertThat(errors.maxOf { abs(it) }).isLessThan(1_000L)
            // Drift is worked off frame by frame, never by putting it in place at once again.
            assertThat(p.resyncs).isEqualTo(1L)
            // 100 ppm over two minutes is 576 frames.
            assertThat(p.corrections).isAtLeast(500L)
        }
    }

    @Test
    fun `the output delay moves everything earlier`() {
        val sink = SimSink()
        val p = playback(sink)
        p.outputDelayUs = 150_000
        val server = Server(p.queue, startServerUs = now + OFFSET_US + 400_000)
        run(p, server, 2.0)
        assertThat(errorsAfter(sink, server, 0, delayUs = 150_000).maxOf { abs(it) }).isAtMost(25L)
    }

    @Test
    fun `audio that comes too late is dropped, the rest plays on time`() {
        val sink = SimSink()
        val p = playback(sink)
        // The stream began half a second ago: its first chunks are already late.
        val server = Server(p.queue, startServerUs = now + OFFSET_US - 500_000)
        run(p, server, 2.0)
        val (first, _) = sink.presented.first()
        assertThat(first).isGreaterThan(24_000L)
        assertThat(errorsAfter(sink, server, 0).maxOf { abs(it) }).isAtMost(25L)
    }

    @Test
    fun `after chunks that never came it plays on in time`() {
        val sink = SimSink()
        val p = playback(sink)
        val server = Server(p.queue, startServerUs = now + OFFSET_US + 300_000)
        // A quarter second missing two seconds in.
        server.skipped = 100L..112L
        run(p, server, 6.0)
        val missing = sink.presented.map { it.first }.filter { it in (100L * 960 + 1)..(113L * 960) }
        assertThat(missing).isEmpty()
        assertThat(errorsAfter(sink, server, 0).maxOf { abs(it) }).isAtMost(25L)
        assertThat(p.resyncs).isEqualTo(2L)
    }

    @Test
    fun `after a clear the output lets go of the old audio and the new is put in place`() {
        val sink = SimSink()
        val p = playback(sink)
        var server = Server(p.queue, startServerUs = now + OFFSET_US + 300_000)
        run(p, server, 2.0)
        // A seek: the server clears, then starts again 400 ms ahead with other audio.
        p.clear()
        val seekAt = now
        server = Server(p.queue, startServerUs = now + OFFSET_US + 400_000).also { it.numberFrom = 1_000_000 }
        run(p, server, 2.0)
        assertThat(sink.flushes).isEqualTo(1)
        // Nothing of the old audio leaves the device after the clear.
        assertThat(sink.presented.filter { it.first < 1_000_000 && it.second > seekAt }).isEmpty()
        val newOnes = sink.presented.filter { it.first >= 1_000_000 }
        assertThat(newOnes.first().first).isEqualTo(1_000_000L)
        assertThat(newOnes.maxOf { abs(it.second - server.meantUs(it.first, 0)) }).isAtMost(25L)
    }

    @Test
    fun `timestamps from before the start are not believed`() {
        val sink = SimSink().also { it.staleReads = 6 }
        val p = playback(sink)
        val server = Server(p.queue, startServerUs = now + OFFSET_US + 300_000)
        run(p, server, 2.0)
        assertThat(errorsAfter(sink, server, 0).maxOf { abs(it) }).isAtMost(25L)
    }

    @Test
    fun `after the output ran dry the audio is put in place again`() {
        val sink = SimSink()
        val p = playback(sink)
        val server = Server(p.queue, startServerUs = now + OFFSET_US + 300_000)
        run(p, server, 2.0)
        sink.underrunCount++
        run(p, server, 1.0)
        assertThat(p.resyncs).isEqualTo(2L)
        assertThat(errorsAfter(sink, server, 0).maxOf { abs(it) }).isAtMost(25L)
    }

    @Test
    fun `a stalled output is not followed, and is put in place again once it moves`() {
        val sink = SimSink()
        val p = playback(sink)
        val server = Server(p.queue, startServerUs = now + OFFSET_US + 300_000)
        run(p, server, 2.0)
        sink.frozen = true
        run(p, server, 1.0)
        sink.frozen = false
        val back = now
        run(p, server, 2.0)
        assertThat(p.resyncs).isAtLeast(2L)
        assertThat(errorsAfter(sink, server, back + 200_000).maxOf { abs(it) }).isAtMost(25L)
    }

    @Test
    fun `a count that jumps back is a new start`() {
        val sink = SimSink()
        val p = playback(sink)
        val server = Server(p.queue, startServerUs = now + OFFSET_US + 300_000)
        run(p, server, 2.0)
        val before = p.resyncs
        sink.frameShift = -2_400
        run(p, server, 0.5)
        assertThat(p.resyncs).isGreaterThan(before)
    }

    @Test
    fun `nothing plays before the output reports where it is`() {
        val sink = SimSink(startUs = 400_000)
        val p = playback(sink)
        val server = Server(p.queue, startServerUs = now + OFFSET_US + 100_000)
        run(p, server, 2.0)
        // The first 100 ms could not be placed in time and were dropped, the rest is on time.
        assertThat(errorsAfter(sink, server, 0).maxOf { abs(it) }).isAtMost(25L)
    }

    @Test
    fun `a volume change ramps instead of jumping`() {
        val sink = SimSink()
        val p = playback(sink)
        // A steady signal: every frame at 10 000 on both sides.
        val chunk = ByteArray(960 * 4) { if (it % 2 == 0) 0x10 else 0x27 }
        for (i in 0 until 200) p.queue.add(now + OFFSET_US + 200_000 + i * 20_000L, chunk, 0, 960)
        while (sink.levels.size < 48_000) check(p.step())
        p.setVolume(0, muted = false)
        repeat(10) { check(p.step()) }
        val after = sink.levels.drop(48_000)
        assertThat(after.first()).isGreaterThan(9_900)
        assertThat(after.last()).isEqualTo(0)
        // 10 000 down to nothing in 960 frames: about 10 a frame, never a jump.
        for (i in 1 until after.size) assertThat(after[i - 1] - after[i]).isIn(0..11)
    }

    @Test
    fun `the volume curve is Sendspin's`() {
        assertThat(Playback.gainOf(100)).isEqualTo(1f)
        assertThat(Playback.gainOf(0)).isEqualTo(0f)
        assertThat(Playback.gainOf(50)).isWithin(1e-4f).of(0.35355f)
    }

    private companion object {
        const val OFFSET_US = 5_000_000_000L
    }
}
