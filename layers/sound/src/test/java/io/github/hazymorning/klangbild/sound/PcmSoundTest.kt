package io.github.hazymorning.klangbild.sound

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** This TV's own playback as a sound feed: measured as it is written, timed by when it is heard. */
class PcmSoundTest {
    private val rate = 48_000
    private val block = 480

    /** [blocks] blocks of a stereo tone at 1 kHz, the first heard at [startUs], 10 ms apart, [from] blocks into it. */
    private fun PcmSound.play(blocks: Int, startUs: Long, amplitude: (Int) -> Double, from: Int = 0) {
        val pcm = ShortArray(block * 2)
        for (b in 0 until blocks) {
            for (i in 0 until block) {
                val n = (from + b) * block + i
                val v = (amplitude(n) * 32_767 * sin(2 * PI * 1_000 * n / rate)).roundToInt().toShort()
                pcm[2 * i] = v
                pcm[2 * i + 1] = v
            }
            played(pcm, block, startUs + b * 10_000L)
        }
    }

    @Test
    fun `nothing is measured while nobody reads`() {
        val sound = PcmSound(rate)
        sound.play(100, 1_000_000L, { 1.0 })
        val out = SoundFrame()
        assertThat(sound.read(1_500_000L, out)).isFalse()
        assertThat(out.timing).isEqualTo(SoundTiming.NONE)
    }

    @Test
    fun `what is read is the stretch heard at that moment, exactly timed`() {
        val sound = PcmSound(rate)
        sound.reading(true)
        // A second of quiet, then a second at full scale, heard from 1 s on.
        sound.play(200, 1_000_000L, { n -> if (n < rate) 0.01 else 1.0 })
        val out = SoundFrame()
        assertThat(sound.read(1_500_000L, out)).isTrue()
        assertThat(out.timing).isEqualTo(SoundTiming.EXACT)
        assertThat(out.loudness).isWithin(0.03f).of(20f / 60f)
        assertThat(out.timeUs).isAtMost(1_500_000L)
        assertThat(1_500_000L - out.timeUs).isLessThan(11_000L)
        assertThat(sound.read(2_500_000L, out)).isTrue()
        assertThat(out.loudness).isWithin(0.03f).of(1f)
        // The step is heard at 2 s: just before, the stretch around it is still mostly quiet.
        sound.read(1_990_000L, out)
        assertThat(out.loudness).isLessThan(0.9f)
        // After the music, nothing.
        assertThat(sound.read(3_500_000L, out)).isFalse()
    }

    @Test
    fun `beats are kept while read, and a clear drops what was measured ahead`() {
        val sound = PcmSound(rate)
        sound.beat(1_100_000L, downbeat = true)
        sound.reading(true)
        sound.play(100, 1_000_000L, { 0.5 })
        sound.beat(1_200_000L, downbeat = false)
        val out = SoundFrame()
        sound.read(1_250_000L, out)
        assertThat(out.beatUs).isEqualTo(1_200_000L)
        assertThat(out.downbeat).isFalse()
        sound.cleared()
        assertThat(sound.read(1_500_000L, out)).isFalse()
        sound.reading(false)
        sound.play(100, 3_000_000L, { 0.5 }, from = 200)
        assertThat(sound.read(3_500_000L, out)).isFalse()
    }
}
