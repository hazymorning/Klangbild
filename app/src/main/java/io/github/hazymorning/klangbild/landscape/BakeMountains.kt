package io.github.hazymorning.klangbild.landscape

import io.github.hazymorning.klangbild.landscape.PaperBake.RANGE_BOTTOM
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/** A range of folded mountains, back to front, each darkening the one behind under its edge. */
internal fun Baker.range(id: SheetId, mountains: List<Mountain>, foot: Float, seed: Int): Build {
    val rnd = Rnd(seed)
    val b = build(id, -o, mountains.minOf { it.summitY } - 4f, PaperMotif.WIDTH + o, RANGE_BOTTOM)
    for ((i, mt) in mountains.withIndex()) {
        val outline = cut(mt.knots, seed * 31 + i, amp = 0.6f)
        lay(b, mask(listOf(outline)), faceRight(mt, foot), faceFront(mt, foot), c(1f + 0.03f * rnd.normal()), aoCast = 0.2f, aoSigma = 5f)
    }
    // Winter: torn caps of snow on the near range too (its third ink is the snow's).
    if (id == SheetId.RANGE_NEAR) {
        val reach = 34f * Shapes.smoothstep(0.6f, 1f, shapes.peaks) + 10f * shapes.snow
        if (reach > 2f) for ((i, mt) in mountains.withIndex()) {
            val outline = cut(mt.knots, seed * 31 + i, amp = 0.6f)
            val cap = capOutline(outline, mt, mt.summitY + reach, foot, seed * 57 + i, tongues = 4f)
            val face = faceRight(mt, foot)
            lay(b, intersect(mask(listOf(outline)), mask(listOf(cap))), Weight { x, y -> 0.25f * face.at(x, y) }, c(0.75f))
        }
    }
    return b
}

/** The far range's caps: torn white paper glued on down to the season's snow line, with tongues down the creases. */
internal fun Baker.snow(): Build? {
    val line = 188f + 212f * shapes.peaks.pow(1.5f)
    val mountains = PaperMotif.farRange
    if (mountains.none { line - it.summitY > 3f }) return null
    val b = build(SheetId.SNOW, -o, mountains.minOf { it.summitY } - 4f, PaperMotif.WIDTH + o, min(RANGE_BOTTOM, line + 24f))
    for ((i, mt) in mountains.withIndex()) {
        if (line - mt.summitY < 3f) continue
        val outline = cut(mt.knots, 2 * 31 + i, amp = 0.6f)
        val cap = capOutline(outline, mt, line, PaperMotif.FAR_FOOT, 400 + i, tongues = 7f)
        // Cut a hair wider than its mountain, as a glued-on piece would be, so the card's edge never shows under it.
        val wide = mask(listOf(outline), pad = (CAP_OVERHANG * 3f * d).toInt() + 4)?.let { margin(it, CAP_OVERHANG) }
        lay(b, intersect(wide, mask(listOf(cap))), faceRight(mt, PaperMotif.FAR_FOOT), faceFront(mt, PaperMotif.FAR_FOOT))
    }
    return b
}

/**
 * The lower edge of a snow cap: torn along [line] (dp), with a tongue down each crease,
 * closed over the mountain's top.
 */
internal fun Baker.capOutline(outline: Outline, mt: Mountain, line: Float, foot: Float, seed: Int, tongues: Float): Outline {
    val x0 = outline.minX() - 2f
    val x1 = outline.maxX() + 2f
    val n = ((x1 - x0) / 0.6f).toInt() + 1
    val xs = FloatArray(n + 2)
    val ys = FloatArray(n + 2)
    val tear = noise1(n, seed, floatArrayOf(5f / 0.6f, 14f / 0.6f, 40f / 0.6f), floatArrayOf(0.45f, 0.8f, 1f))
    for (i in 0 until n) {
        val x = x0 + i * 0.6f
        var tongue = 0f
        for (cb in mt.creases) {
            val cx = mt.summitX + (cb - mt.summitX) * ((line - mt.summitY) / (foot - mt.summitY)).coerceIn(0f, 1f)
            // A wide, shallow V where the fold holds the snow, never a drip.
            val u = (x - cx) / 11f
            tongue += tongues * exp(-u * u)
        }
        xs[i] = x
        ys[i] = line + tongue + 3f * tear[i]
    }
    xs[n] = x1; ys[n] = -o - 20f
    xs[n + 1] = x0; ys[n + 1] = -o - 20f
    return Outline(xs, ys)
}

/** The right face of a mountain (right of its last crease). */
internal fun Baker.faceRight(mt: Mountain, foot: Float) = Weight { x, y ->
    val t = ((y - mt.summitY) / (foot - mt.summitY)).coerceIn(0f, 1f)
    val crease = mt.summitX + (mt.creases.last() - mt.summitX) * t
    ((x - crease) * d + 0.5f).coerceIn(0f, 1f)
}

/** The face between two creases, turned toward the viewer (none with one crease). */
internal fun Baker.faceFront(mt: Mountain, foot: Float): Weight {
    if (mt.creases.size < 2) return ZERO
    return Weight { x, y ->
        val t = ((y - mt.summitY) / (foot - mt.summitY)).coerceIn(0f, 1f)
        val a = mt.summitX + (mt.creases[0] - mt.summitX) * t
        val e = mt.summitX + (mt.creases[1] - mt.summitX) * t
        (((x - a) * d + 0.5f).coerceIn(0f, 1f) - ((x - e) * d + 0.5f).coerceIn(0f, 1f)).coerceAtLeast(0f)
    }
}

/** How far (dp) a snow cap reaches beyond its mountain's edge. */
private const val CAP_OVERHANG = 1.4f
