package io.github.hazymorning.klangbild.render

import io.github.hazymorning.klangbild.math.smoothstep
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sign

/**
 * Where the cards of the cover flow stand. The current cover faces the room in the middle,
 * the ones played before stand to the left of it and the ones to come to the right. Those are
 * turned 60° toward the middle, their outer edges nearer, set back in depth, every card in
 * one shared perspective. Each card stands on a glossy floor and is mirrored in it.
 *
 * A card's place is a single number, its position: 0 in the middle, −1 and −2 to the left,
 * 1 and 2 to the right, anything in between while the strip moves. Everything here is plain
 * arithmetic on that number. The UI asks for a card's four corners every frame and draws one
 * textured quad; nothing is laid out, measured or allocated.
 *
 * Measured from the design (at 960 × 540 dp): the middle cover is 300 dp, and the outer edges
 * of the side cards stand 222.5 and 255 dp beside its center, at 92 % of its height.
 */
class FlowGeometry(val layout: StageGrid) {
    /** Side of a card (dp). */
    val side: Float get() = layout.art

    /** Center of the middle card: the perspective's vanishing point (dp). */
    val cx: Float get() = layout.artRect.centerX
    val cy: Float get() = layout.artRect.centerY

    private val s get() = side / BASE_SIDE

    /** The camera's distance from the screen plane (dp). */
    private val camera get() = CAMERA * s

    /** Center of the first side card and the step to the next (dp, in the card's own plane). */
    private val firstX get() = FIRST_X * s
    private val stepX get() = STEP_X * s

    /** Depth of the side cards' centers behind the screen plane (dp). */
    private val sideZ get() = SIDE_Z * s

    /** The mirror image starts this far below a card and is this tall (dp). */
    val gap: Float get() = GAP * s
    val reflection: Float get() = REFLECTION * s

    /** Height of a card with its mirror image (dp). */
    val height: Float get() = side + gap + reflection

    /**
     * The four corners (dp, clockwise from the top-left of the card, the bottom ones at the
     * end of its reflection) of the card at [position], written into [out] as x0, y0 to x3, y3.
     */
    fun corners(position: Float, out: FloatArray) {
        val half = side / 2f
        cornersOf(position, -half, -half, half, half + gap + reflection, out)
    }

    /**
     * The corners of the rectangle from ([left], [top]) to ([right], [bottom]) in the plane of the
     * card at [position] (dp from the card's center, y pointing down), as for [corners].
     */
    fun cornersOf(position: Float, left: Float, top: Float, right: Float, bottom: Float, out: FloatArray) {
        val t = abs(position)
        val dir = sign(position)
        val near = min(t, 1f)
        // Leaving the middle, the card swings aside early and quickly (turning and stepping
        // out together), then glides on to its place. Two cards crossing the middle (one
        // leaving, one arriving) are already clear of each other when they pass, so they never
        // cut through one another and the order in which they are drawn may simply flip.
        val turn = 1f - (1f - near) * (1f - near)
        val angle = SIDE_ANGLE * turn
        val xc = dir * (firstX * turn + stepX * (t - 1f).coerceAtLeast(0f))
        val zc = sideZ * turn
        val c = cos(angle)
        val sn = sin(angle)
        project(left, top, xc, zc, c, sn, dir, out, 0)
        project(right, top, xc, zc, c, sn, dir, out, 2)
        project(right, bottom, xc, zc, c, sn, dir, out, 4)
        project(left, bottom, xc, zc, c, sn, dir, out, 6)
    }

    /** A point (x along the card, y down from its center) of a card at ([xc], [zc]) turned by acos([c]). */
    private fun project(x: Float, y: Float, xc: Float, zc: Float, c: Float, sn: Float, dir: Float, out: FloatArray, i: Int) {
        val wx = xc + x * c
        // The outer edge comes forward: to the right of the middle that is the card's right edge.
        val wz = zc - dir * x * sn
        val k = camera / (camera + wz)
        out[i] = cx + wx * k
        out[i + 1] = cy + y * k
    }

    /**
     * Where the center of the card at [position] stands on screen, beside the middle (dp, from
     * [cx]; the mean of the upper corners).
     */
    fun center(position: Float): Float {
        cornersOf(position, -side / 2f, -side / 2f, side / 2f, -side / 2f, probe)
        return (probe[0] + probe[2]) / 2f - cx
    }

    /** Card centers beside the middle for positions 0, 1/[TABLE_STEPS] and so on, up to [TABLE_END]. */
    private val centers: FloatArray by lazy { FloatArray(TABLE_SIZE) { center(it / TABLE_STEPS.toFloat()) } }

    private val probe = FloatArray(8)

    /**
     * The position of a card on its way from [from] to [to] at eased progress [s] when it rolls
     * in from beyond the strip's end. Its center crosses the screen at an even pace (the far
     * positions stand close together, so moving evenly in position would crawl there and then
     * rush the last step). The card stays on the same side throughout; positions beyond
     * [TABLE_END] count as [TABLE_END].
     */
    fun rolled(from: Float, to: Float, s: Float): Float {
        val dir = if (from != 0f) sign(from) else sign(to)
        val x = at(abs(from)) + (at(abs(to)) - at(abs(from))) * s
        return dir * positionAt(x)
    }

    private fun at(position: Float): Float {
        val i = (position.coerceIn(0f, TABLE_END) * TABLE_STEPS)
        val k = i.toInt().coerceAtMost(TABLE_SIZE - 2)
        return centers[k] + (centers[k + 1] - centers[k]) * (i - k)
    }

    /** The position whose card center stands [x] dp beside the middle (the inverse of [center]). */
    private fun positionAt(x: Float): Float {
        val c = centers
        if (x <= c[0]) return 0f
        if (x >= c[TABLE_SIZE - 1]) return TABLE_END
        var lo = 0
        var hi = TABLE_SIZE - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (c[mid] < x) lo = mid else hi = mid
        }
        return (lo + (x - c[lo]) / (c[hi] - c[lo])) / TABLE_STEPS
    }

    /**
     * How brightly a card at [position] is lit. The middle one is lit fully and the side cards a
     * little less with distance, because the light falls on the middle.
     */
    fun light(position: Float): Float {
        val t = abs(position)
        return 1f - SIDE_DIM * min(t, 1f) - FAR_DIM * (t - 1f).coerceIn(0f, 1f)
    }

    /** How present a card at [position] is: the third card on each side is already gone. */
    fun presence(position: Float): Float = 1f - smoothstep(MAX_SIDE, EDGE, abs(position))

    /**
     * Where the composition stands when only some of the cards are there: [left] cards beside
     * the middle one on the left (what played before), [right] on the right (what comes), at
     * most [MAX_SIDE] each. What is there stands with the text column in the middle of the
     * screen, as much room left of the outermost card as right of the column, measured on what
     * is seen. The column keeps its width; it stands as far from the outermost card to come as
     * in the full strip or, with none to come, as far from the middle card as beside a single cover.
     * With two cards on each side nothing moves.
     */
    fun balance(left: Int, right: Int): Balance {
        val full = reach(MAX_SIDE.toInt())
        val l = reach(left)
        val r = reach(right)
        val gap = if (right > 0) stripGap else heroGap
        // How much shorter than the full strip each part is, the gap to the column included.
        val dl = full - l
        val dr = full - r
        val dg = stripGap - gap
        return Balance(cards = (dr - dl + dg) / 2f, column = 0f - (dr + dl + dg) / 2f)
    }

    /** How far left of its place in the grid the text column can come to stand (dp, at most 0). */
    val leftmostColumn: Float
        get() {
            val most = MAX_SIDE.toInt()
            return (0..most).minOf { l -> (0..most).minOf { r -> balance(l, r).column } }
        }

    /** How far the strip reaches beside the middle card's center with [cards] on that side (dp). */
    private fun reach(cards: Int): Float {
        val n = cards.coerceIn(0, MAX_SIDE.toInt())
        if (n == 0) return side / 2f
        val half = side / 2f
        val corners = FloatArray(8)
        cornersOf(n.toFloat(), -half, -half, half, -half, corners)
        return corners[2] - cx
    }

    /** From the outermost card to come to the text column (dp): as the grid has it. */
    private val stripGap: Float get() = layout.columnLeft - cx - reach(MAX_SIDE.toInt())

    /** From the middle card to the text column when nothing comes (dp): the single cover's gap. */
    private val heroGap: Float get() = StageGrid.ART_TEXT_GAP * layout.screen.widthDp / StageGrid.GRID_WIDTH

    companion object {

        /** The design's card size, to which the other measures belong (dp). */
        const val BASE_SIDE = 300f

        /** How far the side cards are turned (radians, 60°): more would shimmer, less would crowd. */
        val SIDE_ANGLE = Math.toRadians(60.0).toFloat()

        const val CAMERA = 1000f
        const val FIRST_X = 166f
        const val STEP_X = 35.3f
        const val SIDE_Z = 213.3f
        const val GAP = 4f
        const val REFLECTION = 90f

        /** Cards shown on each side. */
        const val MAX_SIDE = 2f

        /** Beyond this position a card is out of sight: cards that arrive come in from here. */
        const val EDGE = MAX_SIDE + 0.75f

        /** The table behind [rolled]: positions up to [TABLE_END] in steps of 1/[TABLE_STEPS]. */
        private const val TABLE_END = 5f
        private const val TABLE_STEPS = 50
        private const val TABLE_SIZE = (TABLE_END * TABLE_STEPS).toInt() + 1

        /** Darkening of the first side card, and more for the next (as in the design: 55 % and 40 %). */
        const val SIDE_DIM = 0.45f
        const val FAR_DIM = 0.15f
    }
}

/**
 * How far the cover flow's composition moves to stand in the middle when only some of its cards
 * are there ([FlowGeometry.balance], dp): the cards with their light by [cards], the text column
 * by [column].
 */
data class Balance(val cards: Float, val column: Float) {
    /** On the way from here to [to], at [s] (0..1). */
    fun towards(to: Balance, s: Float) = Balance(cards + (to.cards - cards) * s, column + (to.column - column) * s)

    companion object {
        /** The full strip: everything where the grid has it. */
        val NONE = Balance(0f, 0f)
    }
}
