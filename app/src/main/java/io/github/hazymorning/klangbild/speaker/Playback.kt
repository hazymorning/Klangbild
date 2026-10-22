package io.github.hazymorning.klangbild.speaker

import io.github.hazymorning.klangbild.sendspin.ClockMap
import kotlin.math.abs
import kotlin.math.pow

/**
 * Plays the queued audio at the moments the server meant, through [sink], one block at a time.
 *
 * For every block it works out when its first frame will leave the device (from the output's
 * last timestamp and the frames written since), which server time should sound then (the
 * clock's mapping, the output delay added), and how far the queued audio is from that. A start,
 * a clear, an underrun or a large error is put right at once, with silence or by dropping the
 * frames that are late; a small one is worked off a frame at a time, as the player role
 * suggests: one frame dropped or repeated per block at most, which is inaudible and stays well
 * within half a percent of speed.
 *
 * The output's timestamps are trusted only once a few have moved on in a row, never one from
 * before the output started (Android 9 reports those), never one that claims more frames than
 * were written, and not one that has stood still while frames went out (a stalled output).
 * Frame counts are compared in 32 bits, as the output keeps them.
 *
 * [step] is the whole of it and runs on the audio thread; nothing there allocates. The other
 * methods may be called from any thread.
 */
class Playback(
    private val sink: AudioSink,
    val queue: ChunkQueue,
    /** The local clock (monotonic, the clock of the output's timestamps). */
    private val monotonic: MonoClock,
) {
    private val rate = sink.rate
    private val block = ShortArray(BLOCK * 2)
    private val ts = SinkTimestamp()

    /** The server's clock as the time filter has it; null until it has settled. */
    @Volatile var clock: ClockMap? = null

    /** How long the sound takes from the device's port to the ear (the TV, a sound bar), in microseconds. */
    @Volatile var outputDelayUs = 0L

    @Volatile private var targetGain = 1f
    @Volatile private var volume = 100
    @Volatile private var muted = false
    @Volatile private var silenced = false
    private var gain = 1f
    private var rampTarget = 1f
    private var rampStep = 0f

    /** What the analysis gets: each block before the volume, and when it is heard. */
    @Volatile var tap: PcmTap? = null

    @Volatile private var flushWanted = false
    @Volatile private var resyncWanted = false
    private var realign = true
    private var open = false
    private var trustFromUs = 0L
    private var written = 0L
    private var lastTsFrame = 0L
    private var lastTsTimeUs = 0L
    private var lastAdvanceUs = 0L
    private var advancing = 0
    private var hasTimestamp = false
    private var underruns = 0
    private var error = 0.0
    private var errorKnown = false

    /** Frames dropped or repeated so far to keep in time. */
    @Volatile var corrections = 0L
        private set

    /** Times the audio was put in place at once (a start, a clear, an underrun, a large error). */
    @Volatile var resyncs = 0L
        private set

    /** Sets the volume (0 to 100) and mute; the change is ramped over [RAMP_FRAMES]. */
    fun setVolume(volume: Int, muted: Boolean) {
        this.volume = volume
        this.muted = muted
        targetGain = gainNow()
    }

    /**
     * Silences the output here, whatever the server says (another app has the TV's sound for a
     * moment); the audio goes on in time underneath, so it comes back where it belongs.
     */
    fun silence(silent: Boolean) {
        silenced = silent
        targetGain = gainNow()
    }

    private fun gainNow(): Float = if (muted || silenced) 0f else gainOf(volume)

    /**
     * Everything queued goes, and so does what the output still holds (a clear, the stream's
     * end): what comes next is put in place anew.
     */
    fun clear() {
        queue.clear()
        flushWanted = true
    }

    /** The output's timing can no longer be trusted (another output, the device woke up): put in place anew. */
    fun resync() {
        resyncWanted = true
    }

    /** Opens the output; false when it cannot be opened. */
    fun open(): Boolean {
        if (open) return true
        if (!sink.open()) return false
        open = true
        written = 0
        underruns = sink.underruns()
        forgetTimestamps()
        realign = true
        gain = targetGain
        rampTarget = gain
        return true
    }

    fun close() {
        if (!open) return
        open = false
        sink.close()
    }

    /** Writes one block (blocking while the output is full); false when the output failed. */
    fun step(): Boolean {
        if (!open) return false
        if (flushWanted) {
            flushWanted = false
            sink.flush()
            written = 0
            forgetTimestamps()
            realign = true
        }
        if (resyncWanted) {
            resyncWanted = false
            forgetTimestamps()
            realign = true
        }
        val u = sink.underruns()
        if (u != underruns) {
            // The output ran dry: what it plays from here is put in place anew.
            underruns = u
            realign = true
        }
        val start = presentationUs()
        val c = clock
        var filled = 0
        if (start != NONE && c != null) filled = fill(c.serverTime(start + outputDelayUs))
        if (filled < BLOCK) block.fill(0, filled * 2, BLOCK * 2)
        if (start != NONE) tap?.played(block, BLOCK, start + outputDelayUs)
        applyGain()
        val n = sink.write(block, BLOCK)
        if (n < 0) return false
        written += n
        return true
    }

    private fun forgetTimestamps() {
        hasTimestamp = false
        advancing = 0
        lastTsFrame = 0
        trustFromUs = monotonic.nowUs()
    }

    /** When the next frame written will leave the device, by the output's timestamps that can be trusted; [NONE] while there are none. */
    private fun presentationUs(): Long {
        val now = monotonic.nowUs()
        if (sink.timestamp(ts) && ts.timeUs >= trustFromUs && ts.frame > 0) {
            val moved = (ts.frame - lastTsFrame).toInt()
            val pending = (written - ts.frame).toInt()
            when {
                pending < 0 || pending > sink.bufferFrames + rate -> {
                    // A timestamp of frames that were never written, or long gone: not this output's now.
                    advancing = 0
                }
                advancing > 0 && moved < 0 -> {
                    // Back in time: the output started again underneath.
                    forgetTimestamps()
                    realign = true
                }
                moved > 0 || advancing == 0 -> {
                    lastTsFrame = ts.frame
                    lastTsTimeUs = ts.timeUs
                    lastAdvanceUs = now
                    if (++advancing >= TRUSTED_AFTER) hasTimestamp = true
                }
            }
        }
        if (hasTimestamp && now - lastAdvanceUs > STALL_US) {
            // The output stopped presenting: what comes after it is put in place anew.
            forgetTimestamps()
            realign = true
        }
        if (!hasTimestamp) return NONE
        return lastTsTimeUs + (written - lastTsFrame).toInt() * 1_000_000L / rate
    }

    /** Fills the block from the queue so that its first frame is the audio for server time [target]; frames filled. */
    private fun fill(target: Long): Int {
        var filled = 0
        if (realign) {
            realign = false
            errorKnown = false
            // Late audio goes; audio still to come waits behind silence.
            while (true) {
                val t = queue.nextTimeUs(rate)
                if (t == ChunkQueue.NONE) break
                val late = framesOf(target - t)
                if (late <= 0) break
                queue.skip(late.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            val t = queue.nextTimeUs(rate)
            if (t == ChunkQueue.NONE) {
                realign = true
                return 0
            }
            val early = framesOf(t - target)
            if (early >= BLOCK) {
                realign = true
                return 0
            }
            filled = early.toInt().coerceAtLeast(0)
            block.fill(0, 0, filled * 2)
            resyncs++
        } else {
            val t = queue.nextTimeUs(rate)
            if (t == ChunkQueue.NONE) {
                realign = true
                return 0
            }
            val e = (t - target).toDouble()
            if (!errorKnown) {
                error = e
                errorKnown = true
            } else {
                error += (e - error) * ERROR_SMOOTHING
            }
            if (abs(error) > RESYNC_US) {
                realign = true
                return fill(target)
            }
            val frameUs = 1_000_000.0 / rate
            if (error > DEADBAND_US) {
                // Early: one frame twice, so the audio waits a frame.
                val n = queue.take(block, 0, 1)
                if (n == 1) {
                    block[2] = block[0]
                    block[3] = block[1]
                    filled = 2
                    error -= frameUs
                    corrections++
                }
            } else if (error < -DEADBAND_US) {
                // Late: one frame left out, so the audio catches up a frame.
                if (queue.skip(1) == 1) {
                    error += frameUs
                    corrections++
                }
            }
        }
        while (filled < BLOCK) {
            val before = queue.nextTimeUs(rate)
            if (before == ChunkQueue.NONE) break
            val n = queue.take(block, filled, BLOCK - filled)
            if (n == 0) break
            filled += n
            // A gap between chunks (a chunk that never came) is put right by aligning anew.
            val next = queue.nextTimeUs(rate)
            if (next == ChunkQueue.NONE) break
            if (abs(next - (before + n * 1_000_000L / rate)) > GAP_US) {
                realign = true
                break
            }
        }
        if (filled < BLOCK && queue.nextTimeUs(rate) == ChunkQueue.NONE) realign = true
        return filled
    }

    private fun framesOf(us: Long): Long = us * rate / 1_000_000L

    /** The volume, ramped at an even pace from where it stands to a new value over [RAMP_FRAMES]. */
    private fun applyGain() {
        val target = targetGain
        if (target != rampTarget) {
            rampTarget = target
            rampStep = (target - gain) / RAMP_FRAMES
        }
        if (gain == target && gain == 1f) return
        val step = rampStep
        var g = gain
        for (i in 0 until BLOCK) {
            if (g != target) {
                g += step
                if ((step > 0 && g > target) || (step < 0 && g < target)) g = target
            }
            block[2 * i] = (block[2 * i] * g).toInt().toShort()
            block[2 * i + 1] = (block[2 * i + 1] * g).toInt().toShort()
        }
        gain = g
    }

    /** Receives each block written, before the volume is applied: [frames] stereo frames heard from [heardUs] on. */
    fun interface PcmTap {
        fun played(pcm: ShortArray, frames: Int, heardUs: Long)
    }

    companion object {
        /** No time known. */
        private const val NONE = Long.MIN_VALUE

        /** 10 ms at 48 kHz: the unit of writing, and of correcting. */
        const val BLOCK = 480

        /** A volume change is ramped over 20 ms, so it never clicks. */
        const val RAMP_FRAMES = 960f

        /** An error this small is left alone. */
        const val DEADBAND_US = 100.0

        /** An error this large is put right at once (rarely: after a disturbance). */
        const val RESYNC_US = 1_500.0

        /** Successive chunks further apart than this have a gap between them. */
        const val GAP_US = 1_000L

        /** How quickly the measured error follows each new measurement. */
        const val ERROR_SMOOTHING = 0.05

        /** A timestamp that stands still this long while frames are written means the output stalled. */
        const val STALL_US = 500_000L

        /** Timestamps are trusted once this many have moved on in a row. */
        const val TRUSTED_AFTER = 3

        /** Sendspin's volume curve: 50 sounds half as loud as 100. */
        fun gainOf(volume: Int): Float = (volume.coerceIn(0, 100) / 100.0).pow(1.5).toFloat()
    }
}
