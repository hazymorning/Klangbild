package io.github.hazymorning.klangbild.sound

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SoundTimelineTest {
    private val timeline = SoundTimeline(capacity = 8, beatCapacity = 4)
    private val frame = SoundFrame()

    private fun push(timeUs: Long, loudness: Float) {
        frame.timeUs = timeUs
        frame.loudness = loudness
        timeline.push(frame)
    }

    @Test
    fun `a moment reads the stretch that began last before it`() {
        push(1_000, 0.1f)
        push(11_000, 0.2f)
        push(21_000, 0.3f)
        val out = SoundFrame()
        assertThat(timeline.read(500, out)).isFalse()
        assertThat(timeline.read(15_000, out)).isTrue()
        assertThat(out.loudness).isEqualTo(0.2f)
        assertThat(timeline.read(21_000, out)).isTrue()
        assertThat(out.loudness).isEqualTo(0.3f)
    }

    @Test
    fun `long after the last stretch nothing is heard`() {
        push(1_000, 0.5f)
        assertThat(timeline.read(1_000 + SoundTimeline.STALE_US + 1, SoundFrame())).isFalse()
    }

    @Test
    fun `a stretch from earlier replaces what came after it`() {
        push(10_000, 0.1f)
        push(20_000, 0.2f)
        push(30_000, 0.3f)
        push(15_000, 0.9f)
        val out = SoundFrame()
        timeline.read(25_000, out)
        assertThat(out.loudness).isEqualTo(0.9f)
    }

    @Test
    fun `when full the oldest goes`() {
        for (i in 0 until 12) push(i * 10_000L, i / 100f)
        val out = SoundFrame()
        assertThat(timeline.read(35_000, out)).isFalse()
        assertThat(timeline.read(45_000, out)).isTrue()
        assertThat(out.loudness).isEqualTo(0.04f)
    }

    @Test
    fun `beats are read by the moment they are heard`() {
        push(0, 0.5f)
        push(100_000, 0.5f)
        timeline.beat(40_000, downbeat = true)
        timeline.beat(90_000, downbeat = false)
        val out = SoundFrame()
        timeline.read(50_000, out)
        assertThat(out.beatUs).isEqualTo(40_000)
        assertThat(out.downbeat).isTrue()
        timeline.read(120_000, out)
        assertThat(out.beatUs).isEqualTo(90_000)
        timeline.read(20_000, out)
        assertThat(out.beatUs).isEqualTo(SoundFrame.NO_BEAT)
    }

    @Test
    fun `the beats to come can go on their own, and a clear forgets everything`() {
        push(0, 0.1f)
        push(50_000, 0.2f)
        timeline.beat(40_000, downbeat = false)
        timeline.clearBeats()
        val out = SoundFrame()
        assertThat(timeline.read(70_000, out)).isTrue()
        assertThat(out.loudness).isEqualTo(0.2f)
        assertThat(out.beatUs).isEqualTo(SoundFrame.NO_BEAT)
        timeline.clear()
        assertThat(timeline.read(70_000, out)).isFalse()
        // Nothing heard reads as silence, with nothing known about its time.
        assertThat(out.loudness).isEqualTo(0f)
        assertThat(out.timing).isEqualTo(SoundTiming.NONE)
    }
}
