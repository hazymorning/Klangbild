package io.github.hazymorning.klangbild.landscape

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What a sheet is made of: how strongly its clouds of pulp, its tooth and its fibers show. */
enum class PaperKind(val clouds: Float, val tooth: Float, val fibers: Float) {
    /** The sky: watercolor paper under a soft wash. */
    WASH(0.035f, 0.004f, 0.04f),

    /** White paper: snow caps, tissue clouds, the sun and the moon. */
    WHITE(0.02f, 0.004f, 0.05f),

    /** Colored card: mountains, woods, meadows, banks. */
    CARD(0.045f, 0.005f, 0.06f),
}

/**
 * The paper itself. Every sheet has its grain: clouds of pulp from a fingertip to a hand wide,
 * a slow swell where it does not lie quite flat, short fibers a little lighter or darker than
 * the sheet, and a fine tooth. Together they make a factor of light around 1. Each sheet reads
 * it with its own seed, so the grain breaks off where one piece of paper ends and the next
 * begins.
 *
 * Fibers and tooth come from one tile ([TILE_DP] square, wrapping) made once per screen
 * density, and each sheet reads it from its own offset. The clouds and the swell are smooth
 * noise on a grid of [CELL_DP], per sheet, so the grain never repeats in a way the eye notices.
 */
class PaperGrain(private val density: Float) {
    private val size = ceil(TILE_DP * density).toInt()
    private val fibers = FloatArray(size * size)
    private val tooth = FloatArray(size * size)

    init {
        val rnd = Rnd(0x5EED)
        // Fibers: thin curved strokes, slightly more of them lighter than the sheet than darker.
        val count = (TILE_DP * TILE_DP / FIBER_AREA_DP).toInt()
        repeat(count) {
            val cx = rnd.next() * TILE_DP
            val cy = rnd.next() * TILE_DP
            val length = FIBER_MIN_DP + (FIBER_MAX_DP - FIBER_MIN_DP) * rnd.next()
            val angle = rnd.next() * PI.toFloat()
            val bend = rnd.normal() * 1.4f
            val width = 0.28f + 0.26f * rnd.next()
            val value = (0.35f + 0.65f * rnd.next()) * if (rnd.next() < 0.55f) 1f else -1f
            val (x0, y0) = (cx - cos(angle) * length / 2) to (cy - sin(angle) * length / 2)
            val (x1, y1) = (cx + cos(angle) * length / 2) to (cy + sin(angle) * length / 2)
            // The bend: the middle pushed sideways.
            val mx = cx - sin(angle) * bend * length * 0.12f
            val my = cy + cos(angle) * bend * length * 0.12f
            stamp(Shapes.stroke(x0, y0, mx, my, x1, y1, width, width * 0.8f, samples = 8), value)
        }
        blurWrap(fibers, max(1, (0.25f * density).toInt()))
        // Tooth: fine noise, softened to a fraction of a dp.
        for (i in tooth.indices) tooth[i] = rnd.next() + rnd.next() + rnd.next() - 1.5f
        blurWrap(tooth, max(1, (0.35f * density).toInt()))
        var sq = 0.0
        for (v in tooth) sq += v * v
        val norm = 1f / sqrt(sq / tooth.size).toFloat().coerceAtLeast(1e-6f)
        for (i in tooth.indices) tooth[i] *= norm
    }

    /** Adds a fiber's coverage (dp outline) to the tile, wrapping around its edges. */
    private fun stamp(o: Outline, value: Float) {
        val x0 = floor(o.minX() * density).toInt() - 1
        val y0 = floor(o.minY() * density).toInt() - 1
        val w = ceil(o.maxX() * density).toInt() + 2 - x0
        val h = ceil(o.maxY() * density).toInt() + 2 - y0
        val c = Coverage(w, h)
        c.polygon(FloatArray(o.size) { o.xs[it] * density - x0 }, FloatArray(o.size) { o.ys[it] * density - y0 })
        val v = c.resolve()
        for (y in 0 until h) for (x in 0 until w) {
            val k = v[y * w + x]
            if (k <= 0f) continue
            val tx = Math.floorMod(x0 + x, size)
            val ty = Math.floorMod(y0 + y, size)
            fibers[ty * size + tx] += value * k
        }
    }

    /** A box blur (three passes) that wraps around the tile's edges. */
    private fun blurWrap(p: FloatArray, r: Int) {
        val tmp = FloatArray(size)
        val norm = 1f / (2 * r + 1)
        repeat(3) {
            for (y in 0 until size) {
                val row = y * size
                var acc = 0f
                for (k in -r..r) acc += p[row + Math.floorMod(k, size)]
                for (x in 0 until size) {
                    tmp[x] = acc * norm
                    acc += p[row + Math.floorMod(x + r + 1, size)] - p[row + Math.floorMod(x - r, size)]
                }
                System.arraycopy(tmp, 0, p, row, size)
            }
            for (x in 0 until size) {
                var acc = 0f
                for (k in -r..r) acc += p[Math.floorMod(k, size) * size + x]
                for (y in 0 until size) {
                    tmp[y] = acc * norm
                    acc += p[Math.floorMod(y + r + 1, size) * size + x] - p[Math.floorMod(y - r, size) * size + x]
                }
                for (y in 0 until size) p[y * size + x] = tmp[y]
            }
        }
    }

    /**
     * The grain of one sheet over its box (canvas pixels [left], [top], [width] × [height]):
     * [at] gives the factor for a pixel of the box.
     */
    fun field(seed: Int, kind: PaperKind, left: Int, top: Int, width: Int, height: Int): Field = Field(seed, kind, left, top, width, height)

    inner class Field(seed: Int, private val kind: PaperKind, private val left: Int, private val top: Int, width: Int, height: Int) {
        private val cell = CELL_DP * density
        private val gx0 = floor(left / cell).toInt()
        private val gy0 = floor(top / cell).toInt()
        private val gw = ceil((left + width) / cell).toInt() - gx0 + 2
        private val gh = ceil((top + height) / cell).toInt() - gy0 + 2
        private val grid = FloatArray(gw * gh)
        private val ox: Int
        private val oy: Int

        init {
            val rnd = Rnd(seed * 7919 + 17)
            ox = (rnd.next() * size).toInt()
            oy = (rnd.next() * size).toInt()
            val offsets = FloatArray(SCALES.size * 2) { rnd.next() * 1000f }
            for (j in 0 until gh) for (i in 0 until gw) {
                val xDp = (gx0 + i) * CELL_DP
                val yDp = (gy0 + j) * CELL_DP
                var v = 0f
                for (k in SCALES.indices) {
                    val n = Noise.at((xDp / SCALES[k] + offsets[2 * k]).toDouble(), (yDp / SCALES[k] + offsets[2 * k + 1]).toDouble()).toFloat()
                    v += n * if (k == 0) SWELL else kind.clouds * CLOUD_AMPS[k]
                }
                grid[j * gw + i] = v
            }
        }

        /** The paper's light at canvas pixel ([x], [y]). */
        fun at(x: Int, y: Int): Float {
            val gx = x / cell - gx0
            val gy = y / cell - gy0
            val i = gx.toInt().coerceIn(0, gw - 2)
            val j = gy.toInt().coerceIn(0, gh - 2)
            val fx = (gx - i).coerceIn(0f, 1f)
            val fy = (gy - j).coerceIn(0f, 1f)
            val a = grid[j * gw + i]
            val b = grid[j * gw + i + 1]
            val c = grid[(j + 1) * gw + i]
            val d = grid[(j + 1) * gw + i + 1]
            val clouds = a + (b - a) * fx + (c - a + (d - c - b + a) * fx) * fy
            val t = Math.floorMod(y + oy, size) * size + Math.floorMod(x + ox, size)
            return 1f + clouds + kind.fibers * fibers[t] + kind.tooth * tooth[t]
        }
    }

    companion object {
        /** The side of the fiber and tooth tile (dp). */
        const val TILE_DP = 320f

        /** The clouds' grid (dp). */
        const val CELL_DP = 3f

        /** One fiber per this many dp² of paper, each this long (dp). */
        private const val FIBER_AREA_DP = 20f
        private const val FIBER_MIN_DP = 4f
        private const val FIBER_MAX_DP = 14f

        /** The swell (the paper not lying flat) and the clouds of pulp: their sizes (dp) and strengths. */
        private val SCALES = floatArrayOf(240f, 110f, 44f, 17f, 7f)
        private val CLOUD_AMPS = floatArrayOf(0f, 1f, 0.55f, 0.36f, 0.22f)
        private const val SWELL = 0.05f
    }
}

/** A small fixed-seed random source for cutting paper the same way every time (xorshift). */
class Rnd(seed: Int) {
    private var s = seed * -0x61c88647 or 1

    /** A number from 0 until 1. */
    fun next(): Float {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        return (s ushr 8) / 16777216f
    }

    /** Roughly normal, mean 0, standard deviation 1 (a sum of uniforms). */
    fun normal(): Float = (next() + next() + next() + next() - 2f) * 1.732f

    /** A whole number from 0 until [n]. */
    fun int(n: Int): Int = min(n - 1, (next() * n).toInt())
}
