package io.github.hazymorning.klangbild.sound

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoundAnalyzerTest {
    private val rate = 48_000
    private val n = SoundAnalyzer.WINDOW

    private fun sine(freq: Double, amplitude: Double, from: Int = 0) =
        FloatArray(n) { (amplitude * sin(2 * PI * freq * (from + it) / rate)).toFloat() }

    @Test
    fun `a full-scale tone at 1 kHz is as loud as the scale goes`() {
        val frame = SoundFrame()
        SoundAnalyzer(rate).analyze(sine(1_000.0, 1.0), 0, frame)
        assertThat(frame.loudness).isWithin(0.02f).of(1f)
    }

    @Test
    fun `20 dB less reads a third lower`() {
        val frame = SoundFrame()
        SoundAnalyzer(rate).analyze(sine(1_000.0, 0.1), 0, frame)
        assertThat(frame.loudness).isWithin(0.02f).of(40f / 60f)
    }

    @Test
    fun `a deep tone counts for little in loudness but fills the bass`() {
        val frame = SoundFrame()
        SoundAnalyzer(rate).analyze(sine(60.0, 1.0), 0, frame)
        // A-weighting takes about 26 dB off at 60 Hz.
        assertThat(frame.loudness).isLessThan(0.65f)
        assertThat(frame.bands[0]).isGreaterThan(0.9f)
        assertThat(frame.bands[2]).isLessThan(0.3f)
    }

    @Test
    fun `each tone lands in its band`() {
        val tones = listOf(80.0, 300.0, 1_200.0, 5_000.0)
        tones.forEachIndexed { b, f ->
            val frame = SoundFrame()
            SoundAnalyzer(rate).analyze(sine(f, 0.5), 0, frame)
            val strongest = frame.bands.indices.maxBy { frame.bands[it] }
            assertThat(strongest).isEqualTo(b)
        }
    }

    @Test
    fun `silence is zero everywhere`() {
        val frame = SoundFrame()
        SoundAnalyzer(rate).analyze(FloatArray(n), 0, frame)
        assertThat(frame.loudness).isEqualTo(0f)
        assertThat(frame.bands.toList()).containsExactly(0f, 0f, 0f, 0f)
        assertThat(frame.onset).isEqualTo(0f)
    }

    @Test
    fun `a hit after quiet music sets in sharply, the steady tone around it does not`() {
        val analyzer = SoundAnalyzer(rate)
        val frame = SoundFrame()
        val onsets = (0 until 40).map { i ->
            val window = sine(220.0, 0.05, i * n)
            if (i == 30) for (k in 0 until n) window[k] += if (k % 2 == 0) 0.8f else -0.8f
            analyzer.analyze(window, 0, frame)
            frame.onset
        }
        assertThat(onsets[30]).isGreaterThan(0.9f)
        assertThat(onsets.subList(5, 30).max()).isLessThan(0.2f)
    }

    @Test
    fun `A-weighting passes 1 kHz unchanged and takes much off the deep end`() {
        assertThat(SoundAnalyzer.aWeighting(1_000.0)).isWithin(0.01f).of(1f)
        // -19.1 dB at 100 Hz (IEC 61672).
        assertThat(10 * kotlin.math.log10(SoundAnalyzer.aWeighting(100.0))).isWithin(0.2f).of(-19.1f)
    }
}
