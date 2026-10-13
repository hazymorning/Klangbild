package io.github.hazymorning.klangbild.render

import io.github.hazymorning.klangbild.color.Oklab
import io.github.hazymorning.klangbild.math.Rnd
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A float image in linear light, three planes. Blurring and compositing light in linear
 * space looks like real light spreading on a wall; doing it in sRGB looks muddy.
 */
class LinearImage(val width: Int, val height: Int) {
    val r = FloatArray(width * height)
    val g = FloatArray(width * height)
    val b = FloatArray(width * height)

    companion object {
        fun fromArgb(pixels: IntArray, width: Int, height: Int): LinearImage {
            val img = LinearImage(width, height)
            for (i in pixels.indices) {
                val p = pixels[i]
                img.r[i] = Oklab.srgbToLinear(p shr 16)
                img.g[i] = Oklab.srgbToLinear(p shr 8)
                img.b[i] = Oklab.srgbToLinear(p)
            }
            return img
        }
    }
}

object ImageOps {

    /**
     * Approximates a Gaussian blur with [passes] box blurs (separable, O(n) per pass,
     * independent of the radius). Edges clamp.
     */
    fun blur(img: LinearImage, radius: Int, passes: Int = 3) {
        if (radius <= 0) return
        val tmp = FloatArray(max(img.width, img.height))
        for (plane in arrayOf(img.r, img.g, img.b)) {
            repeat(passes) {
                boxBlurH(plane, img.width, img.height, radius, tmp)
                boxBlurV(plane, img.width, img.height, radius, tmp)
            }
        }
    }

    private fun boxBlurH(p: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray) {
        val norm = 1f / (2 * r + 1)
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
    }

    private fun boxBlurV(p: FloatArray, w: Int, h: Int, r: Int, tmp: FloatArray) {
        val norm = 1f / (2 * r + 1)
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

    /**
     * Converts linear light to 8-bit sRGB with triangular dither (±[ditherLsb] LSB). Large
     * soft gradients on an OLED otherwise show visible bands, especially in dark tones.
     * Pixels darker than [blackBelow] become pure black without noise.
     */
    fun toArgbDithered(
        img: LinearImage,
        seed: Int = 1,
        ditherLsb: Float = 1f,
        blackBelow: Float = -1f,
    ): IntArray {
        val out = IntArray(img.width * img.height)
        val rnd = Rnd(seed)
        for (i in out.indices) {
            if (img.r[i] < blackBelow && img.g[i] < blackBelow && img.b[i] < blackBelow) {
                out[i] = 0xFF shl 24 // true black stays black: no dither noise where there is no light
                continue
            }
            val d = (rnd.next() - rnd.next()) * ditherLsb // triangular in -1..1 (× amplitude)
            val r = (Oklab.linearToSrgb(img.r[i]) * 255f + d).roundToInt().coerceIn(0, 255)
            val g = (Oklab.linearToSrgb(img.g[i]) * 255f + d).roundToInt().coerceIn(0, 255)
            val b = (Oklab.linearToSrgb(img.b[i]) * 255f + d).roundToInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /**
     * Dither amplitude (gray levels) for a map that is drawn multiplied by [tint]: about one
     * output level of noise, so even the darkest stretch of a glow never shows bands.
     */
    fun ditherFor(tint: Int): Float {
        val maxC = maxOf((tint shr 16) and 0xFF, (tint shr 8) and 0xFF, tint and 0xFF).coerceAtLeast(16)
        return (1.25f * 255f / maxC).coerceIn(1f, 6f)
    }

    // ---------------------------------------------------------------------------------
    // High quality resampling (separable Lanczos-3). Used once per track to bring the
    // cover to its exact on-screen pixel size, so the GPU never has to stretch it with
    // bilinear filtering (soft) or squeeze it without mipmaps (aliased).
    // ---------------------------------------------------------------------------------

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun lanczos3(x: Double): Double = if (abs(x) < 3.0) sinc(x) * sinc(x / 3.0) else 0.0

    private class Contrib(val start: IntArray, val weights: Array<FloatArray>)

    private fun contributions(src: Int, dst: Int): Contrib {
        val scale = dst.toDouble() / src
        val filterScale = max(1.0, 1.0 / scale) // widen the kernel when downsampling
        val support = 3.0 * filterScale
        val start = IntArray(dst)
        val weights = Array(dst) { FloatArray(0) }
        for (i in 0 until dst) {
            val center = (i + 0.5) / scale
            val left = max(0, floor(center - support).toInt())
            val right = min(src - 1, ceil(center + support).toInt())
            val w = FloatArray(right - left + 1)
            var sum = 0.0
            for (j in left..right) {
                val v = lanczos3((j + 0.5 - center) / filterScale)
                w[j - left] = v.toFloat()
                sum += v
            }
            if (sum != 0.0) for (k in w.indices) w[k] = (w[k] / sum).toFloat()
            start[i] = left
            weights[i] = w
        }
        return Contrib(start, weights)
    }

    /**
     * Resizes sRGB ARGB pixels with Lanczos-3. Works in sRGB (perceptually tighter edges
     * for artwork); results are clamped to avoid ringing overshoot.
     */
    fun resize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src.copyOf()
        val ch = contributions(sw, dw)
        val cv = contributions(sh, dh)
        // horizontal pass → float planes (dw × sh)
        val tr = FloatArray(dw * sh)
        val tg = FloatArray(dw * sh)
        val tb = FloatArray(dw * sh)
        val ta = FloatArray(dw * sh)
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until dw) {
                val s = ch.start[x]
                val w = ch.weights[x]
                var r = 0f
                var g = 0f
                var b = 0f
                var a = 0f
                for (k in w.indices) {
                    val p = src[row + s + k]
                    val wk = w[k]
                    a += ((p ushr 24) and 0xFF) * wk
                    r += ((p shr 16) and 0xFF) * wk
                    g += ((p shr 8) and 0xFF) * wk
                    b += (p and 0xFF) * wk
                }
                val o = y * dw + x
                tr[o] = r; tg[o] = g; tb[o] = b; ta[o] = a
            }
        }
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val s = cv.start[y]
            val w = cv.weights[y]
            for (x in 0 until dw) {
                var r = 0f
                var g = 0f
                var b = 0f
                var a = 0f
                for (k in w.indices) {
                    val o = (s + k) * dw + x
                    val wk = w[k]
                    r += tr[o] * wk; g += tg[o] * wk; b += tb[o] * wk; a += ta[o] * wk
                }
                out[y * dw + x] = (a.roundToInt().coerceIn(0, 255) shl 24) or
                    (r.roundToInt().coerceIn(0, 255) shl 16) or
                    (g.roundToInt().coerceIn(0, 255) shl 8) or
                    b.roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Fast area-average downscale for analysis thumbnails. */
    fun thumbnail(src: IntArray, sw: Int, sh: Int, size: Int): IntArray {
        val out = IntArray(size * size)
        for (ty in 0 until size) {
            val y0 = ty * sh / size
            val y1 = max(y0 + 1, (ty + 1) * sh / size)
            for (tx in 0 until size) {
                val x0 = tx * sw / size
                val x1 = max(x0 + 1, (tx + 1) * sw / size)
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0L
                for (y in y0 until y1) for (x in x0 until x1) {
                    val p = src[y * sw + x]
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                }
                out[ty * size + tx] = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return out
    }
}
