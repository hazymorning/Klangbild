package io.github.hazymorning.klangbild.sound

/**
 * What the music does at each moment, for whoever shows it: the stretch heard now, the latest
 * beat, and how exactly their time is known ([SoundFrame.timing]). A feed measures and listens
 * only while someone reads it ([reading]).
 */
interface SoundFeed {
    /**
     * Fills [out] with what is heard at [nowUs] (microseconds on the monotonic clock,
     * `System.nanoTime() / 1000`); false, and [out] silent with no timing, while nothing is.
     */
    fun read(nowUs: Long, out: SoundFrame): Boolean

    /** Someone starts reading the feed ([on]), or stops. */
    fun reading(on: Boolean)

    /** Nothing is known about the music that plays. */
    object None : SoundFeed {
        override fun read(nowUs: Long, out: SoundFrame): Boolean {
            out.silence()
            return false
        }

        override fun reading(on: Boolean) = Unit
    }
}
