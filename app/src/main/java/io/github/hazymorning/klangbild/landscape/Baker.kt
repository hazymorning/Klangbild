package io.github.hazymorning.klangbild.landscape

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How far beyond the canvas a swaying sprite is cut (dp). */
private const val SPRITE_REACH_DP = 120f

/** The paper's light is stored in the raster up to this factor while it is cut. */
internal const val TONE_MAX = 1.25f

/** The grid (dp) on which the bake notes what stands in front, for the shade it casts. */
private const val AO_CELL = 2f

/** A value at each point of the screen (dp): an ink's weight or the paper's light. */
internal fun interface Weight {
    fun at(x: Float, y: Float): Float
}

/** A sheet while it is being cut: weights and light in a raster over its box (canvas pixels). */
internal class Build(val id: SheetId, val left: Int, val top: Int, val width: Int, val height: Int) {
    val raster = InkRaster(width, height, seed = id.ordinal + 1)
}

/** A coverage mask in a box of canvas pixels. */
internal class Mask(val left: Int, val top: Int, val width: Int, val height: Int, val v: FloatArray)

/**
 * Cuts the paper for one bake: a canvas of [cw] × [ch] pixels at [d] pixels per dp, the
 * season's [shapes] and the foreground [fg] for the label's side. It cuts pieces ([mask]), lays
 * them on sheets ([lay]) and finishes each sheet ([finish]). It keeps a coarse grid of what the
 * sheets baked so far cover, so each sheet takes the shade of what stands in front of it. The
 * pieces of each kind of paper are cut by extensions in the Bake files (BakeSky.kt and so on).
 */
internal class Baker(val cw: Int, val ch: Int, val d: Float, val shapes: SeasonShapes, val fg: PaperMotif.Foreground) {
    val o = PaperMotif.OVERSCAN
    private val grain = PaperGrain(d)
    /** What stands in front of the sheets baked so far (coverage on a grid of [AO_CELL] dp). */
    private val gw = ceil(cw / (AO_CELL * d)).toInt() + 1
    private val gh = ceil(ch / (AO_CELL * d)).toInt() + 1
    private val front = FloatArray(gw * gh)
    val meadowLine = Shapes.line(PaperMotif.meadow)

    /** The same value [v] everywhere; [ZERO] is 0 everywhere. */
    fun c(v: Float) = Weight { _, _ -> v }
    val ZERO = c(0f)

    fun px(dp: Float) = (dp + o) * d
    fun dpX(px: Int) = (px + 0.5f) / d - o
    fun dpY(px: Int) = (px + 0.5f) / d - o

    /** Where pieces may be cut (canvas pixels): the canvas or, for sprites that sway, a little beyond it ([beyondCanvas]). */
    private var minX = 0
    private var minY = 0
    private var maxX = cw
    private var maxY = ch

    /** Cuts in [block] reach [SPRITE_REACH_DP] beyond the canvas, so a tree that sways never shows where the canvas ends. */
    fun <T> beyondCanvas(block: () -> T): T {
        val m = ceil(SPRITE_REACH_DP * d).toInt()
        minX = -m
        minY = -m
        maxX = cw + m
        maxY = ch + m
        try {
            return block()
        } finally {
            minX = 0
            minY = 0
            maxX = cw
            maxY = ch
        }
    }

    // --- cutting ------------------------------------------------------------------------

    /** The union coverage of [outlines] (dp), in their box padded by [pad] pixels, clipped to the canvas. */
    fun mask(outlines: List<Outline>, pad: Int = 2): Mask? {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (e in outlines) {
            x0 = min(x0, e.minX()); y0 = min(y0, e.minY()); x1 = max(x1, e.maxX()); y1 = max(y1, e.maxY())
        }
        val left = max(minX, floor(px(x0)).toInt() - pad)
        val top = max(minY, floor(px(y0)).toInt() - pad)
        val right = min(maxX, ceil(px(x1)).toInt() + pad)
        val bottom = min(maxY, ceil(px(y1)).toInt() + pad)
        if (right <= left || bottom <= top) return null
        val w = right - left
        val h = bottom - top
        val out = FloatArray(w * h)
        for (e in outlines) {
            // Each piece is cut in a box of its own: a meadow of small flowers costs what they cover,
            // not a whole meadow per flower.
            val l = max(left, floor(px(e.minX())).toInt() - 1)
            val t = max(top, floor(px(e.minY())).toInt() - 1)
            val r = min(right, ceil(px(e.maxX())).toInt() + 1)
            val bt = min(bottom, ceil(px(e.maxY())).toInt() + 1)
            if (r <= l || bt <= t) continue
            val ew = r - l
            val c = Coverage(ew, bt - t)
            c.polygon(FloatArray(e.size) { px(e.xs[it]) - l }, FloatArray(e.size) { px(e.ys[it]) - t })
            val v = c.resolve()
            for (y in 0 until bt - t) {
                val row = (t - top + y) * w + (l - left)
                for (x in 0 until ew) {
                    val k = v[y * ew + x]
                    if (k > out[row + x]) out[row + x] = k
                }
            }
        }
        return Mask(left, top, w, h, out)
    }

    /**
     * Lays a piece of paper ([piece]) on [b], as one piece is glued over another: its weights and
     * its light take over where it covers, and it darkens what lies under its edge by [aoCast]
     * (spread over [aoSigma] dp).
     */
    fun lay(b: Build, piece: Mask?, w1: Weight, w2: Weight, tone: Weight = c(1f), aoCast: Float = 0f, aoSigma: Float = 3f) {
        piece ?: return
        val r = b.raster
        val m = if (aoCast > 0f) expand(piece, ceil(3f * aoSigma * d).toInt() + 2) else piece
        if (aoCast > 0f) {
            val blurred = m.v.copyOf()
            blur(blurred, m.width, m.height, max(1, (aoSigma * d - 0.5f).roundToInt()))
            for (y in 0 until m.height) {
                val ry = m.top + y - b.top
                if (ry < 0 || ry >= b.height) continue
                for (x in 0 until m.width) {
                    val rx = m.left + x - b.left
                    if (rx < 0 || rx >= b.width) continue
                    val i = y * m.width + x
                    val shade = aoCast * blurred[i] * (1f - m.v[i])
                    if (shade <= 0.002f) continue
                    val p = r.pixels[ry * b.width + rx]
                    if (p ushr 24 == 0) continue
                    val t = ((p and 0xFF) * (1f - shade)).roundToInt()
                    r.pixels[ry * b.width + rx] = (p and 0xFFFFFF00.toInt()) or t
                }
            }
        }
        for (y in 0 until m.height) {
            val ry = m.top + y - b.top
            if (ry < 0 || ry >= b.height) continue
            val yDp = dpY(m.top + y)
            for (x in 0 until m.width) {
                val k = m.v[y * m.width + x]
                if (k <= 0.002f) continue
                val rx = m.left + x - b.left
                if (rx < 0 || rx >= b.width) continue
                val xDp = dpX(m.left + x)
                r.over(rx, ry, w1.at(xDp, yDp), w2.at(xDp, yDp), (tone.at(xDp, yDp) / TONE_MAX).coerceIn(0f, 1f), k)
            }
        }
    }

    /** A box over the canvas from [x0] to [x1] and [y0] to [y1] (dp). */
    fun build(id: SheetId, x0: Float, y0: Float, x1: Float, y1: Float): Build {
        val left = max(minX, floor(px(x0)).toInt() - 2)
        val top = max(minY, floor(px(y0)).toInt() - 2)
        val right = min(maxX, ceil(px(x1)).toInt() + 2)
        val bottom = min(maxY, ceil(px(y1)).toInt() + 2)
        return Build(id, left, top, max(1, right - left), max(1, bottom - top))
    }

    /** A white margin around [m]: its coverage spread by [marginDp] and cut again with a soft edge. */
    fun margin(m: Mask, marginDp: Float): Mask {
        val v = m.v.copyOf()
        blur(v, m.width, m.height, max(1, (marginDp * d * 0.55f).roundToInt()))
        for (i in v.indices) v[i] = Shapes.smoothstep(0.04f, 0.22f, v[i])
        return Mask(m.left, m.top, m.width, m.height, v)
    }

    /** [m] in a box grown by [pad] pixels on every side (within the canvas). */
    private fun expand(m: Mask, pad: Int): Mask {
        val left = max(minX, m.left - pad)
        val top = max(minY, m.top - pad)
        val right = min(maxX, m.left + m.width + pad)
        val bottom = min(maxY, m.top + m.height + pad)
        val w = right - left
        val h = bottom - top
        val v = FloatArray(w * h)
        for (y in 0 until m.height) System.arraycopy(m.v, y * m.width, v, (m.top + y - top) * w + (m.left - left), m.width)
        return Mask(left, top, w, h, v)
    }

    /** Where both masks cover. */
    fun intersect(a: Mask?, b: Mask?): Mask? {
        a ?: return null
        b ?: return null
        val out = FloatArray(a.v.size)
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val bx = a.left + x - b.left
            val by = a.top + y - b.top
            if (bx < 0 || by < 0 || bx >= b.width || by >= b.height) continue
            out[y * a.width + x] = a.v[y * a.width + x] * b.v[by * b.width + bx]
        }
        return Mask(a.left, a.top, a.width, a.height, out)
    }

    /** A contour cut by hand: the drawn line, a little uneven, closed down to [closedTo] (dp) or left open as drawn. */
    fun cut(knots: List<Knot>, seed: Int, amp: Float, closedTo: Float? = null): Outline {
        val line = Shapes.line(knots, step = 1f)
        val wobbly = wobbled(Outline(line.xs, line.ys), seed, amp, closed = false)
        if (closedTo == null) return wobbly
        val n = wobbly.size
        return Outline(wobbly.xs + floatArrayOf(wobbly.xs[n - 1], wobbly.xs[0]), wobbly.ys + floatArrayOf(closedTo, closedTo))
    }

    // --- finishing ----------------------------------------------------------------------

    /**
     * Makes a cut sheet ready to draw: its paper's grain, the shade of what stands close in front
     * of it and the light on its cut top edge (all in the paper's light), then its focus. What the
     * sheet covers then joins what stands in front of the sheets behind it.
     */
    fun finish(b: Build): Sheet {
        val id = b.id
        val r = b.raster
        val g = grain.field(id.ordinal * 101 + 7, id.kind, b.left, b.top, b.width, b.height)
        val ao = aoFor(id)
        val rimPx = max(1, d.roundToInt())
        val out = r.pixels
        val alpha = IntArray(out.size) { out[it] ushr 24 }
        for (y in 0 until b.height) {
            for (x in 0 until b.width) {
                val i = y * b.width + x
                val a = alpha[i]
                if (a == 0) continue
                val p = out[i]
                val tone = (p and 0xFF) / 255f * TONE_MAX
                val above = if (y >= rimPx) alpha[i - rimPx * b.width] else 0
                val rim = 1f + id.rim * ((a - above) / 255f).coerceIn(0f, 1f)
                val cx = b.left + x
                val cy = b.top + y
                val t = (tone * g.at(cx, cy) * ao.at(cx, cy) * rim / Inks.T_SCALE).coerceIn(0f, 1f)
                val w1 = (p shr 16 and 0xFF) / 255f
                val w2 = (p shr 8 and 0xFF) / 255f
                out[i] = (a shl 24) or ((w1 * t * 255f).roundToInt() shl 16) or ((w2 * t * 255f).roundToInt() shl 8) or (t * 255f).roundToInt()
            }
        }
        if (id.blur * d >= 0.75f) focus(out, b.width, b.height, id.blur * d)
        if (id.occludes) addFront(out, b)
        return Sheet(b.left, b.top, b.width, b.height, out)
    }

    /** Softens a sheet (premultiplied, so its edge blurs into nothing, not into black). */
    private fun focus(p: IntArray, w: Int, h: Int, sigmaPx: Float) {
        val a = FloatArray(p.size)
        val rr = FloatArray(p.size)
        val gg = FloatArray(p.size)
        val bb = FloatArray(p.size)
        for (i in p.indices) {
            val v = p[i]
            val k = (v ushr 24) / 255f
            a[i] = k
            rr[i] = (v shr 16 and 0xFF) * k
            gg[i] = (v shr 8 and 0xFF) * k
            bb[i] = (v and 0xFF) * k
        }
        val radius = max(1, (sigmaPx - 0.5f).roundToInt())
        for (plane in arrayOf(a, rr, gg, bb)) blur(plane, w, h, radius)
        for (i in p.indices) {
            val k = a[i]
            if (k < 1f / 512f) {
                p[i] = 0
                continue
            }
            fun ch(v: Float) = (v / k).roundToInt().coerceIn(0, 255)
            p[i] = ((k * 255f).roundToInt().coerceIn(0, 255) shl 24) or (ch(rr[i]) shl 16) or (ch(gg[i]) shl 8) or ch(bb[i])
        }
    }

    /** The shade of what stands in front of [id], close by and over a hand's width; the sky takes only a faint shade. */
    private fun aoFor(id: SheetId): AoField {
        if (front.all { it == 0f }) return AoField(null, null, 0f, 0f)
        return if (id == SheetId.SKY) {
            AoField(blurred(front, 9), null, 0.08f, 0f)
        } else {
            AoField(blurred(front, 1), blurred(front, 5), 0.18f, 0.2f)
        }
    }

    private fun blurred(src: FloatArray, r: Int): FloatArray = src.copyOf().also { blur(it, gw, gh, r) }

    private inner class AoField(val near: FloatArray?, val wide: FloatArray?, val kNear: Float, val kWide: Float) {
        fun at(x: Int, y: Int): Float {
            if (near == null) return 1f
            val gx = x / (AO_CELL * d) - 0.5f
            val gy = y / (AO_CELL * d) - 0.5f
            var v = 1f - kNear * sample(near, gx, gy)
            if (wide != null) v *= 1f - kWide * sample(wide, gx, gy)
            return v
        }

        private fun sample(g: FloatArray, gx: Float, gy: Float): Float {
            val i = gx.toInt().coerceIn(0, gw - 2)
            val j = gy.toInt().coerceIn(0, gh - 2)
            val fx = (gx - i).coerceIn(0f, 1f)
            val fy = (gy - j).coerceIn(0f, 1f)
            val a = g[j * gw + i]
            val b = g[j * gw + i + 1]
            val c = g[(j + 1) * gw + i]
            val e = g[(j + 1) * gw + i + 1]
            return a + (b - a) * fx + (c - a + (e - c - b + a) * fx) * fy
        }
    }

    /** Adds what [p] covers to the grid of what stands in front. */
    private fun addFront(p: IntArray, b: Build) {
        val cell = AO_CELL * d
        val sum = FloatArray(gw * gh)
        val count = FloatArray(gw * gh)
        for (y in 0 until b.height) {
            val gy = ((b.top + y) / cell).toInt().coerceIn(0, gh - 1)
            for (x in 0 until b.width) {
                val gx = ((b.left + x) / cell).toInt().coerceIn(0, gw - 1)
                sum[gy * gw + gx] += (p[y * b.width + x] ushr 24) / 255f
                count[gy * gw + gx] += 1f
            }
        }
        val area = cell * cell
        for (i in front.indices) if (count[i] > 0f) front[i] = max(front[i], (sum[i] / area).coerceAtMost(1f))
    }

    /** A soft shadow of a sheet: its coverage at half resolution, blurred by [blurDp]. */
    fun shadow(s: Sheet, blurDp: Float): Sheet {
        val f = 2
        val radius = max(1, (blurDp * d / f - 0.5f).roundToInt())
        val pad = radius * 3 + 2
        val w = s.width / f + 2 * pad
        val h = s.height / f + 2 * pad
        val v = FloatArray(w * h)
        for (y in 0 until s.height) for (x in 0 until s.width) {
            val a = (s.pixels[y * s.width + x] ushr 24) / 255f
            if (a > 0f) v[(y / f + pad) * w + x / f + pad] += a / (f * f)
        }
        blur(v, w, h, radius)
        val px = IntArray(w * h) { i -> ((v[i] * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF }
        return Sheet(s.left - pad * f, s.top - pad * f, w, h, px, scale = f)
    }

    // --- sprites ------------------------------------------------------------------------

    /** A sprite of [m] relative to an anchor at canvas pixel ([ax], [ay]), finished like a sheet. */
    fun sprite(m: Mask, ax: Float, ay: Float, kind: PaperKind, seed: Int, blurDp: Float, weights: (xDp: Float, yDp: Float, i: Int) -> FloatArray): Sheet {
        val g = grain.field(seed, kind, m.left, m.top, m.width, m.height)
        val p = IntArray(m.v.size)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            val i = y * m.width + x
            val k = m.v[i]
            if (k <= 0.002f) continue
            val w = weights(dpX(m.left + x), dpY(m.top + y), i)
            val t = (w[2] * g.at(m.left + x, m.top + y) / Inks.T_SCALE).coerceIn(0f, 1f)
            val a = (k * w[3]).coerceIn(0f, 1f)
            p[i] = ((a * 255f).roundToInt() shl 24) or ((w[0] * t * 255f).roundToInt() shl 16) or ((w[1] * t * 255f).roundToInt() shl 8) or (t * 255f).roundToInt()
        }
        if (blurDp * d >= 0.75f) focus(p, m.width, m.height, blurDp * d)
        return Sheet(m.left - ax.roundToInt(), m.top - ay.roundToInt(), m.width, m.height, p)
    }

    // --- helpers ------------------------------------------------------------------------

    /**
     * [e] cut by an uneven hand: every point is pushed along the line's normal by a smooth
     * wander over the distance along the line ([scales] dp), at most [amp] dp.
     */
    fun wobbled(e: Outline, seed: Int, amp: Float, closed: Boolean, scales: FloatArray = floatArrayOf(7f, 19f, 53f), amps: FloatArray = floatArrayOf(0.35f, 0.6f, 1f)): Outline {
        val n = e.size
        if (n < 3 || amp <= 0f) return e
        val s = FloatArray(n)
        for (i in 1 until n) s[i] = s[i - 1] + kotlin.math.hypot(e.xs[i] - e.xs[i - 1], e.ys[i] - e.ys[i - 1])
        val off = FloatArray(n)
        var peak = 1e-6f
        for (i in 0 until n) {
            var v = 0f
            for (k in scales.indices) v += amps[k] * Noise.at((s[i] / scales[k] + seed * 7.31f + k * 31.7f).toDouble()).toFloat()
            off[i] = v
            peak = max(peak, abs(v))
        }
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val a = if (i > 0) i - 1 else if (closed) n - 1 else 0
            val b = if (i < n - 1) i + 1 else if (closed) 0 else n - 1
            val tx = e.xs[b] - e.xs[a]
            val ty = e.ys[b] - e.ys[a]
            val l = kotlin.math.hypot(tx, ty).coerceAtLeast(1e-6f)
            val k = off[i] / peak * amp
            xs[i] = e.xs[i] - ty / l * k
            ys[i] = e.ys[i] + tx / l * k
        }
        return Outline(xs, ys)
    }

    /** Smooth 1D noise over [n] samples, with features [scales] samples long. */
    fun noise1(n: Int, seed: Int, scales: FloatArray, amps: FloatArray): FloatArray {
        val out = FloatArray(n)
        var peak = 1e-6f
        for (i in 0 until n) {
            var v = 0f
            for (k in scales.indices) v += amps[k] * Noise.at((i / scales[k] + seed * 3.17f + k * 19.3f).toDouble()).toFloat()
            out[i] = v
            peak = max(peak, abs(v))
        }
        for (i in 0 until n) out[i] /= peak
        return out
    }
}

/** A box blur (three passes) over a plane of [w] × [h], edges clamped. */
internal fun blur(p: FloatArray, w: Int, h: Int, r: Int) {
    if (r <= 0 || w <= 0 || h <= 0) return
    val tmp = FloatArray(max(w, h))
    val norm = 1f / (2 * r + 1)
    repeat(3) {
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += p[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[x] = acc * norm
                acc += p[row + (x + r + 1).coerceAtMost(w - 1)] - p[row + (x - r).coerceAtLeast(0)]
            }
            System.arraycopy(tmp, 0, p, row, w)
        }
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += p[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                tmp[y] = acc * norm
                acc += p[(y + r + 1).coerceAtMost(h - 1) * w + x] - p[(y - r).coerceAtLeast(0) * w + x]
            }
            for (y in 0 until h) p[y * w + x] = tmp[y]
        }
    }
}
