package io.github.hazymorning.klangbild.speaker

/** Where a frame left the device, as the audio output last reported it. */
class SinkTimestamp {
    /** Frames written before the one that was presented (as the output counts them: only the low 32 bits hold). */
    var frame = 0L

    /** When it was presented, in microseconds on the monotonic clock. */
    var timeUs = 0L
}

/**
 * The audio output: 16-bit stereo frames at [rate], written in order. The AudioTrack on the TV,
 * a simulation in the tests.
 */
interface AudioSink {
    val rate: Int

    /** How many frames the output holds at most. */
    val bufferFrames: Int

    /** Opens the output and starts it; false when it cannot be opened. */
    fun open(): Boolean

    /** Writes [frames] frames from [pcm], blocking while the output's buffer is full; frames written, negative on an error. */
    fun write(pcm: ShortArray, frames: Int): Int

    /**
     * Where a frame was last presented ([out]), at the device's audio port. False while the
     * output has none to report.
     */
    fun timestamp(out: SinkTimestamp): Boolean

    /** How often the output has run dry since it opened. */
    fun underruns(): Int

    /** Drops what the output holds and starts again, counting frames from 0. */
    fun flush()

    /** Stops the output and lets it go. */
    fun close()
}
