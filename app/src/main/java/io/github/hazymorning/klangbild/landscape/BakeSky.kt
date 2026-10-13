package io.github.hazymorning.klangbild.landscape

import io.github.hazymorning.klangbild.landscape.PaperBake.SKY_BOTTOM
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The sky: a wash from the zenith's color (base) through the middle one to the horizon's. */
internal fun Baker.sky(): Build {
    val b = build(SheetId.SKY, -o, -o, PaperMotif.WIDTH + o, SKY_BOTTOM)
    for (y in 0 until b.height) {
        val t = (dpY(b.top + y) / SKY_SPAN).coerceIn(0f, 1f)
        val horizon = Shapes.smoothstep(0.38f, 1f, t)
        val middle = Shapes.smoothstep(0f, 0.58f, t) * (1f - horizon)
        for (x in 0 until b.width) b.raster.over(x, y, middle, horizon, 1f / TONE_MAX, 1f, dither = true)
    }
    return b
}

/**
 * A cloud of torn tissue: long strips laid over each other, with torn edges and the lower part
 * a little in shade. Placed relative to its left end and baseline.
 */
internal fun Baker.cloud(i: Int): Sheet {
    val pieces = CLOUDS[i]
    val outlines = pieces.mapIndexed { k, p -> tissue(p[0], p[1], p[2], p[3], 900 + i * 7 + k) }
    // Laid out around a point well inside the canvas, then made relative to it.
    val ox = 200f
    val oy = 200f
    val shifted = outlines.map { it.transformed(1f, ox, oy) }
    val m = mask(shifted, pad = (2f * d).toInt() + 3)!!
    val baseline = pieces[0][1]
    val th = pieces[0][3]
    return sprite(m, px(ox), px(oy), PaperKind.WHITE, 950 + i, blurDp = 0.6f) { _, y, _ ->
        val local = y - oy - baseline
        floatArrayOf(0.7f * Shapes.smoothstep(-th * 0.2f, th * 0.4f, local), 0f, 1f, CLOUD_ALPHA)
    }
}

/** A strip of tissue torn from a sheet: [length] long, [thickness] high, its left end at [x], its baseline at [y]. */
private fun Baker.tissue(x: Float, y: Float, length: Float, thickness: Float, seed: Int): Outline {
    val n = (length * 2f).toInt()
    val swell = noise1(n, seed, floatArrayOf(30f * 2f, 80f * 2f), floatArrayOf(0.6f, 1f))
    val rag = noise1(n, seed + 1, floatArrayOf(3f * 2f, 9f * 2f), floatArrayOf(0.5f, 1f))
    val top = ArrayList<Pair<Float, Float>>()
    val bottom = ArrayList<Pair<Float, Float>>()
    for (i in 0 until n) {
        val u = i / (n - 1f) * 2f - 1f
        val env = sqrt((1f - u * u).coerceAtLeast(0f)).pow(0.8f)
        val px = x + (u + 1f) / 2f * length
        top += px to y - thickness * env * (0.8f + 0.25f * swell[i])
        bottom += px to y + thickness * 0.35f * env + 1.2f * rag[i]
    }
    return wobbled(Outline.of(top + bottom.reversed()), seed + 2, 0.9f, closed = true, scales = floatArrayOf(2.5f, 6f, 15f), amps = floatArrayOf(0.6f, 0.8f, 0.5f))
}

/** A disc of white paper, [radius] dp, cut a little unevenly; placed relative to its middle. */
internal fun Baker.disc(radius: Float, seed: Int): Sheet {
    val ox = 100f
    val oy = 100f
    val circle = wobbled(Shapes.circle(ox, oy, radius, segments = 96), seed, 0.25f, closed = true)
    val m = mask(listOf(circle), pad = 3)!!
    return sprite(m, px(ox), px(oy), PaperKind.WHITE, seed, blurDp = 0f) { _, _, _ -> floatArrayOf(0f, 0f, 1f, 1f) }
}

/** A soft round light, 1 in the middle to 0 at the rim (drawn scaled). */
internal fun Baker.glow(): Sheet {
    val side = 256
    val px = IntArray(side * side)
    val rnd = Rnd(77)
    for (y in 0 until side) for (x in 0 until side) {
        val u = (x + 0.5f) / side * 2 - 1
        val v = (y + 0.5f) / side * 2 - 1
        val r2 = u * u + v * v
        val a = exp(-r2 * 3.2f) * (1f - Shapes.smoothstep(0.7f, 1f, sqrt(r2)))
        px[y * side + x] = ((a * 255f + rnd.next() - 0.5f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
    }
    return Sheet(0, 0, side, side, px)
}

/** The sky's wash reaches the horizon's color this far down (dp). */
private const val SKY_SPAN = 345f

/** The tissue clouds: their strips (left end, baseline, length, thickness; all in dp). */
private val CLOUDS = listOf(
    listOf(floatArrayOf(0f, 0f, 230f, 15f), floatArrayOf(50f, -12f, 120f, 11f)),
    listOf(floatArrayOf(0f, 0f, 180f, 12f)),
    listOf(floatArrayOf(0f, 0f, 300f, 17f), floatArrayOf(110f, -11f, 140f, 12f)),
    listOf(floatArrayOf(0f, 0f, 140f, 10f)),
    listOf(floatArrayOf(0f, 0f, 240f, 14f), floatArrayOf(60f, -9f, 100f, 9f)),
    // Heaps: a wide, flat base and rounded humps of tissue laid over it.
    listOf(floatArrayOf(0f, 0f, 250f, 24f), floatArrayOf(36f, -14f, 140f, 24f), floatArrayOf(98f, -24f, 96f, 22f), floatArrayOf(158f, -9f, 72f, 16f)),
    listOf(floatArrayOf(0f, 0f, 320f, 26f), floatArrayOf(52f, -18f, 160f, 26f), floatArrayOf(140f, -30f, 110f, 24f), floatArrayOf(214f, -12f, 80f, 18f)),
    listOf(floatArrayOf(0f, 0f, 380f, 20f), floatArrayOf(64f, -11f, 200f, 20f), floatArrayOf(92f, -22f, 92f, 16f), floatArrayOf(206f, -18f, 128f, 18f)),
)

/** Tissue lets a little of the sky through. */
private const val CLOUD_ALPHA = 0.93f
