package io.github.hazymorning.klangbild.sound

/** How exactly the feed knows when what it says is heard. */
enum class SoundTiming {
    /** This TV plays the music: each moment's time is known to the millisecond. */
    EXACT,

    /** Another speaker plays it and Music Assistant says when: right to a fraction of a second. */
    CLOSE,

    /** Nothing is known about the music that plays. */
    NONE,
}

/**
 * One short stretch of music: when it is heard and what it does. A reader keeps one and has
 * it filled again and again, so reading allocates nothing.
 */
class SoundFrame {
    /** When it is heard, in microseconds on the monotonic clock (`System.nanoTime() / 1000`). */
    var timeUs = 0L

    /** A-weighted loudness, 0 (-60 dB or less) to 1 (full scale). */
    var loudness = 0f

    /** The strength of each band in [SoundAnalyzer.BANDS], on the same scale. */
    val bands = FloatArray(SoundAnalyzer.BANDS.size)

    /** How sharply something sets in, 0 to 1. */
    var onset = 0f

    /** When the latest beat was heard (microseconds as [timeUs]), or [NO_BEAT] when none is known. */
    var beatUs = NO_BEAT

    /** Whether that beat began a bar. */
    var downbeat = false

    /** How exact [timeUs] and [beatUs] are. */
    var timing = SoundTiming.NONE

    /** Nothing heard, nothing known. */
    fun silence() {
        loudness = 0f
        bands.fill(0f)
        onset = 0f
        beatUs = NO_BEAT
        downbeat = false
        timing = SoundTiming.NONE
    }

    fun set(from: SoundFrame) {
        timeUs = from.timeUs
        loudness = from.loudness
        from.bands.copyInto(bands)
        onset = from.onset
        beatUs = from.beatUs
        downbeat = from.downbeat
        timing = from.timing
    }

    companion object {
        const val NO_BEAT = Long.MIN_VALUE
    }
}
