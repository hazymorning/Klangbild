package io.github.hazymorning.klangbild.sound

/**
 * The music this TV plays, as a sound feed with exact times: each block on its way to the output
 * ([played], from the audio thread) is mixed to mono, and every [HOP] samples the last
 * [SoundAnalyzer.WINDOW] are measured and stamped with when the middle of that stretch is heard.
 * Beats come from the server ([beat]). It measures only while someone reads it, and allocates
 * nothing per block.
 */
class PcmSound(rate: Int) : SoundFeed {
    private val analyzer = SoundAnalyzer(rate)
    private val window = SoundAnalyzer.WINDOW
    private val ring = FloatArray(window)
    private val linear = FloatArray(window)
    private val frame = SoundFrame().apply { timing = SoundTiming.EXACT }
    private val timeline = SoundTimeline()
    private val frameUs = 1_000_000.0 / rate
    private var written = 0
    private var sinceLast = 0

    @Volatile private var readers = 0
    @Volatile private var restart = true

    override fun read(nowUs: Long, out: SoundFrame): Boolean = timeline.read(nowUs, out)

    @Synchronized
    override fun reading(on: Boolean) {
        readers = (readers + if (on) 1 else -1).coerceAtLeast(0)
        if (readers == 0) {
            timeline.clear()
            restart = true
        }
    }

    /**
     * [frames] stereo frames of [pcm] (before the volume), the first heard at [heardUs]; called
     * on the audio thread for every block it writes.
     */
    fun played(pcm: ShortArray, frames: Int, heardUs: Long) {
        if (readers == 0) return
        if (restart) {
            restart = false
            written = 0
            sinceLast = 0
            analyzer.reset()
        }
        for (i in 0 until frames) {
            ring[written % window] = (pcm[2 * i] + pcm[2 * i + 1]) / STEREO_SCALE
            written++
            if (written >= window && ++sinceLast >= HOP) {
                sinceLast = 0
                val start = written % window
                System.arraycopy(ring, start, linear, 0, window - start)
                System.arraycopy(ring, 0, linear, window - start, start)
                analyzer.analyze(linear, 0, frame)
                // The window ends after sample i; its middle is heard half a window earlier.
                frame.timeUs = heardUs + ((i + 1 - window / 2) * frameUs).toLong()
                timeline.push(frame)
            }
        }
        // A whole number of windows later the count starts over, so it never runs out.
        if (written >= window * WRAP) written -= window * (WRAP - 1)
    }

    /** A beat the server knows, heard at [heardUs]. */
    fun beat(heardUs: Long, downbeat: Boolean) {
        if (readers > 0) timeline.beat(heardUs, downbeat)
    }

    /** The server dropped the beats to come. */
    fun beatsCleared() = timeline.clearBeats()

    /** The music jumped or ended: what was measured for later no longer comes. */
    fun cleared() {
        timeline.clear()
        restart = true
    }

    companion object {
        /** A new stretch every 512 samples: about 94 a second, each overlapping the one before by half. */
        const val HOP = 512

        /** Two full-scale 16-bit channels summed, as -1 to 1. */
        private const val STEREO_SCALE = 65_536f

        private const val WRAP = 1_024
    }
}
