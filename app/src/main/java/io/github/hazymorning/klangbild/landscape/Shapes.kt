package io.github.hazymorning.klangbild.landscape

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A point of a drawn contour (dp); a [corner] is cut sharp, every other point is passed smoothly. */
data class Knot(val x: Float, val y: Float, val corner: Boolean = false)

/** An outline as two coordinate arrays (dp), closed from the last point back to the first. */
class Outline(val xs: FloatArray, val ys: FloatArray) {
    val size: Int get() = xs.size

    fun minX() = xs.min()
    fun maxX() = xs.max()
    fun minY() = ys.min()
    fun maxY() = ys.max()

    /** Moved by ([dx], [dy]) and scaled by [s] about the origin. */
    fun transformed(s: Float, dx: Float = 0f, dy: Float = 0f) =
        Outline(FloatArray(size) { xs[it] * s + dx }, FloatArray(size) { ys[it] * s + dy })

    companion object {
        fun of(points: List<Pair<Float, Float>>) = Outline(FloatArray(points.size) { points[it].first }, FloatArray(points.size) { points[it].second })
    }
}

/** An open line through knots, sampled densely: x and y arrays (dp). */
class Polyline(val xs: FloatArray, val ys: FloatArray) {
    val size: Int get() = xs.size

    /** The line's height at [x] (dp), for lines running left to right or right to left; beyond its ends, the end's height. */
    fun yAt(x: Float): Float {
        val ascending = xs[0] <= xs[size - 1]
        val first = if (ascending) 0 else size - 1
        val last = if (ascending) size - 1 else 0
        if (x <= xs[first]) return ys[first]
        if (x >= xs[last]) return ys[last]
        // Binary search in the line's own direction: lo and hi bracket x.
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if ((xs[mid] <= x) == ascending) lo = mid else hi = mid
        }
        val dx = xs[hi] - xs[lo]
        val t = if (dx == 0f) 0f else (x - xs[lo]) / dx
        return ys[lo] + (ys[hi] - ys[lo]) * t.coerceIn(0f, 1f)
    }

    /** Closed down to [bottom]: the shape of a layer of paper under this line. */
    fun closedDown(bottom: Float): Outline {
        val n = size
        val xs2 = FloatArray(n + 2)
        val ys2 = FloatArray(n + 2)
        for (i in 0 until n) {
            xs2[i] = xs[i]; ys2[i] = ys[i]
        }
        xs2[n] = xs[n - 1]; ys2[n] = bottom
        xs2[n + 1] = xs[0]; ys2[n + 1] = bottom
        return Outline(xs2, ys2)
    }
}

/**
 * The shapes the picture is cut from, drawn from deliberate control points: every curve is
 * placed by hand (the bake adds the slight unevenness of the scissors).
 */
object Shapes {

    /**
     * A smooth line through [knots] (centripetal Catmull-Rom), cut sharp at corners, sampled
     * about every [step] dp.
     */
    fun line(knots: List<Knot>, step: Float = 1.5f): Polyline {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until knots.size - 1) {
            val p1 = knots[i]
            val p2 = knots[i + 1]
            // At a corner (or an end) the curve leaves straight toward its neighbor.
            val p0 = if (i == 0 || p1.corner) Knot(2 * p1.x - p2.x, 2 * p1.y - p2.y) else knots[i - 1]
            val p3 = if (i + 2 >= knots.size || p2.corner) Knot(2 * p2.x - p1.x, 2 * p2.y - p1.y) else knots[i + 2]
            val len = hypot(p2.x - p1.x, p2.y - p1.y)
            val n = max(2, (len / step).toInt())
            for (k in 0 until n) {
                val (x, y) = catmullRom(p0, p1, p2, p3, k / n.toFloat())
                xs += x
                ys += y
            }
        }
        xs += knots.last().x
        ys += knots.last().y
        return Polyline(xs.toFloatArray(), ys.toFloatArray())
    }

    private fun catmullRom(p0: Knot, p1: Knot, p2: Knot, p3: Knot, t: Float): Pair<Float, Float> {
        fun knot(ti: Float, a: Knot, b: Knot): Float = ti + sqrt(hypot(b.x - a.x, b.y - a.y)).coerceAtLeast(1e-3f)
        val t0 = 0f
        val t1 = knot(t0, p0, p1)
        val t2 = knot(t1, p1, p2)
        val t3 = knot(t2, p2, p3)
        val tt = t1 + (t2 - t1) * t
        fun lerp(a: Float, b: Float, ta: Float, tb: Float) = if (tb - ta == 0f) a else a * (tb - tt) / (tb - ta) + b * (tt - ta) / (tb - ta)
        val a1x = lerp(p0.x, p1.x, t0, t1); val a1y = lerp(p0.y, p1.y, t0, t1)
        val a2x = lerp(p1.x, p2.x, t1, t2); val a2y = lerp(p1.y, p2.y, t1, t2)
        val a3x = lerp(p2.x, p3.x, t2, t3); val a3y = lerp(p2.y, p3.y, t2, t3)
        val b1x = lerp(a1x, a2x, t0, t2); val b1y = lerp(a1y, a2y, t0, t2)
        val b2x = lerp(a2x, a3x, t1, t3); val b2y = lerp(a2y, a3y, t1, t3)
        return lerp(b1x, b2x, t1, t2) to lerp(b1y, b2y, t1, t2)
    }

    /** A circle (dp). */
    fun circle(cx: Float, cy: Float, r: Float, segments: Int = max(24, (r * 1.6f).toInt())): Outline =
        ellipse(cx, cy, r, r, segments)

    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, segments: Int = max(24, (max(rx, ry) * 1.6f).toInt())): Outline {
        val xs = FloatArray(segments)
        val ys = FloatArray(segments)
        for (i in 0 until segments) {
            val a = 2 * PI * i / segments
            xs[i] = cx + (rx * cos(a)).toFloat()
            ys[i] = cy + (ry * sin(a)).toFloat()
        }
        return Outline(xs, ys)
    }

    /**
     * A tapered stroke along the quadratic curve [x0],[y0] → ([cx],[cy]) → [x1],[y1], [w0] wide
     * at its start and [w1] at its end (dp): a grass blade, a twig, a stem.
     */
    fun stroke(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, w0: Float, w1: Float, samples: Int = 18): Outline {
        val lx = FloatArray(samples + 1)
        val ly = FloatArray(samples + 1)
        val rx = FloatArray(samples + 1)
        val ry = FloatArray(samples + 1)
        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val u = 1 - t
            val x = u * u * x0 + 2 * u * t * cx + t * t * x1
            val y = u * u * y0 + 2 * u * t * cy + t * t * y1
            var tx = 2 * u * (cx - x0) + 2 * t * (x1 - cx)
            var ty = 2 * u * (cy - y0) + 2 * t * (y1 - cy)
            val l = hypot(tx, ty).coerceAtLeast(1e-4f)
            tx /= l; ty /= l
            val w = (w0 + (w1 - w0) * t) / 2f
            lx[i] = x - ty * w; ly[i] = y + tx * w
            rx[i] = x + ty * w; ry[i] = y - tx * w
        }
        val xs = FloatArray(2 * (samples + 1))
        val ys = FloatArray(2 * (samples + 1))
        for (i in 0..samples) {
            xs[i] = lx[i]; ys[i] = ly[i]
            xs[2 * samples + 1 - i] = rx[i]; ys[2 * samples + 1 - i] = ry[i]
        }
        return Outline(xs, ys)
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
