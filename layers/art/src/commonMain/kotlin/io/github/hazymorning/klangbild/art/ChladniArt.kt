package io.github.hazymorning.klangbild.art

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Artwork for music without a cover (radio, local files): a Chladni figure, the pattern
 * sand forms on a vibrating plate. It is literally a picture of sound, which is what the app's
 * name means. Every station or album gets its own stable figure and colors.
 *
 * The plate is round, seen from above, and hangs on the wall like the prints (outside the disc
 * the image is transparent). A ring mode of the plate (Bessel functions in the radius) is
 * superposed with a nearly equal mode that has three to six lobes around it, so the sand
 * circles undulate like ripples. These are the calm figures of real sand photographs, not a
 * grid of lines. The sand stays clear of the rim. Both modes share the same axis, so every
 * figure is mirror-symmetric and no figure can turn into a hooked, pinwheel-like sign.
 *
 * Pure Kotlin (returns ARGB pixels), so it renders identically on the JVM and the device
 * and can be produced at the exact on-screen pixel size, with no upscaling ever.
 */
object ChladniArt {

    /**
     * One figure: the ring mode J₀(k1·r), plus [mix] times the mode J_n2(k2·r)·cos(n2·θ) with
     * [n2] lobes; the [plate]'s and the [sand]'s colors.
     */
    data class Spec(
        val k1: Float,
        val n2: Int,
        val k2: Float,
        val mix: Float,
        val plate: Int,
        val sand: Int,
    )

    private val palettes = listOf(
        0xFF121B2B.toInt() to 0xFFEADDC2.toInt(), // ink blue / sand
        0xFF0F2320.toInt() to 0xFFDCE7D3.toInt(), // deep green / pale sage
        0xFF221426.toInt() to 0xFFF1DACB.toInt(), // aubergine / blush
        0xFF1B1716.toInt() to 0xFFE6C597.toInt(), // warm charcoal / brass
        0xFF2A1113.toInt() to 0xFFF2DECB.toInt(), // oxblood / ivory
        0xFF19212A.toInt() to 0xFFD5E2E8.toInt(), // slate / mist
    )

    fun specFor(seedText: String): Spec {
        val rnd = Random(stableHash(seedText.ifBlank { "Klangbild" }))
        // A ring mode (two or three nodal circles, the outermost well inside the rim) made
        // wavy by a second mode with three to six lobes. Nodal diameters through the center
        // would turn into crosses, wheels and rosettes. A single circle or two lobes read as
        // an "O", and plain circles read as a target.
        val rings = 2 + rnd.nextInt(2)
        val n2 = LOBES[rnd.nextInt(LOBES.size)]
        val k1 = J0_ZEROS[rings - 1] / OUTER_RING
        val k2 = k1 * (0.97f + 0.06f * rnd.nextFloat())
        // The second mode's share: the outermost circle undulates clearly but stays rounded
        // (more than 1.5 r / (n² − 1) gives the lobes points), and no circle may wander far
        // toward its neighbor, the clamp or the sandless rim.
        val radii = FloatArray(rings) { J0_ZEROS[it] / k1 }
        fun undulation(i: Int) = abs(besselTable(n2).at(k2 * radii[i])) / (k1 * abs(besselTable(1).at(J0_ZEROS[i])))
        val rounded = minOf(MAX_WAVE, 1.5f * OUTER_RING / (n2 * n2 - 1))
        var mix = rounded * (0.65f + 0.35f * rnd.nextFloat()) / undulation(rings - 1).coerceAtLeast(1e-3f)
        for (i in 0 until rings) {
            val inside = if (i == 0) radii[0] - MOUNT * 2f else radii[i] - radii[i - 1]
            val outside = if (i == rings - 1) (SAND_IN - radii[i]) * 2f else radii[i + 1] - radii[i]
            mix = minOf(mix, 0.3f * minOf(inside, outside) / undulation(i).coerceAtLeast(1e-4f))
        }
        mix = mix.coerceAtMost(MAX_MIX) * (if (rnd.nextBoolean()) 1f else -1f)
        val (plate, sand) = palettes[rnd.nextInt(palettes.size)]
        return Spec(k1, n2, k2, mix, plate, sand)
    }

    /** The first zeros of J₀: where the nodal circles of a ring mode lie. */
    private val J0_ZEROS = floatArrayOf(2.4048f, 5.5201f, 8.6537f)

    /** Radius of the outermost nodal circle (share of the plate's radius). */
    private const val OUTER_RING = 0.64f

    /** Lobes of the second mode. */
    private val LOBES = intArrayOf(3, 4, 5, 6)

    /** Largest undulation of the outermost circle (share of the plate's radius). */
    private const val MAX_WAVE = 0.07f

    /** Upper limit of the second mode's share: more would break the circles apart. */
    const val MAX_MIX = 0.6f

    /**
     * The plate's vibration at radius [r] (0..1) and angle [theta]: sand gathers where it is
     * zero. Both terms are cosines around the same axis, so the field is mirror-symmetric by
     * construction.
     */
    fun field(spec: Spec, r: Float, theta: Float): Float =
        besselTable(0).at(spec.k1 * r) + spec.mix * besselTable(spec.n2).at(spec.k2 * r) * cos(spec.n2 * theta)

    /** Plate radius as a share of half the image. */
    const val PLATE = 0.84f

    /** Radius of the central clamp (plate units). */
    private const val MOUNT = 0.035f

    /** The sand thins out between these radii (shares of the plate's): none near the rim. */
    private const val SAND_IN = 0.74f
    private const val SAND_OUT = 0.84f

    fun render(spec: Spec, size: Int, seed: Int = 1): IntArray {
        val out = IntArray(size * size)
        val rnd = Random(seed)
        val j1 = besselTable(0)
        val j2 = besselTable(spec.n2)

        val pr = (spec.plate shr 16) and 0xFF
        val pg = (spec.plate shr 8) and 0xFF
        val pb = spec.plate and 0xFF
        val sr = (spec.sand shr 16) and 0xFF
        val sg = (spec.sand shr 8) and 0xFF
        val sb = spec.sand and 0xFF
        // Line width in plate units; dividing |f| by |∇f| gives the distance to the nodal
        // line, so lines keep an even width instead of blooming where they cross.
        val lineWidth = 2.2f / (PLATE / 2f * size) + 0.004f
        for (y in 0 until size) {
            val v = ((y + 0.5f) / size) * 2f - 1f
            for (x in 0 until size) {
                val u = ((x + 0.5f) / size) * 2f - 1f
                val r = hypot(u, v) / PLATE
                val o = y * size + x
                if (r >= 1f) {
                    // Off the plate there is nothing: the wall (and its light) shows through. One
                    // pixel of anti-aliasing keeps the rim smooth.
                    val edge = ((1f - (r - 1f) * PLATE * size / 2f)).coerceIn(0f, 1f)
                    out[o] = if (edge <= 0f) 0 else argb(pr * 1.2f, pg * 1.2f, pb * 1.2f, edge)
                    continue
                }
                if (r < MOUNT) {
                    // The plate is clamped in its center: a small dark bolt with a lit edge.
                    val edge = exp(-((MOUNT - r) / 0.006f).let { it * it }) * 0.5f
                    val k = 0.45f + 0.25f * (1f - r / MOUNT)
                    out[o] = argb(pr * k + 255f * edge * 0.35f, pg * k + 255f * edge * 0.35f, pb * k + 255f * edge * 0.35f)
                    continue
                }
                val theta = atan2(v, u)
                // f = A(r) + mix·B(r)·cos(n₂θ) and its gradient in polar form
                val a = j1.at(spec.k1 * r)
                val b = j2.at(spec.k2 * r)
                val da = spec.k1 * j1.slope(spec.k1 * r)
                val db = spec.k2 * j2.slope(spec.k2 * r)
                val c2 = cos(spec.n2 * theta)
                val s2 = sin(spec.n2 * theta)
                val f = a + spec.mix * b * c2
                val fr = da + spec.mix * db * c2
                val ft = -spec.mix * spec.n2 * b * s2 / max(r, 1e-3f)
                val dist = abs(f) / max(hypot(fr, ft), 0.6f)
                val line = exp(-(dist / lineWidth) * (dist / lineWidth))
                val halo = exp(-(dist / (lineWidth * 4f)) * (dist / (lineWidth * 4f)))
                // Sand: stippled grains, denser on the nodal lines, and a little dust elsewhere.
                // Sand slides off the bolt and off the free rim, so there is none right next to
                // either and no line ends abruptly at the plate's edge.
                val rimClear = ((SAND_OUT - r) / (SAND_OUT - SAND_IN)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val clear = ((r - MOUNT) / 0.05f).coerceIn(0f, 1f) * rimClear
                val grain = if (rnd.nextFloat() < line * 0.9f * clear) 0.75f + 0.25f * rnd.nextFloat() else 0f
                val dust = if (rnd.nextFloat() < (halo * 0.06f + 0.004f) * rimClear) 0.55f else 0f
                // Plate: brushed metal with a soft light from the upper left and a bright rim.
                val light = 1.06f - 0.22f * hypot(u + 0.25f, v + 0.3f)
                val tex = 1f + (rnd.nextFloat() - 0.5f) * 0.05f
                val rim = exp(-((1f - r) / 0.012f).let { it * it }) * 0.35f
                val k = (grain + dust + line * 0.1f * clear).coerceAtMost(1f)
                val base = light * tex
                val rr = (pr * base + 255f * rim * 0.4f) * (1f - k) + sr * k
                val gg = (pg * base + 255f * rim * 0.4f) * (1f - k) + sg * k
                val bb = (pb * base + 255f * rim * 0.4f) * (1f - k) + sb * k
                out[o] = argb(rr, gg, bb)
            }
        }
        return out
    }

    private fun argb(r: Float, g: Float, b: Float, alpha: Float = 1f): Int =
        ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

    /** J_n on 0..[MAX_X] in steps of [STEP], from Bessel's integral (Simpson's rule). */
    private class BesselTable(val values: FloatArray) {
        fun at(x: Float): Float {
            val p = (x / STEP).coerceIn(0f, (values.size - 2).toFloat())
            val i = p.toInt()
            val t = p - i
            return values[i] * (1f - t) + values[i + 1] * t
        }

        /** dJ/dx by central differences on the table. */
        fun slope(x: Float): Float = (at(x + STEP) - at(x - STEP)) / (2 * STEP)
    }

    private const val STEP = 0.01f
    private const val MAX_X = 40f
    private val tables = HashMap<Int, BesselTable>()
    private val tablesLock = SynchronizedObject()

    private fun besselTable(n: Int): BesselTable = synchronized(tablesLock) {
        tables.getOrPut(n) {
            val count = (MAX_X / STEP).toInt() + 2
            val steps = 64 // even, for Simpson
            val values = FloatArray(count)
            for (i in 0 until count) {
                val x = i * STEP
                var sum = 0.0
                for (s in 0..steps) {
                    val tau = PI * s / steps
                    val w = if (s == 0 || s == steps) 1.0 else if (s % 2 == 1) 4.0 else 2.0
                    sum += w * cos(n * tau - x * sin(tau))
                }
                values[i] = (sum * (PI / steps) / 3.0 / PI).toFloat()
            }
            BesselTable(values)
        }
    }
}
