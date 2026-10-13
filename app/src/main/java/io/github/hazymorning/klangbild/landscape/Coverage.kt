package io.github.hazymorning.klangbild.landscape

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Exact anti-aliased coverage of a polygon, the way font rasterizers do it: every edge adds
 * the signed area it encloses to an accumulation row, and a running sum along the row turns
 * that into coverage. The edge of a paper cut-out comes out as sharp as the pixel grid allows,
 * with no supersampling and no blur.
 */
class Coverage(val width: Int, val height: Int) {
    // Each row has two cells of room on the right, so area that runs past the last pixel stays in its row.
    private val stride = width + 2
    private val acc = FloatArray(stride * height)

    /** Adds the closed polygon ([xs], [ys]) in pixels of this raster (any winding, non-zero fill). */
    fun polygon(xs: FloatArray, ys: FloatArray, n: Int = xs.size) {
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            line(xs[i], ys[i], xs[j], ys[j])
        }
    }

    /**
     * An edge that leaves the raster on the left or right is split where it crosses the side;
     * the part outside becomes a vertical edge along that side, which leaves the coverage
     * inside exactly as it was.
     */
    private fun line(ax: Float, ay: Float, bx: Float, by: Float) {
        val right = width - 1e-3f
        var t0 = 0f
        val cuts = FloatArray(2)
        var n = 0
        for (side in floatArrayOf(0f, right)) {
            if ((ax - side) * (bx - side) < 0f) cuts[n++] = (side - ax) / (bx - ax)
        }
        if (n == 2 && cuts[0] > cuts[1]) cuts.reverse()
        for (k in 0..n) {
            val t1 = if (k < n) cuts[k] else 1f
            span(ax + (bx - ax) * t0, ay + (by - ay) * t0, ax + (bx - ax) * t1, ay + (by - ay) * t1)
            t0 = t1
        }
    }

    private fun span(ax: Float, ay: Float, bx: Float, by: Float) {
        if (abs(ay - by) < 1e-6f) return
        val x0c = ax.coerceIn(0f, width - 1e-3f)
        val x1c = bx.coerceIn(0f, width - 1e-3f)
        val dir: Float
        val p0x: Float
        val p0y: Float
        val p1x: Float
        val p1y: Float
        if (ay < by) {
            dir = 1f; p0x = x0c; p0y = ay; p1x = x1c; p1y = by
        } else {
            dir = -1f; p0x = x1c; p0y = by; p1x = x0c; p1y = ay
        }
        val dxdy = (p1x - p0x) / (p1y - p0y)
        var x = p0x
        if (p0y < 0f) x -= p0y * dxdy
        val yStart = max(0, floor(p0y).toInt())
        val yEnd = min(height, ceil(p1y).toInt())
        for (y in yStart until yEnd) {
            val row = y * stride
            val dy = min((y + 1).toFloat(), p1y) - max(y.toFloat(), p0y)
            val xNext = x + dxdy * dy
            val d = dy * dir
            val lo = min(x, xNext)
            val hi = max(x, xNext)
            val loFloor = floor(lo)
            val loI = loFloor.toInt()
            val hiCeil = ceil(hi)
            val hiI = hiCeil.toInt()
            if (hiI <= loI + 1) {
                val xmf = 0.5f * (x + xNext) - loFloor
                acc[row + loI] += d - d * xmf
                acc[row + loI + 1] += d * xmf
            } else {
                val s = 1f / (hi - lo)
                val x0f = lo - loFloor
                val a0 = 0.5f * s * (1f - x0f) * (1f - x0f)
                val x1f = hi - hiCeil + 1f
                val am = 0.5f * s * x1f * x1f
                acc[row + loI] += d * a0
                if (hiI == loI + 2) {
                    acc[row + loI + 1] += d * (1f - a0 - am)
                } else {
                    val a1 = s * (1.5f - x0f)
                    acc[row + loI + 1] += d * (a1 - a0)
                    for (xi in loI + 2 until hiI - 1) acc[row + xi] += d * s
                    val a2 = a1 + (hiI - loI - 3) * s
                    acc[row + hiI - 1] += d * (1f - a2 - am)
                }
                acc[row + hiI] += d * am
            }
            x = xNext
        }
    }

    /** The coverage (0..1) of every pixel, row by row; the raster is spent afterward. */
    fun resolve(): FloatArray {
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            var sum = 0f
            val row = y * stride
            val o = y * width
            for (x in 0 until width) {
                sum += acc[row + x]
                out[o + x] = min(1f, abs(sum))
            }
        }
        return out
    }
}
