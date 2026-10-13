package io.github.hazymorning.klangbild.landscape

import io.github.hazymorning.klangbild.landscape.PaperBake.WOOD_FAR_BOTTOM
import io.github.hazymorning.klangbild.landscape.PaperBake.WOOD_NEAR_BOTTOM
import io.github.hazymorning.klangbild.math.Rnd
import io.github.hazymorning.klangbild.math.smoothstep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The far wood: a gentle line of small firs standing close, cut as one silhouette. */
internal fun Baker.woodFar(): Build {
    val rnd = Rnd(41)
    val ground = cut(PaperMotif.woodFar, 41, amp = 0.5f, closedTo = WOOD_FAR_BOTTOM)
    val line = Shapes.line(PaperMotif.woodFar)
    val b = build(SheetId.WOOD_FAR, -o, 294f, PaperMotif.WIDTH + o, WOOD_FAR_BOTTOM)
    lay(b, mask(listOf(ground)), ZERO, groundSnow(0.92f))
    val trees = ArrayList<Triple<Float, Float, Float>>()
    var x = -16f
    while (x < PaperMotif.WIDTH + 20f) {
        val s = sin(x * 0.013f)
        val h = (14f + 6f * s * s) * (0.66f + 0.5f * rnd.next())
        trees += Triple(line.yAt(x) + h * 0.08f + 2f * rnd.next() + 1f, x, h)
        x += 4.2f * (0.5f + 0.8f * rnd.next())
    }
    for ((foot, tx, h) in trees.sortedBy { it.first }) {
        val f = fir(tx, foot, h, rnd, width = 0.48f, jag = 0.7f)
        lay(b, mask(f.tiers + f.trunk), ZERO, ZERO, c(1f + 0.03f * rnd.normal()))
        capFir(b, f)
    }
    return b
}

/**
 * The near wood, dipping into the valley: firs standing close as one silhouette, taller toward
 * the sides, and here and there a broad-leaved tree. Such a tree's trunk goes on the wood and
 * its crown on a sheet of its own ([leaves]), whose inks the season paints.
 */
internal fun Baker.woodNear(leaves: Boolean): Build? {
    val rnd = Rnd(43)
    val line = Shapes.line(PaperMotif.woodNear)
    class Tree(val foot: Float, val x: Float, val h: Float, val broad: Boolean, val tone: Int)
    val trees = ArrayList<Tree>()
    var x = -20f
    while (x < PaperMotif.WIDTH + 30f) {
        val side = abs(x - 520f) / 520f
        val h = (22f + 30f * side.pow(1.2f)) * (0.66f + 0.5f * rnd.next())
        val broad = rnd.next() < BROAD_SHARE
        trees += Tree(line.yAt(x) + h * 0.08f + 2f * rnd.next() + 1f, x, h, broad, rnd.int(3))
        x += (7f + 6f * side) * (0.5f + 0.8f * rnd.next())
    }
    // Cut every tree (the same cuts for both sheets), then lay what belongs on this one.
    class Cut(val tree: Tree, val tone: Float, val fir: FirCut?, val round: RoundTree?)
    val cuts = trees.sortedBy { it.foot }.map { t ->
        val tone = 1f + 0.05f * rnd.normal()
        if (t.broad) Cut(t, tone, null, roundTree(t.x, t.foot, t.h * 0.85f, rnd)) else Cut(t, tone, fir(t.x, t.foot, t.h, rnd, width = 0.48f, jag = 0.7f), null)
    }
    if (leaves) {
        val crowns = cuts.mapNotNull { it.round }
        if (crowns.isEmpty()) return null
        val all = crowns.flatMap { it.discs }
        val b = build(SheetId.LEAVES, all.minOf { it.minX() } - 3f, all.minOf { it.minY() } - 3f, all.maxOf { it.maxX() } + 3f, all.maxOf { it.maxY() } + 3f)
        for (k in cuts) {
            val tree = k.round ?: continue
            // Three colors by the tree's own tone; the crown's lower part in shade.
            lay(b, mask(tree.discs), c(if (k.tree.tone == 1) 1f else 0f), c(if (k.tree.tone == 2) 1f else 0f), crownShade(tree, k.tone))
        }
        return b
    }
    val ground = cut(PaperMotif.woodNear, 43, amp = 0.6f, closedTo = WOOD_NEAR_BOTTOM)
    val b = build(SheetId.WOOD_NEAR, -o, 268f, PaperMotif.WIDTH + o, WOOD_NEAR_BOTTOM)
    lay(b, mask(listOf(ground)), ZERO, groundSnow(0.92f))
    for (k in cuts) {
        val f = k.fir
        if (f != null) {
            lay(b, mask(f.tiers + f.trunk), ZERO, ZERO, c(k.tone))
            capFir(b, f)
        } else if (shapes.leaves >= 0.25f) {
            // Without its leaves the broad tree is gone from the wood: a bare trunk would stand like a post.
            lay(b, mask(listOf(k.round!!.trunk)), ZERO, ZERO, c(0.8f * k.tone))
        }
    }
    return b
}

/**
 * A fir cut by hand: stacked tiers with drooping sides and bough tips along their lower
 * edge, the halves not quite equal, a slight lean; snow along the tiers' upper edges.
 */
internal fun Baker.fir(cx: Float, footY: Float, h: Float, rnd: Rnd, width: Float = 0.46f, jag: Float = 1f, droop: Float = 1.5f, snow: Float = 0f): FirCut {
    var tiers = when {
        h < 14f -> 3
        h < 26f -> 4
        h < 50f -> 5
        h < 110f -> 6
        else -> 7
    }
    if (rnd.next() < 0.3f) tiers++
    val trunkH = h * 0.05f
    val top = footY - h
    val crownH = h - trunkH
    val lean = rnd.normal() * 0.035f
    val asym = 1f + 0.12f * rnd.normal()
    val outlines = ArrayList<Outline>()
    val caps = ArrayList<Outline>()
    for (i in 0 until tiers) {
        val t0 = (i / tiers.toFloat()).pow(1.1f) * 0.9f
        val t1 = min(1f, (i + 1.35f) / tiers)
        val y0 = top + crownH * t0
        val y1 = top + crownH * t1
        val hw = width * h * 0.5f * (0.2f + 0.8f * t1) * (1f + 0.08f * rnd.normal())
        val ax = cx + lean * (y0 - top)
        val hl = hw * asym
        val hr = hw / asym
        fun side(sign: Float, half: Float, t: Float) = (ax + sign * half * t) to (y0 + (y1 - y0) * t.pow(droop) + 0.3f * rnd.normal() * h / 80f)
        val right = (0 until 6).map { side(1f, hr, 0.12f + 0.88f * it / 5f) }
        val left = (0 until 6).map { side(-1f, hl, 1f - 0.88f * it / 5f) }
        val apex = (ax + 0.3f * rnd.normal()) to (y0 - if (i == 0) 1f else 0f)
        val pts = ArrayList<Pair<Float, Float>>()
        pts += apex
        pts += right
        val n = 2 + ((hl + hr) / 14f).toInt()
        for (k in n downTo 0) {
            val x = ax - hl + (hl + hr) * k / n
            if (k in 1 until n) {
                val yb = y1 - (y1 - y0) * 0.18f * jag * (0.5f + 0.9f * rnd.next())
                pts += (x + (hl + hr) / n * (0.35f + 0.3f * rnd.next())) to yb
            }
            pts += x to (y1 + if (k in 1 until n) 0.6f * rnd.normal() * h / 60f else 0f)
        }
        pts += left
        outlines += Outline.of(pts)
        if (snow > 0f) {
            val depth = (y1 - y0) * (0.22f + 0.25f * snow)
            caps += Outline.of(listOf(apex) + right + right.reversed().map { (x, y) -> x to y + depth * (0.7f + 0.3f * sin(x * 0.9f)) })
            val down = left.reversed()
            caps += Outline.of(listOf(apex) + down + down.reversed().map { (x, y) -> x to y + depth * (0.7f + 0.3f * sin(x * 0.7f)) })
        }
    }
    // A slim trunk, only its foot showing under the lowest boughs, a little wider where it meets
    // the ground. Tall firs keep it slender: a trunk as wide as the small ones' would stand like a bell.
    val tw = max(0.55f, min(h * 0.026f, 1.5f + h * 0.011f))
    val trunk = Outline.of(
        listOf(
            cx - tw * 0.85f to footY - trunkH - 4f, cx + tw * 0.85f to footY - trunkH - 4f, cx + tw to footY - 1.2f,
            cx + tw * 1.3f to footY + 1.6f, cx - tw * 1.3f to footY + 1.6f, cx - tw to footY - 1.2f,
        ),
    )
    return FirCut(outlines, caps, trunk, cx, top, lean)
}

/**
 * A broad-leaved tree: a crown of clustered leaf masses (a large one in the middle, smaller
 * ones around its top and sides, its underside flatter) on a short trunk that forks into it.
 */
internal fun Baker.roundTree(cx: Float, footY: Float, h: Float, rnd: Rnd): RoundTree {
    val r = h * 0.36f
    val cy = footY - h + r
    val discs = ArrayList<Outline>()
    discs += wobbled(Shapes.ellipse(cx, cy + r * 0.12f, r * 0.84f, r * 0.76f, segments = 40), (cx * 13).toInt(), 0.5f, closed = true)
    val k = 6
    for (i in 0 until k) {
        val a = PI.toFloat() * (1.05f + 0.9f * i / (k - 1f)) + rnd.normal() * 0.12f
        val rr = r * (0.42f + 0.16f * rnd.next())
        val dx = cos(a) * r * 0.62f
        val dy = sin(a) * r * 0.5f + r * 0.08f
        discs += wobbled(Shapes.ellipse(cx + dx, cy + dy, rr, rr * 0.9f, segments = 32), (cx * 13 + i + 1).toInt(), 0.4f, closed = true)
    }
    val tw = max(0.8f, h * 0.045f)
    val trunk = Outline.of(listOf(cx - tw * 0.8f to cy + r * 0.2f, cx + tw * 0.8f to cy + r * 0.2f, cx + tw * 1.15f to footY - 1f, cx + tw * 1.5f to footY + 2f, cx - tw * 1.5f to footY + 2f, cx - tw * 1.15f to footY - 1f))
    return RoundTree(discs, trunk, cx, cy, r)
}

/**
 * A birch: a slender white trunk, a little bent, with its dark marks; thin twigs rising
 * from its upper half; a crown of small leaf clusters along them (the season's color,
 * gone in winter, when the twigs carry snow instead).
 */
internal fun Baker.birch(cx: Float, footY: Float, h: Float, rnd: Rnd): BirchCut {
    val top = footY - h
    val lean = rnd.normal() * 0.025f
    val tw = max(1.1f, h * 0.03f)
    val tipX = cx + lean * h
    val trunk = Shapes.stroke(cx, footY + 1.5f, cx + lean * h * 0.3f + 1.2f, footY - h * 0.55f, tipX, top + h * 0.08f, tw * 2.3f, tw * 0.6f)
    fun axisAt(y: Float): Float {
        val t = ((footY - y) / h).coerceIn(0f, 1f)
        return cx + (tipX - cx) * t * t + 1.2f * 2f * t * (1f - t)
    }
    val branches = ArrayList<Outline>()
    val twigTips = ArrayList<Pair<Float, Float>>()
    val n = 6
    for (i in 0 until n) {
        val t = 0.42f + 0.48f * i / (n - 1f)
        val by = footY - h * t
        val bx = axisAt(by)
        val dir = if (i % 2 == 0) -1f else 1f
        val len = h * (0.15f + 0.08f * rnd.next()) * (1.1f - 0.4f * t)
        val ex = bx + dir * len
        val ey = by - len * (0.8f + 0.3f * rnd.next())
        branches += Shapes.stroke(bx, by, bx + dir * len * 0.55f, by - len * 0.25f, ex, ey, tw * 0.9f, tw * 0.3f)
        twigTips += ex to ey
    }
    val marks = ArrayList<Outline>()
    var y = footY - h * 0.06f
    while (y > top + h * 0.15f) {
        val ax = axisAt(y)
        val half = tw * (1.1f - 0.5f * (footY - y) / h)
        val w = half * (0.5f + 0.7f * rnd.next())
        val off = (rnd.next() - 0.5f) * half * 0.8f
        marks += Shapes.ellipse(ax + off, y, w, 0.35f + 0.3f * rnd.next(), segments = 12)
        y -= 3.5f + 5f * rnd.next()
    }
    // The crown: an upright oval of overlapping leaf clusters around the upper trunk, and a few at the twigs' tips.
    val crown = ArrayList<Outline>()
    val cy = top + h * 0.37f
    val ry = h * 0.31f
    val rx = h * 0.17f
    var tries = 0
    while (crown.size < 17 && tries < 400) {
        tries++
        val u = rnd.next() * 2f - 1f
        val v = rnd.next() * 2f - 1f
        if (u * u + v * v > 1f) continue
        val yy = cy + v * ry
        val r = h * (0.05f + 0.03f * rnd.next()) * (1f - 0.25f * v)
        crown += wobbled(Shapes.ellipse(axisAt(yy) + u * rx, yy, r * 1.12f, r * 0.86f, segments = 20), (cx * 7 + crown.size).toInt(), 0.3f, closed = true)
    }
    for ((i, tip) in twigTips.withIndex()) {
        if (i % 2 == 1) continue
        val r = h * 0.042f
        crown += wobbled(Shapes.ellipse(tip.first, tip.second + r * 0.3f, r * 1.1f, r * 0.85f, segments = 18), (cx * 11 + i).toInt(), 0.3f, closed = true)
    }
    val snow = if (snowOnTrees() > 0f) branches.map { br ->
        Outline(br.xs, FloatArray(br.ys.size) { br.ys[it] - 0.6f })
    } else {
        emptyList()
    }
    return BirchCut(trunk, branches, marks, crown, snow, cx, tw, top)
}

/**
 * A fir as a stand-up cut-out: a fine margin of the card's light core around it, the
 * trunk (its bark in shade), the tiers folded down the middle, snow on them.
 */
internal fun Baker.standee(b: Build, f: FirCut, margin: Float, aoSigma: Float) {
    val pad = ((margin * 3f + aoSigma * 3f) * d).toInt() + 4
    val all = mask(f.tiers + f.trunk, pad) ?: return
    lay(b, margin(all, margin), ZERO, c(1f), aoCast = 0.22f, aoSigma = aoSigma)
    lay(b, mask(listOf(f.trunk), pad), c(0.2f), ZERO, Weight { x, _ -> 0.5f + 0.1f * ((x - f.axisX) / 3f).coerceIn(-1f, 1f) })
    lay(b, mask(f.tiers, pad), foldOf(f), ZERO)
    capFir(b, f)
}

/** Snow on a fir's tiers, where the season has it. */
internal fun Baker.capFir(b: Build, f: FirCut) {
    if (f.caps.isEmpty()) return
    lay(b, intersect(mask(f.tiers), mask(f.caps)), ZERO, c(0.95f))
}

/** The fir's fold: its right half takes the first ink, sharp but anti-aliased along the crease. */
internal fun Baker.foldOf(f: FirCut) = Weight { x, y -> ((x - (f.axisX + f.lean * (y - f.top))) * d + 0.5f).coerceIn(0f, 1f) }

/** The crown's light: its lower part in shade. */
internal fun Baker.crownShade(t: RoundTree, tone: Float) = Weight { _, y -> tone * (1f - 0.18f * smoothstep(t.cy - t.r * 0.2f, t.cy + t.r * 0.8f, y)) }

/** How much snow the firs carry. */
internal fun Baker.snowOnTrees(): Float = smoothstep(0.25f, 0.8f, shapes.snow)

/** A fir as cut: its tiers, the snow on them, its trunk, and its fold (the axis from its top, leaning). */
internal class FirCut(val tiers: List<Outline>, val caps: List<Outline>, val trunk: Outline, val axisX: Float, val top: Float, val lean: Float)

/** A broad-leaved tree: crown discs, trunk, and the crown's middle and radius (dp). */
internal class RoundTree(val discs: List<Outline>, val trunk: Outline, val cx: Float, val cy: Float, val r: Float)

/**
 * A birch as cut: trunk, twigs, the trunk's marks, the crown's leaf clusters, snow on the twigs;
 * its axis, half width and top (dp).
 */
internal class BirchCut(
    val trunk: Outline,
    val branches: List<Outline>,
    val marks: List<Outline>,
    val crown: List<Outline>,
    val snow: List<Outline>,
    val axis: Float,
    val width: Float,
    val top: Float,
)

/** The share of the near wood's trees that are broad-leaved. */
private const val BROAD_SHARE = 0.1f
