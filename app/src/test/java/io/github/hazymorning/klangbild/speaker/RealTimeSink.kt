package io.github.hazymorning.klangbild.speaker

import java.util.concurrent.locks.LockSupport

/**
 * An output on the real clock for the end-to-end tests: frames leave at exactly 48 kHz of
 * `System.nanoTime()` once it opens, writing blocks until there is room, and every frame that
 * carries the reference server's counter signal is noted with the moment it leaves, in runs
 * of frames that follow each other.
 */
class RealTimeSink : AudioSink {
    override val rate = 48_000
    override val bufferFrames = rate / 5
    private var origin = 0L
    private var written = 0L
    private var lastCounter = -1
    private var epoch = 0
    private val runs = ArrayList<Run>()

    /** Frames written that were not silence. */
    @Volatile var musicFrames = 0L
        private set

    /** A run of frames that left in order: the counter of the first, how many, when the first left (µs). */
    class Run(val counter: Int, var frames: Int, val atUs: Long, val startFrame: Long, val epoch: Int)

    /** The runs so far. */
    fun runs(): List<Run> = synchronized(runs) { runs.toList() }

    private fun nowUs() = System.nanoTime() / 1_000

    private fun leaves(frame: Long) = origin + frame * 1_000_000 / rate

    private fun presentedBy(t: Long) = if (t < origin) 0 else (t - origin) * rate / 1_000_000

    override fun open(): Boolean {
        origin = nowUs() + 20_000
        written = 0
        return true
    }

    override fun write(pcm: ShortArray, frames: Int): Int {
        while (written + frames - presentedBy(nowUs()) > bufferFrames) LockSupport.parkNanos(500_000)
        synchronized(runs) {
            for (i in 0 until frames) {
                if (pcm[2 * i] == 0.toShort() && pcm[2 * i + 1] == 0.toShort()) continue
                musicFrames++
                val left = pcm[2 * i].toInt() and 0xffff
                val last = runs.lastOrNull()
                if (last != null && last.epoch == epoch && left == (lastCounter + 1) and 0xffff && last.startFrame + last.frames == written + i) {
                    last.frames++
                } else {
                    runs += Run(left, 1, leaves(written + i), written + i, epoch)
                }
                lastCounter = left
            }
        }
        written += frames
        return frames
    }

    override fun timestamp(out: SinkTimestamp): Boolean {
        val now = nowUs()
        if (now < origin) return false
        out.frame = presentedBy(now)
        out.timeUs = leaves(out.frame)
        return out.frame > 0
    }

    override fun underruns() = 0

    override fun flush() {
        origin = nowUs() + 20_000
        written = 0
        epoch++
    }

    override fun close() = Unit
}
