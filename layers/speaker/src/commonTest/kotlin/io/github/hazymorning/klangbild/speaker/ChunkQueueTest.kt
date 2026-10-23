package io.github.hazymorning.klangbild.speaker

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class ChunkQueueTest {
    /** [frames] stereo frames whose left samples count up from [from]. */
    private fun pcm(from: Int, frames: Int) = ByteArray(frames * 4).also { b ->
        for (i in 0 until frames) {
            b[4 * i] = (from + i).toByte()
            b[4 * i + 1] = ((from + i) shr 8).toByte()
        }
    }

    @Test
    fun `chunks come out in order, across the ring's end`() {
        val q = ChunkQueue(capacityFrames = 100, maxChunks = 4)
        val out = ShortArray(200)
        assertThat(q.add(1_000, pcm(0, 60), 0, 60)).isTrue()
        assertThat(q.take(out, 0, 60)).isEqualTo(60)
        assertThat(q.add(2_000, pcm(60, 80), 0, 80)).isTrue()
        assertThat(q.nextTimeUs(48_000)).isEqualTo(2_000)
        assertThat(q.take(out, 0, 30)).isEqualTo(30)
        // 30 frames in: the next one plays 625 µs after the chunk's start.
        assertThat(q.nextTimeUs(48_000)).isEqualTo(2_625)
        assertThat(q.take(out, 30, 100)).isEqualTo(50)
        assertThat((0 until 80).map { out[2 * it].toInt() }).isEqualTo((60 until 140).toList())
        assertThat(q.nextTimeUs(48_000)).isEqualTo(ChunkQueue.NONE)
    }

    @Test
    fun `what does not fit is left out`() {
        val q = ChunkQueue(capacityFrames = 100, maxChunks = 2)
        assertThat(q.add(0, pcm(0, 80), 0, 80)).isTrue()
        assertThat(q.add(1, pcm(0, 30), 0, 30)).isFalse()
        assertThat(q.add(2, pcm(0, 10), 0, 10)).isTrue()
        assertThat(q.add(3, pcm(0, 5), 0, 5)).isFalse()
        assertThat(q.overflows).isEqualTo(2)
    }

    @Test
    fun `a clear drops what came before it, not what comes after`() {
        val q = ChunkQueue(capacityFrames = 100)
        q.add(0, pcm(0, 40), 0, 40)
        q.add(1_000, pcm(40, 40), 0, 40)
        q.clear()
        q.add(50_000, pcm(500, 20), 0, 20)
        assertThat(q.nextTimeUs(48_000)).isEqualTo(50_000)
        val out = ShortArray(40)
        assertThat(q.take(out, 0, 20)).isEqualTo(20)
        assertThat(out[0].toInt()).isEqualTo(500)
        // The room of what was cleared is free again.
        assertThat(q.add(60_000, pcm(0, 100), 0, 100)).isTrue()
    }

    @Test
    fun `skipping lets frames go without copying them`() {
        val q = ChunkQueue(capacityFrames = 100)
        q.add(0, pcm(0, 48), 0, 48)
        assertThat(q.skip(47)).isEqualTo(47)
        assertThat(q.framesInChunk()).isEqualTo(1)
        assertThat(q.nextTimeUs(48_000)).isEqualTo(979)
    }
}
