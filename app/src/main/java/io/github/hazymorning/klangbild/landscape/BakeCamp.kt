package io.github.hazymorning.klangbild.landscape

import io.github.hazymorning.klangbild.landscape.PaperBake.TENT_HALF
import io.github.hazymorning.klangbild.landscape.PaperBake.TENT_HEIGHT
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the tent stands: a little behind the fire, on the same ground. */
internal val Baker.tentFoot: Float get() = fg.fireY - 5f

/**
 * A small paper tent by the fire. Its front is a triangle of canvas that sags a little between
 * its poles, the side wall runs back to the right, the door is a dark triangle with its flap
 * folded back, and the tip of the ridge pole shows above. At night a lantern lights the door
 * from inside (drawn as light, see [PaperBaked.doorX]).
 */
internal fun Baker.tent(): Build {
    val cx = fg.tentX
    val foot = tentFoot
    val hw = TENT_HALF
    val h = TENT_HEIGHT
    val ax = cx + 1.5f
    val ay = foot - h
    fun sag(x0: Float, y0: Float, x1: Float, y1: Float, depth: Float): List<Pair<Float, Float>> = (0..10).map { i ->
        val t = i / 10f
        val nx = -(y1 - y0)
        val ny = x1 - x0
        val l = kotlin.math.hypot(nx, ny)
        val k = depth * 4f * t * (1f - t) / l
        (x0 + (x1 - x0) * t + nx * k) to (y0 + (y1 - y0) * t + ny * k)
    }
    val face = wobbled(Outline.of(sag(ax, ay, cx + hw, foot, 0.9f) + sag(cx + hw, foot, cx - hw, foot, -0.4f).drop(1) + sag(cx - hw, foot, ax, ay, 0.9f).drop(1)), 811, 0.25f, closed = true)
    val side = wobbled(Outline.of(listOf(ax to ay, (ax + hw * 0.44f) to (ay + 3f), (cx + hw * 1.45f) to (foot - 3.5f), (cx + hw) to foot)), 812, 0.2f, closed = true)
    val doorTop = foot - h * 0.6f
    val door = Outline.of(listOf((ax - 0.5f) to doorTop, (cx + hw * 0.3f) to foot, (cx - hw * 0.24f) to foot))
    val flap = Outline.of(listOf((ax - 0.5f) to doorTop, (cx - hw * 0.24f) to foot, (cx - hw * 0.5f) to (foot - 0.7f)))
    val pole = Shapes.stroke(ax, ay + 1f, ax + 0.3f, ay - 2.2f, ax + 0.6f, ay - 5f, 1.5f, 1.1f)
    val b = build(SheetId.TENT, cx - hw - 4f, ay - 7f, cx + hw * 1.45f + 4f, foot + 4f)
    // The side wall turns to the right; the front faces the viewer, so it stands halfway between the lights.
    lay(b, mask(listOf(side)), c(1f), ZERO, c(0.9f), aoCast = 0.2f, aoSigma = 2f)
    lay(b, mask(listOf(face)), c(0.5f), ZERO, Weight { _, y -> 1.02f - 0.08f * Shapes.smoothstep(ay, foot, y) }, aoCast = 0.25f, aoSigma = 2f)
    lay(b, mask(listOf(door)), ZERO, c(1f))
    lay(b, mask(listOf(flap)), c(0.8f), ZERO, c(1.12f), aoCast = 0.2f, aoSigma = 1f)
    lay(b, mask(listOf(pole)), ZERO, ZERO, c(0.5f))
    return b
}

/** Snow along the tent's ridge and on top of its side wall. */
private fun Baker.tentSnow(snow: Float): List<Outline> {
    val cx = fg.tentX
    val foot = tentFoot
    val ax = cx + 1.5f
    val ay = foot - TENT_HEIGHT
    val depth = 2.5f + 4f * snow
    val left = Outline.of((0..10).map { i -> val t = i / 10f; (ax - TENT_HALF * 0.55f * t) to (ay + TENT_HEIGHT * 0.55f * t) } +
        (10 downTo 0).map { i -> val t = i / 10f; (ax - TENT_HALF * 0.55f * t + 0.6f) to (ay + TENT_HEIGHT * 0.55f * t + depth * (0.6f + 0.4f * sin(i * 1.7f))) })
    val hw = TENT_HALF
    val ridge = Outline.of(listOf((ax - 0.5f) to (ay - 0.8f), (ax + hw * 0.46f) to (ay + 2.2f), (cx + hw * 1.46f) to (foot - 4f - 0.2f * depth), (cx + hw * 1.36f) to (foot - 3.5f + 0.5f * depth), (ax + hw * 0.4f) to (ay + 3f + depth), (ax - 0.5f) to (ay + depth)))
    return listOf(left, ridge)
}

/**
 * Boulders of folded gray card on the meadow, their faces toward the light lit, and snow on
 * them and on the tent's ridge where the season has it.
 */
internal fun Baker.props(): Build? {
    val rocks = fg.rocks.mapIndexed { i, r -> rock(r, 840 + i) }
    val snow = shapes.snow
    val tent = if (snow > 0.05f) tentSnow(snow) else emptyList()
    val all = rocks.map { it.first } + tent
    if (all.isEmpty()) return null
    val b = build(SheetId.PROPS, all.minOf { it.minX() } - 4f, all.minOf { it.minY() } - 4f, all.maxOf { it.maxX() } + 4f, all.maxOf { it.maxY() } + 4f)
    for ((i, rock) in rocks.withIndex()) {
        val (outline, crease) = rock
        val r = fg.rocks[i]
        lay(b, mask(listOf(outline)), Weight { x, y -> (((x - crease(y)) * d) + 0.5f).coerceIn(0f, 1f) }, ZERO, Weight { _, y -> 1.04f - 0.12f * Shapes.smoothstep(r.y - r.h, r.y, y) }, aoCast = 0.25f, aoSigma = 1.5f)
        if (snow > 0.05f) {
            val cap = Outline.of((0..16).map { k -> val t = k / 16f; (r.x - r.w * 0.6f + r.w * 1.2f * t) to (r.y - r.h * (0.72f - 0.35f * snow) + 1.2f * sin(t * 9f + i)) } + listOf((r.x + r.w) to (r.y - r.h * 2f), (r.x - r.w) to (r.y - r.h * 2f)))
            lay(b, intersect(mask(listOf(outline)), mask(listOf(cap))), ZERO, c(1f))
        }
    }
    if (tent.isNotEmpty()) lay(b, mask(tent), ZERO, c(1f), c(1.02f))
    return b
}

/** A boulder for [r]: its outline (rounded above, flat below) and its crease (x at a height). */
private fun Baker.rock(r: Rock, seed: Int): Pair<Outline, (Float) -> Float> {
    val rnd = Rnd(seed)
    val pts = (0..28).map { k ->
        val a = PI.toFloat() * (1f + k / 28f)
        val bulge = 1f + 0.12f * sin(3f * a + seed) + 0.05f * rnd.normal()
        (r.x + r.w / 2f * cos(a) * bulge) to (r.y + r.h * sin(a) * bulge.coerceAtMost(1.1f))
    } + listOf((r.x + r.w / 2f) to (r.y + 1.5f), (r.x - r.w / 2f) to (r.y + 1.5f))
    val outline = wobbled(Outline.of(pts), seed, 0.3f, closed = true)
    val topX = r.x + r.w * (0.05f + 0.1f * rnd.normal())
    val footX = r.x + r.w * 0.18f
    return outline to { y -> topX + (footX - topX) * ((y - (r.y - r.h)) / r.h).coerceIn(0f, 1f) }
}

/** The campfire's wood: a ring of stones and two logs crossed over the embers, their cut ends showing. */
internal fun Baker.firewood(): Build {
    val rnd = Rnd(71)
    val fx = fg.fireX
    val fy = fg.fireY
    val b = build(SheetId.FIREWOOD, fx - 36f, fy - 16f, fx + 36f, fy + 18f)
    class Stone(val x: Float, val y: Float, val r: Float, val outline: Outline)
    val stones = (0 until 12).map { k ->
        val a = PI.toFloat() * k / 11f
        val sx = fx - 27f * cos(a)
        val sy = fy + 4f + 7f * sin(a)
        val r = 3.9f + 1.4f * rnd.next()
        val pts = (0 until 30).map { i ->
            val t = 2 * PI.toFloat() * i / 30f
            (sx + r * 1.3f * cos(t) * (1f + 0.1f * sin(3 * t + k))) to (sy + r * 0.85f * sin(t))
        }
        Stone(sx, sy, r, wobbled(Outline.of(pts), 700 + k, 0.25f, closed = true))
    }.sortedBy { it.y }
    fun stone(s: Stone) = lay(
        b, mask(listOf(s.outline)), c(1f), ZERO,
        Weight { x, y -> 1f + 0.16f * (1f - Shapes.smoothstep(s.y - s.r * 0.7f, s.y + s.r * 0.3f, y)) - 0.1f * Shapes.smoothstep(s.x - s.r, s.x + s.r * 1.2f, x) },
        aoCast = 0.3f, aoSigma = 1.2f,
    )
    stones.take(6).forEach { stone(it) }
    val logs = listOf(
        Outline.of(listOf(fx - 24f to fy + 6f, fx + 2f to fy - 11f, fx + 6.5f to fy - 7.5f, fx - 19.5f to fy + 10f)),
        Outline.of(listOf(fx + 24f to fy + 6f, fx - 2f to fy - 11f, fx - 6.5f to fy - 7.5f, fx + 19.5f to fy + 10f)),
    )
    for (log in logs) lay(b, mask(listOf(log)), ZERO, ZERO, aoCast = 0.3f, aoSigma = 1f)
    for (side in listOf(-1f, 1f)) lay(b, mask(listOf(Shapes.ellipse(fx + side * 22f, fy + 8.2f, 3f, 3.3f))), ZERO, c(1f))
    stones.drop(6).forEach { stone(it) }
    return b
}

/**
 * The campfire's flames: tongues of crumpled tissue, yellow at the heart, then orange, red at
 * the tips, and a little translucent at their torn edges. Each flickers on its own.
 */
internal fun Baker.flames(): List<Flame> {
    val fx = fg.fireX
    val fy = fg.fireY
    val base = fy - 2f
    return TONGUES.sortedByDescending { it[1] }.mapIndexed { k, (ox0, hh0, hw0, lean) ->
        val ox = ox0 * FLAME_SCALE
        val hh = hh0 * FLAME_SCALE
        val hw = hw0 * FLAME_SCALE
        val n = 70
        val right = ArrayList<Pair<Float, Float>>()
        val left = ArrayList<Pair<Float, Float>>()
        // Torn edges, as ragged as the tongue is wide: the tip itself stays a clean point.
        val tornRight = noise1(n + 1, 300 + k, floatArrayOf(3f, 7f, 17f), floatArrayOf(0.6f, 0.8f, 0.5f))
        val tornLeft = noise1(n + 1, 310 + k, floatArrayOf(3f, 7f, 17f), floatArrayOf(0.6f, 0.8f, 0.5f))
        for (i in 0..n) {
            val v = i / n.toFloat()
            val wv = hw * sin(PI.toFloat() * min(1f, v * 1.15f + 0.05f)).coerceAtLeast(0f).pow(0.85f) * (1f - v).pow(0.35f)
            val xc = fx + ox + lean * hh * v * v + 1.5f * sin(v * 5f + ox)
            val rag = 0.6f * min(1f, wv / 2.5f)
            right += (xc + wv + rag * tornRight[i]) to (base - v * hh)
            left += (xc - wv - rag * tornLeft[i]) to (base - v * hh)
        }
        val outline = Outline.of(right + left.reversed())
        val m = mask(listOf(outline), pad = (2f * d).toInt() + 3)!!
        val soft = m.v.copyOf().also { blur(it, m.width, m.height, max(1, (0.9f * d - 0.5f).roundToInt())) }
        val footX = px(fx + ox)
        val footY = px(base)
        val sheet = sprite(m, footX, footY, PaperKind.WHITE, 330 + k, blurDp = 0f) { x, y, i ->
            val u = ((base - y) / hh).coerceIn(0f, 1f)
            val w1: Float
            val w2: Float
            if (u < 0.45f) {
                w1 = u / 0.45f; w2 = 0f
            } else {
                val f = (u - 0.45f) / 0.55f
                w1 = 1f - f; w2 = f
            }
            val crumple = 0.86f + 0.22f * (1f - abs(Noise.at(x / 3.2 + k * 13.1, y / 3.2 - k * 7.7).toFloat()))
            // Tissue: a little translucent, more so at the torn edge.
            val edge = soft[i]
            floatArrayOf(w1, w2, crumple * (1f + 0.3f * (1f - u)), 0.82f + 0.16f * Shapes.smoothstep(0.5f, 0.95f, edge))
        }
        Flame(sheet, footX, footY, k)
    }
}

/** The flames' tongues: offset from the fire's middle, height, half width, lean (dp, before [FLAME_SCALE]). */
private val TONGUES = listOf(
    floatArrayOf(-9f, 26f, 8.5f, -0.25f), floatArrayOf(8.5f, 30f, 8f, 0.22f), floatArrayOf(0f, 42f, 10.5f, 0.05f),
    floatArrayOf(-3f, 22f, 6.5f, -0.1f), floatArrayOf(4f, 18f, 6f, 0.15f),
)
private const val FLAME_SCALE = 1.25f
