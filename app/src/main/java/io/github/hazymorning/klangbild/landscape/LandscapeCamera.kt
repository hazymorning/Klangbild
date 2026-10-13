package io.github.hazymorning.klangbild.landscape

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the camera stands at one moment: how far it has moved in ([zoom], for the nearest
 * layer) and to the side ([panX], [panY], dp, for the nearest layer), about the screen's middle.
 * Each layer of the diorama takes its share by its depth ([Depth.parallax]): the near banks move
 * most and the sky hardly at all, so the window has depth even when nothing in it moves.
 */
class CameraPose(val zoom: Float, val panX: Float, val panY: Float) {
    fun scale(d: Depth): Float = 1f + (zoom - 1f) * d.parallax
    fun dx(d: Depth): Float = panX * d.parallax
    fun dy(d: Depth): Float = panY * d.parallax
}

/**
 * The camera's slow journey over the diorama, like a hand-held look through a window. It moves
 * in and out (up to [ZOOM_MAX]) and its view wanders across the picture, as far as the paper
 * reaches beyond the screen at that zoom ([PaperMotif.OVERSCAN], with the screen's own drift on
 * top). The music's label stands still on the screen because it is no part of the scenery, and
 * the view never wanders so far that the near bank under the label would leave it: the bank
 * covers the label, with [BANK_MARGIN] to spare, in every pose. A swing in and out takes about
 * two minutes, one across about a minute and a half; the waves are sums of sines with
 * incommensurate periods, so the journey never repeats. The nearer a layer, the more it moves,
 * so the parallax is plain to see.
 */
object LandscapeCamera {
    /** The nearest layer's enlargement: the least and the most. */
    const val ZOOM_MIN = 1.01f
    const val ZOOM_MAX = 1.11f

    /** The view's middle wanders at most this far up and down (dp): the picture is wide, not tall. */
    const val TILT = 16f

    /** The near bank reaches at least this far beyond the label's top and inner end (dp). */
    const val BANK_MARGIN = 8f

    /** The screen's own drift against burn-in, which comes on top (dp). */
    const val DRIFT = 6f

    /** The point the layers scale about (dp): the screen's middle. */
    const val PIVOT_X = PaperMotif.WIDTH / 2f
    const val PIVOT_Y = PaperMotif.HEIGHT / 2f

    /** The pose at rest: the stills and the docs show the whole picture. */
    val REST = CameraPose(ZOOM_MIN, 0f, 0f)

    /** The pose [seconds] into the wall clock's run, for a label on [label]. */
    fun at(seconds: Double, label: LabelSide = LabelSide.NONE): CameraPose {
        val z = 0.5f + 0.5f * swing(seconds, ZOOM_PERIOD_S, 0.0)
        val k = ZOOM_MIN + (ZOOM_MAX - ZOOM_MIN) * z
        val (x0, x1) = rangeX(k, label)
        val cx = x0 + (x1 - x0) * (0.5f + 0.5f * swing(seconds, PAN_PERIOD_S, 1.9))
        val (y0, y1) = rangeY(k, label, cx)
        val cy = y0 + (y1 - y0) * (0.5f + 0.5f * swing(seconds, TILT_PERIOD_S, 4.1))
        return pose(k, cx, cy)
    }

    /** The pose that shows the picture around ([cx], [cy]) (dp) at [zoom]. */
    fun pose(zoom: Float, cx: Float, cy: Float) = CameraPose(zoom, (PIVOT_X - cx) * zoom, (PIVOT_Y - cy) * zoom)

    /**
     * Where the view's middle may stand across at [zoom] (dp): as far as the paper reaches and,
     * with a label, only where its bank can still be brought under it by looking a little lower.
     */
    fun rangeX(zoom: Float, label: LabelSide): Pair<Float, Float> {
        val half = PIVOT_X / zoom
        var lo = -PaperMotif.OVERSCAN + DRIFT + half
        var hi = PaperMotif.WIDTH + PaperMotif.OVERSCAN - DRIFT - half
        if (label != LabelSide.NONE) {
            val top = paperY(zoom).second
            // The farther the view looks toward the label's inner end, the lower it must be for the bank to cover the label.
            fun fits(cx: Float) = lowestFor(cx, zoom, label) <= top
            if (label == LabelSide.LEFT && !fits(hi)) hi = search(lo, hi) { fits(it) }
            if (label == LabelSide.RIGHT && !fits(lo)) lo = search(hi, lo) { fits(it) }
        }
        return lo to max(lo, hi)
    }

    /**
     * Where the view's middle may stand up and down at [zoom] for a view at [cx] (dp): the paper's
     * reach, the label's bank, the tilt.
     */
    fun rangeY(zoom: Float, label: LabelSide, cx: Float = PIVOT_X): Pair<Float, Float> {
        var (low, hi) = paperY(zoom)
        if (label != LabelSide.NONE) low = max(low, lowestFor(cx, zoom, label))
        hi = max(low, hi)
        // Not too far up or down: the middle of what is allowed, the tilt to either side.
        val mid = (low + hi) / 2f
        return max(low, mid - TILT) to min(hi, mid + TILT)
    }

    /** How far up and down the view's middle may stand at [zoom] for the paper to fill the screen (dp). */
    private fun paperY(zoom: Float): Pair<Float, Float> {
        val half = PIVOT_Y / zoom
        return (-PaperMotif.OVERSCAN + DRIFT + half) to (PaperMotif.HEIGHT + PaperMotif.OVERSCAN - DRIFT - half)
    }

    /**
     * The highest the view's middle may stand (its least y, dp) with the view at [cx] and [zoom]
     * so that the near bank still covers the label: every point of the label's top edge, taken
     * back into the picture, lies [BANK_MARGIN] below the bank's top.
     */
    fun lowestFor(cx: Float, zoom: Float, label: LabelSide): Float {
        val bank = bankOf(label) ?: return -Float.MAX_VALUE
        val left = if (label == LabelSide.RIGHT) PaperMotif.LABEL_RIGHT_X else PaperMotif.LABEL_LEFT_X
        val x0 = left - BANK_MARGIN
        val x1 = left + PaperMotif.LABEL_MAX_WIDTH + BANK_MARGIN
        val edge = PaperMotif.LABEL_BOTTOM - PaperMotif.LABEL_HEIGHT - BANK_MARGIN
        var need = -Float.MAX_VALUE
        var x = x0
        while (true) {
            val top = bank.yAt(cx + (x - PIVOT_X) / zoom)
            need = max(need, top - (edge - PIVOT_Y) / zoom)
            if (x >= x1) break
            x = min(x1, x + 6f)
        }
        return need
    }

    /** The bank the label lies on, as a line (or none without a label). */
    private fun bankOf(label: LabelSide): Polyline? = when (label) {
        LabelSide.LEFT -> leftBank
        LabelSide.RIGHT -> rightBank
        LabelSide.NONE -> null
    }

    private val leftBank by lazy { Shapes.line(PaperMotif.foreground(LabelSide.LEFT).frontLeft) }
    private val rightBank by lazy { Shapes.line(PaperMotif.foreground(LabelSide.RIGHT).frontRight) }

    /** The value between [ok] (where [fits] holds) and [bad] closest to [bad] where it still holds. */
    private fun search(ok: Float, bad: Float, fits: (Float) -> Boolean): Float {
        var a = ok
        var b = bad
        repeat(24) {
            val m = (a + b) / 2f
            if (fits(m)) a = m else b = m
        }
        return a
    }

    /**
     * A smooth wave −1..1 with a rough period of [period] seconds: two sines whose periods stand
     * in the golden ratio (they never fall into step), the second weaker.
     */
    private fun swing(t: Double, period: Double, phase: Double): Float {
        val a = sin(2 * PI * t / period + phase)
        val b = sin(2 * PI * t / (period * GOLDEN) + phase * 2.3 + 0.7)
        return (0.68 * a + 0.32 * b).toFloat()
    }

    private const val GOLDEN = 0.6180339887

    /** The rough length of one swing (s): in and out, across, up and down. */
    private const val ZOOM_PERIOD_S = 131.0
    private const val PAN_PERIOD_S = 97.0
    private const val TILT_PERIOD_S = 157.0
}
