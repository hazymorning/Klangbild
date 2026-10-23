package io.github.hazymorning.klangbild.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * What a short stretch of music does, measured from its samples: how loud it is, how strong a
 * few bands are, and how sharply it sets in. One analyzer per source; it keeps the stretch
 * before for the onset, and allocates nothing per stretch.
 *
 * Levels are A-weighted where they stand for loudness and mapped as Sendspin's visualizer maps
 * them: -60 dB is 0, full scale (a full-scale sine) is 1, linear in between.
 */
class SoundAnalyzer(sampleRate: Int, val window: Int = WINDOW) {
    private val fft = Fft(window)
    private val re = FloatArray(window)
    private val im = FloatArray(window)
    private val hann = FloatArray(window) { (0.5 - 0.5 * cos(2 * PI * it / window)).toFloat() }

    /** Turns the windowed spectrum's power into the mean square of the signal it came from. */
    private val powerScale: Float = 2f / (window * hann.sumOf { (it * it).toDouble() }).toFloat()
    private val bins = window / 2
    private val aWeight = FloatArray(bins) { k -> aWeighting(k.toDouble() * sampleRate / window) }
    private val band = IntArray(bins) { k -> bandOf(k.toDouble() * sampleRate / window) }
    private val logMagnitude = FloatArray(bins)
    private val previous = FloatArray(bins)
    private val bandPower = FloatArray(BANDS.size)
    private var hasPrevious = false
    private var fluxMean = 0f

    /** Forgets the stretch before, as after a jump in the music. */
    fun reset() {
        hasPrevious = false
        fluxMean = 0f
    }

    /**
     * Measures [samples] (mono, -1 to 1, [window] of them from [offset]) into [out]'s
     * loudness, bands and onset. Time and beats are the caller's to set.
     */
    fun analyze(samples: FloatArray, offset: Int, out: SoundFrame) {
        for (i in 0 until window) {
            re[i] = samples[offset + i] * hann[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        var weighted = 0f
        bandPower.fill(0f)
        var flux = 0f
        for (k in 1 until bins) {
            val power = (re[k] * re[k] + im[k] * im[k]) * powerScale
            weighted += power * aWeight[k]
            val b = band[k]
            if (b >= 0) bandPower[b] += power
            // Spectral flux on compressed magnitudes: how much the spectrum grew since the stretch before.
            val lm = ln(1f + COMPRESSION * sqrt(power))
            if (hasPrevious && lm > previous[k]) flux += lm - previous[k]
            logMagnitude[k] = lm
        }
        logMagnitude.copyInto(previous, 0, 0, bins)
        out.loudness = level(weighted)
        for (b in BANDS.indices) out.bands[b] = level(bandPower[b])
        flux /= bins
        out.onset = if (!hasPrevious) 0f else onset(flux)
        hasPrevious = true
    }

    /** The flux against its recent mean: 0 when nothing sets in, 1 for a hit well above the music around it. */
    private fun onset(flux: Float): Float {
        val mean = fluxMean
        fluxMean = if (mean == 0f) flux else mean + (flux - mean) * FLUX_ADAPT
        val floor = maxOf(mean, FLUX_FLOOR)
        return ((flux - floor) / (floor * ONSET_RANGE)).coerceIn(0f, 1f)
    }

    companion object {
        /** About 21 ms at 48 kHz: short enough for a drum hit, long enough for the bass. */
        const val WINDOW = 1024

        /** The bands, in Hz: bass, low mids, mids, highs. */
        val BANDS = listOf(20.0 to 150.0, 150.0 to 600.0, 600.0 to 2_400.0, 2_400.0 to 12_000.0)

        private const val COMPRESSION = 1_000f
        private const val FLUX_ADAPT = 0.02f
        private const val FLUX_FLOOR = 1e-3f
        private const val ONSET_RANGE = 2f

        /** A mean square as Sendspin's 0 to 1 scale: -60 dB is 0, a full-scale sine (mean square 0.5) is 1. */
        fun level(meanSquare: Float): Float {
            if (meanSquare <= 0f) return 0f
            val db = 10 * log10(2 * meanSquare)
            return ((db + 60f) / 60f).coerceIn(0f, 1f)
        }

        private fun bandOf(f: Double): Int = BANDS.indexOfFirst { (lo, hi) -> f >= lo && f < hi }

        /** The A-weighting of IEC 61672 as a factor on power, 1 at 1 kHz. */
        internal fun aWeighting(f: Double): Float {
            if (f <= 0) return 0f
            val f2 = f * f
            val ra = 12_194.0 * 12_194.0 * f2 * f2 /
                ((f2 + 20.6 * 20.6) * sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12_194.0 * 12_194.0))
            val gain = ra * 1.2589 // +2.0 dB, so that 1 kHz passes unchanged
            return (gain * gain).toFloat()
        }
    }
}
