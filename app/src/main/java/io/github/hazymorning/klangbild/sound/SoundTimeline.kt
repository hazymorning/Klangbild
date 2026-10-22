package io.github.hazymorning.klangbild.sound

/**
 * The stretches and beats of one source in the order they are heard, measured ahead of time and
 * read as they come due. One writer (the source), any number of readers; every frame lives in
 * a slot made once, so neither writing nor reading allocates.
 */
class SoundTimeline(capacity: Int = CAPACITY, private val beatCapacity: Int = BEATS) {
    private val frames = Array(capacity) { SoundFrame() }
    private var start = 0
    private var count = 0
    private val beatTimes = LongArray(beatCapacity)
    private val beatDown = BooleanArray(beatCapacity)
    private var beatStart = 0
    private var beatCount = 0

    /**
     * Adds a copy of [frame]. One heard no later than the newest so far replaces what comes from
     * then on, as after a jump; when full, the oldest goes.
     */
    @Synchronized
    fun push(frame: SoundFrame) {
        while (count > 0 && frames[index(count - 1)].timeUs >= frame.timeUs) count--
        if (count == frames.size) {
            start = (start + 1) % frames.size
            count--
        }
        frames[index(count)].set(frame)
        count++
    }

    /** Adds a beat heard at [timeUs], replacing any later ones (they belonged to music that no longer comes). */
    @Synchronized
    fun beat(timeUs: Long, downbeat: Boolean) {
        while (beatCount > 0 && beatTimes[beatIndex(beatCount - 1)] >= timeUs) beatCount--
        if (beatCount == beatCapacity) {
            beatStart = (beatStart + 1) % beatCapacity
            beatCount--
        }
        val i = beatIndex(beatCount)
        beatTimes[i] = timeUs
        beatDown[i] = downbeat
        beatCount++
    }

    /** Forgets the beats to come (the server has dropped them), keeping the stretches. */
    @Synchronized
    fun clearBeats() {
        beatCount = 0
    }

    @Synchronized
    fun clear() {
        count = 0
        beatCount = 0
    }

    /**
     * Fills [out] with the stretch heard at [nowUs] and the latest beat by then. False when
     * nothing is heard then: no stretch began at most [STALE_US] before it.
     */
    @Synchronized
    fun read(nowUs: Long, out: SoundFrame): Boolean {
        var found = -1
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (frames[index(mid)].timeUs <= nowUs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0 || nowUs - frames[index(found)].timeUs > STALE_US) {
            out.silence()
            return false
        }
        out.set(frames[index(found)])
        out.beatUs = SoundFrame.NO_BEAT
        out.downbeat = false
        for (b in beatCount - 1 downTo 0) {
            val i = beatIndex(b)
            if (beatTimes[i] <= nowUs) {
                out.beatUs = beatTimes[i]
                out.downbeat = beatDown[i]
                break
            }
        }
        return true
    }

    private fun index(i: Int) = (start + i) % frames.size

    private fun beatIndex(i: Int) = (beatStart + i) % beatCapacity

    companion object {
        /** Room for several seconds ahead of time, as Music Assistant sends them. */
        const val CAPACITY = 1_024
        const val BEATS = 64

        /** A stretch older than this says nothing about now: the music has stopped. */
        const val STALE_US = 200_000L
    }
}
