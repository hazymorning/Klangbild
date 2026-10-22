package io.github.hazymorning.klangbild.speaker

/**
 * The audio received and not yet played: PCM frames (16-bit stereo) in one ring, each chunk
 * with the server time of its first frame. One thread adds (the connection), one takes (the
 * audio thread); neither waits for the other or allocates. Clearing is asked for by the adding
 * side and carried out by the taking side, which skips everything added before the request.
 */
class ChunkQueue(capacityFrames: Int, private val maxChunks: Int = MAX_CHUNKS) {
    private val pcm = ShortArray(capacityFrames * 2)
    private val ringFrames = capacityFrames.toLong()
    private val chunkTime = LongArray(maxChunks)
    private val chunkStart = LongArray(maxChunks)
    private val chunkFrames = IntArray(maxChunks)

    /** Chunks and frames added so far (the adding side writes these). */
    @Volatile private var chunkHead = 0L
    @Volatile private var frameHead = 0L

    /** Chunks taken whole and frames taken so far (the taking side writes these). */
    @Volatile private var chunkTail = 0L
    @Volatile private var frameTail = 0L

    /** Every chunk added before this one is to be skipped. */
    @Volatile private var clearedBefore = 0L

    /** The taking side's place within the chunk at [chunkTail]. */
    private var offsetInChunk = 0

    /** Chunks that did not fit and were left out. */
    @Volatile var overflows = 0
        private set

    /**
     * Adding side: a chunk of [frames] stereo frames in little-endian 16-bit PCM at [offset] of
     * [bytes], to be played from server time [serverUs]. False when it does not fit.
     */
    fun add(serverUs: Long, bytes: ByteArray, offset: Int, frames: Int): Boolean {
        if (frames <= 0) return true
        if (chunkHead - chunkTail >= maxChunks || frameHead + frames - frameTail > ringFrames) {
            overflows++
            return false
        }
        val slot = (chunkHead % maxChunks).toInt()
        chunkTime[slot] = serverUs
        chunkStart[slot] = frameHead
        chunkFrames[slot] = frames
        var at = ((frameHead % ringFrames) * 2).toInt()
        var b = offset
        repeat(frames * 2) {
            pcm[at] = ((bytes[b].toInt() and 0xff) or (bytes[b + 1].toInt() shl 8)).toShort()
            b += 2
            at++
            if (at == pcm.size) at = 0
        }
        frameHead += frames
        chunkHead++
        return true
    }

    /** Adding side: everything added so far is to go. */
    fun clear() {
        clearedBefore = chunkHead
    }

    /**
     * Taking side: the server time of the next frame to take, or [NONE] when nothing waits.
     * Carries out a clear asked for meanwhile.
     */
    fun nextTimeUs(rate: Int): Long {
        skipCleared()
        if (chunkTail == chunkHead) return NONE
        val slot = (chunkTail % maxChunks).toInt()
        return chunkTime[slot] + offsetInChunk * 1_000_000L / rate
    }

    /** Taking side: frames left in the chunk at hand (0 when nothing waits). */
    fun framesInChunk(): Int {
        skipCleared()
        if (chunkTail == chunkHead) return 0
        return chunkFrames[(chunkTail % maxChunks).toInt()] - offsetInChunk
    }

    /** Taking side: copies up to [frames] frames of the chunk at hand into [out] from frame [at]; how many it copied. */
    fun take(out: ShortArray, at: Int, frames: Int): Int {
        val n = minOf(frames, framesInChunk())
        if (n == 0) return 0
        val slot = (chunkTail % maxChunks).toInt()
        var from = (((chunkStart[slot] + offsetInChunk) % ringFrames) * 2).toInt()
        var to = at * 2
        repeat(n * 2) {
            out[to++] = pcm[from++]
            if (from == pcm.size) from = 0
        }
        advance(slot, n)
        return n
    }

    /** Taking side: lets go of up to [frames] frames of the chunk at hand without copying them; how many. */
    fun skip(frames: Int): Int {
        val n = minOf(frames, framesInChunk())
        if (n > 0) advance((chunkTail % maxChunks).toInt(), n)
        return n
    }

    private fun advance(slot: Int, n: Int) {
        offsetInChunk += n
        if (offsetInChunk == chunkFrames[slot]) {
            frameTail = chunkStart[slot] + chunkFrames[slot]
            offsetInChunk = 0
            chunkTail++
        }
    }

    private fun skipCleared() {
        val until = clearedBefore
        if (chunkTail >= until) return
        val last = ((until - 1) % maxChunks).toInt()
        frameTail = chunkStart[last] + chunkFrames[last]
        chunkTail = until
        offsetInChunk = 0
    }

    companion object {
        /** Music Assistant sends chunks of 15 to 150 ms; this many cover the whole ring at the shortest. */
        const val MAX_CHUNKS = 2_048

        /** No time: nothing waits. */
        const val NONE = Long.MIN_VALUE
    }
}
