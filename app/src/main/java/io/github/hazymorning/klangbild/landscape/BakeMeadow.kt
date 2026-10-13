package io.github.hazymorning.klangbild.landscape

import io.github.hazymorning.klangbild.landscape.PaperBake.MEADOW_BOTTOM
import io.github.hazymorning.klangbild.landscape.PaperBake.TENT_HALF
import io.github.hazymorning.klangbild.landscape.PaperBake.shadowBlurDp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The clearing's meadow. In winter, white patches spread over it until it is all snow. */
internal fun Baker.meadow(): Build {
    val ground = cut(PaperMotif.meadow, 51, amp = 0.9f, closedTo = MEADOW_BOTTOM)
    val b = build(SheetId.MEADOW, -o, 412f, PaperMotif.WIDTH + o, MEADOW_BOTTOM)
    lay(b, mask(listOf(ground)), ZERO, groundSnow(0.95f))
    return b
}

/** The nearer strip of meadow, its edge a fringe of short grass. */
internal fun Baker.meadowNear(): Build {
    val rnd = Rnd(81)
    val ground = cut(PaperMotif.meadowNear, 81, amp = 1f, closedTo = PaperMotif.BOTTOM)
    val line = Shapes.line(PaperMotif.meadowNear)
    val b = build(SheetId.MEADOW_NEAR, -o, 464f, PaperMotif.WIDTH + o, PaperMotif.BOTTOM)
    lay(b, mask(listOf(ground)), ZERO, groundSnow(0.95f))
    lay(b, mask(grass(line, -20f, PaperMotif.WIDTH + 30f, rnd, 2f, 6f)), ZERO, c(0.6f * shapes.snow), c(1.05f))
    return b
}

/** Snow on the ground: patches that spread as the season deepens, cut sharp like pieces of paper; [full] at deep snow. */
internal fun Baker.groundSnow(full: Float): Weight {
    val s = shapes.snow
    if (s <= 0.02f) return ZERO
    val threshold = 1f - 2f * s
    return Weight { x, y ->
        val n = (0.65 * Noise.at(x / 34.0 + 11.3, y / 17.0 - 4.1) + 0.35 * Noise.at(x / 11.0 - 7.7, y / 6.0 + 2.9)).toFloat()
        full * ((n - threshold) * 30f * d + 0.5f).coerceIn(0f, 1f)
    }
}

/** Grass blades along [line] from [from] to [to] (dp): narrow, leaning, uneven in height. */
internal fun Baker.grass(line: Polyline, from: Float, to: Float, rnd: Rnd, low: Float, high: Float): List<Outline> {
    val out = ArrayList<Outline>()
    var x = from
    while (x < to) {
        val foot = line.yAt(x) + 2.5f
        val h = low + (high - low) * rnd.next().pow(1.8f)
        val lean = rnd.normal() * 2.2f
        val w = 1.1f + 0.8f * rnd.next()
        out += Outline.of(listOf(x - w to foot + 2f, x + lean * 0.5f to foot - h * 0.6f, x + lean to foot - h, x + lean * 0.4f + 0.3f to foot - h * 0.55f, x + w to foot + 2f))
        x += 1.2f + 2.2f * rnd.next()
    }
    return out
}

/**
 * Flowers in the meadow's grass in spring and early summer (five petals around a center, in
 * patches) and fallen leaves in autumn. They are smaller on the far meadow and kept clear of
 * the fire, the tent and the stones.
 */
internal fun Baker.blooms(near: Boolean): Build? {
    val flowers = shapes.flowers
    val fallen = shapes.fallen
    if (max(flowers, fallen) < 0.05f) return null
    val line = if (near) Shapes.line(PaperMotif.meadowNear) else meadowLine
    val nearLine = Shapes.line(PaperMotif.meadowNear)
    val rnd = Rnd(if (near) 133 else 131)
    val pieces = ArrayList<Pair<Outline, Int>>()
    val count = ((if (near) 90 else 150) * max(flowers, fallen)).toInt()
    var placed = 0
    var tries = 0
    while (placed < count && tries < count * 40) {
        tries++
        val x = -12f + (PaperMotif.WIDTH + 24f) * rnd.next()
        val top = line.yAt(x) + 5f
        val bottom = if (near) PaperMotif.HEIGHT + 6f else nearLine.yAt(x) + 2f
        if (bottom <= top) continue
        val y = top + (bottom - top) * rnd.next()
        // In patches, never by the fire, the tent or a stone.
        val patch = Noise.at(x / 46.0 + (if (near) 17.0 else 3.0), y / 21.0 - 5.0).toFloat()
        if (patch < 0.08f + 0.5f * rnd.next()) continue
        if (kotlin.math.hypot(x - fg.fireX, (y - fg.fireY) * 1.6f) < 48f) continue
        if (!near && abs(x - fg.tentX - 5f) < TENT_HALF + 16f && y < tentFoot + 6f) continue
        if (fg.rocks.any { abs(x - it.x) < it.w * 0.7f && abs(y - it.y + it.h * 0.4f) < it.h }) continue
        val s = if (near) 1.15f else 0.75f + 0.25f * ((y - top) / (bottom - top))
        placed++
        if (flowers >= fallen) {
            val ink = if (rnd.next() < 0.55f) 0 else 1
            val r = 1.1f * s * (0.8f + 0.4f * rnd.next())
            val turn = rnd.next() * 6.3f
            for (k in 0 until 5) {
                val a = turn + k * 1.2566f
                pieces += Shapes.circle(x + cos(a) * r * 1.05f, y + sin(a) * r * 0.75f, r * 0.72f, segments = 10) to ink
            }
            pieces += Shapes.circle(x, y, r * 0.48f, segments = 8) to 2
        } else {
            val a = rnd.next() * 3.14f
            val rx = 2.1f * s * (0.8f + 0.4f * rnd.next())
            val ry = rx * 0.48f
            val pts = (0 until 12).map { k ->
                val t = k / 12f * 6.283f
                val px = cos(t) * rx * (1f - 0.18f * cos(t))
                val py = sin(t) * ry
                (x + px * cos(a) - py * sin(a)) to (y + px * sin(a) + py * cos(a))
            }
            pieces += Outline.of(pts) to (if (rnd.next() < 0.5f) 1 else 2)
        }
    }
    if (pieces.isEmpty()) return null
    val all = pieces.map { it.first }
    val b = build(if (near) SheetId.BLOOMS_NEAR else SheetId.BLOOMS, all.minOf { it.minX() } - 2f, all.minOf { it.minY() } - 2f, all.maxOf { it.maxX() } + 2f, all.maxOf { it.maxY() } + 2f)
    for (ink in 0..2) {
        val group = pieces.filter { it.second == ink }.map { it.first }
        if (group.isEmpty()) continue
        lay(b, mask(group), c(if (ink == 1) 1f else 0f), c(if (ink == 2) 1f else 0f), c(1.02f))
    }
    return b
}

/**
 * The meadow's cut-outs, each a sprite of its own about its foot so it can sway. The firs have a
 * fine margin of the card's light core and are folded down the middle. The birches have white
 * trunks and twigs and crowns of little leaf clusters in the season's colors (gone in winter).
 */
internal fun Baker.standees(): List<Standee> {
    val rnd = Rnd(61)
    val items = (fg.standees.map { (x, h) -> Triple(x, h, false) } + fg.birches.map { (x, h) -> Triple(x, h, true) }).sortedBy { it.first }
    return items.mapIndexed { k, (x, h, isBirch) ->
        val foot = meadowLine.yAt(x) + 6f
        val parts = ArrayList<Pair<SheetId, Sheet>>()
        if (isBirch) {
            val t = birch(x, foot, h, rnd)
            val crownOn = shapes.leaves > 0.05f
            // The crown stands in front of its twigs: cut first (front to back), then the trunk behind it.
            val crown = if (crownOn) {
                val all = t.crown
                val cb = build(SheetId.CROWNS, all.minOf { it.minX() } - 3f, all.minOf { it.minY() } - 3f, all.maxOf { it.maxX() } + 3f, all.maxOf { it.maxY() } + 3f)
                for ((i, cl) in t.crown.withIndex()) {
                    val tone = 1f + 0.06f * rnd.normal() - 0.12f * Shapes.smoothstep(t.top + h * 0.25f, t.top + h * 0.6f, cl.maxY())
                    lay(cb, mask(listOf(cl)), c(if (i % 3 == 1) 1f else 0f), ZERO, c(tone), aoCast = 0.12f, aoSigma = 1.2f)
                }
                SheetId.CROWNS to finish(cb)
            } else {
                null
            }
            val wood = listOf(t.trunk) + t.branches
            val b = build(SheetId.STANDEES, wood.minOf { it.minX() } - 3f, wood.minOf { it.minY() } - 3f, wood.maxOf { it.maxX() } + 3f, foot + 3f)
            lay(b, mask(t.branches), ZERO, ZERO, c(0.55f))
            lay(b, mask(listOf(t.trunk)), ZERO, c(1f), Weight { xx, _ -> 1.04f - 0.16f * ((xx - t.axis) / t.width).coerceIn(-1f, 1f) }, aoCast = 0.15f, aoSigma = 1.5f)
            lay(b, mask(t.marks), ZERO, ZERO, c(0.42f))
            if (snowOnTrees() > 0f) lay(b, mask(t.snow), ZERO, c(0.95f))
            parts += SheetId.STANDEES to finish(b)
            if (crown != null) parts += crown
        } else {
            val f = fir(x, foot, h, rnd, width = 0.46f, jag = 0.85f, snow = snowOnTrees())
            val all = f.tiers + f.trunk
            val b = build(SheetId.STANDEES, all.minOf { it.minX() } - 4f, all.minOf { it.minY() } - 4f, all.maxOf { it.maxX() } + 4f, all.maxOf { it.maxY() } + 4f)
            standee(b, f, margin = 0.75f, aoSigma = 2f)
            parts += SheetId.STANDEES to finish(b)
        }
        val fx = px(x)
        val fy = px(foot)
        val shadows = parts.map { (_, sh) -> relative(shadow(sh, shadowBlurDp(SheetId.STANDEES.depth)), fx, fy) }
        Standee(fx, fy, parts.map { (id, sh) -> id to relative(sh, fx, fy) }, shadows, k)
    }
}

/**
 * A near bank with its fringe of grass and its tall firs. Snow lies only along its top, so
 * the bank under the label stays dark in every season.
 */
internal fun Baker.front(left: Boolean): Build? {
    val rnd = Rnd(if (left) 91 else 97)
    val knots = if (left) fg.frontLeft else fg.frontRight
    val line = Shapes.line(knots)
    val bank = cut(knots, if (left) 91 else 97, amp = 0.9f).let { e ->
        val cornerX = if (left) -o - 4f else PaperMotif.WIDTH + o + 4f
        Outline(e.xs + floatArrayOf(cornerX), e.ys + floatArrayOf(PaperMotif.BOTTOM))
    }
    // The tall firs are sprites of their own ([frontFirs]). Cutting them here too draws the same
    // random numbers, so the grass keeps its shapes.
    frontFirCuts(left, rnd)
    val parts = listOf(bank)
    val b = build(if (left) SheetId.FRONT_LEFT else SheetId.FRONT_RIGHT, parts.minOf { it.minX() } - 4f, parts.minOf { it.minY() } - 4f, parts.maxOf { it.maxX() } + 4f, PaperMotif.BOTTOM)
    val band = if (shapes.snow > 0.05f) 3f + 7f * shapes.snow else 0f
    lay(b, mask(listOf(bank)), c(0.5f), Weight { x, y -> if (band <= 0f) 0f else 0.9f * (1f - Shapes.smoothstep(band - 0.6f, band + 0.6f, y - line.yAt(x))) })
    val from = if (left) -20f else knots.last().x - 6f
    val to = if (left) knots.last().x + 6f else PaperMotif.WIDTH + 30f
    lay(b, mask(grass(line, from, to, rnd, 3f, 10f)), c(0.55f), c(0.5f * shapes.snow), c(1.06f))
    return b
}

/** The tall firs on the near bank on one side, as the seeded hand cuts them. */
private fun Baker.frontFirCuts(left: Boolean, rnd: Rnd): List<Pair<Fir, FirCut>> =
    fg.frontFirs.filter { (it.x < PaperMotif.WIDTH / 2f) == left }.map { it to fir(it.x, it.footY, it.height, rnd, width = 0.42f, jag = 0.7f, snow = snowOnTrees()) }

/**
 * The tall firs that frame the view, standing on the near bank of one side. They are sprites
 * about their feet, so they sway in the wind, and are cut whole even where they reach beyond
 * the screen. They are baked before their bank, which takes the shade of their feet.
 */
internal fun Baker.frontFirs(left: Boolean): List<Standee> {
    val id = if (left) SheetId.FRONT_LEFT else SheetId.FRONT_RIGHT
    return beyondCanvas {
        frontFirCuts(left, Rnd(if (left) 91 else 97)).mapIndexed { k, (tree, f) ->
            val all = f.tiers + f.trunk
            val b = build(id, all.minOf { it.minX() } - 5f, all.minOf { it.minY() } - 5f, all.maxOf { it.maxX() } + 5f, all.maxOf { it.maxY() } + 5f)
            standee(b, f, margin = 1.0f, aoSigma = 3f)
            val sheet = finish(b)
            val fx = px(f.axisX)
            val fy = px(tree.footY)
            Standee(fx, fy, listOf(id to relative(sheet, fx, fy)), listOf(relative(shadow(sheet, shadowBlurDp(id.depth)), fx, fy)), 40 + k + if (left) 0 else 10)
        }
    }
}

/** [s] placed relative to an anchor at canvas pixel ([ax], [ay]). */
private fun relative(s: Sheet, ax: Float, ay: Float) = Sheet(s.left - ax.roundToInt(), s.top - ay.roundToInt(), s.width, s.height, s.pixels, s.scale)
