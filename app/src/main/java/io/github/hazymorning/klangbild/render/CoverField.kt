package io.github.hazymorning.klangbild.render

import android.graphics.Bitmap
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.CoverAnalysis
import io.github.hazymorning.klangbild.color.CoverPalette
import io.github.hazymorning.klangbild.color.Lab
import io.github.hazymorning.klangbild.color.Lch
import io.github.hazymorning.klangbild.color.Lamp
import io.github.hazymorning.klangbild.color.Oklab
import io.github.hazymorning.klangbild.domain.StageView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One soft shape of the color field: an elliptical pool of one color whose strength falls off
 * from its middle. Shapes far back fall off softly, shapes in front have a clearer shoulder
 * (they are "in focus"). Its place, turn and size move with the field's time
 * ([FieldMotion]).
 */
class FieldBlob(
    /** The color as the wall shows it (opaque ARGB). */
    val color: Int,
    /** The cover's own color it stands for, in linear sRGB. This is what the lights show. */
    val light: FloatArray,
    /** How strongly it covers what lies behind (0..1). */
    val alpha: Float,
    /** Half its longer axis, as a share of the screen's width. */
    val radius: Float,
    /** Shorter axis over longer axis. */
    val aspect: Float,
    /** 0 is very soft (far back), 1 has a clear shoulder (in front). */
    val focus: Float,
    /** Adds light (screen blend) instead of covering (over). Used for the bright shapes in front. */
    val glow: Boolean,
    /** 0 far, 1 middle, 2 near: the nearer, the quicker it drifts (parallax). */
    val depth: Int,
    val homeX: Float,
    val homeY: Float,
    val wanderX: Float,
    val wanderY: Float,
    val periodX: Float,
    val periodY: Float,
    /** Five phases: two per axis, one for the breath. */
    val phase: FloatArray,
    val turnStart: Float,
    val turnSpeed: Float,
    val breath: Float,
    val breathPeriod: Float,
) {
    /**
     * The falloff ([CoverField.falloff]) at [STOPS] + 1 even steps from the middle to the rim.
     * The screen's gradient has exactly these stops (linear between them), and the field
     * computed on the CPU reads the same table. This is cheap and matches the screen exactly.
     */
    val stops: FloatArray = FloatArray(STOPS + 1) { CoverField.falloff(it / STOPS.toFloat(), focus) }

    /** The falloff at [rho] (0 at the middle, 1 at the rim), as the screen draws it. */
    fun strengthAt(rho: Float): Float {
        if (rho >= 1f) return 0f
        val x = rho * STOPS
        val i = x.toInt()
        return stops[i] + (stops[i + 1] - stops[i]) * (x - i)
    }

    companion object {
        const val STOPS = 24
    }
}

/**
 * Cover colors: the cover's colors as a field that fills the whole screen, edge to edge. It has
 * a deep ground and a mass of color for each of the cover's colors, placed where that color sits
 * in the cover and kept apart (the field carries the picture's colors across the wall), with lit
 * cores and light in front. Far shapes are large and very soft, near ones smaller, lighter and a
 * little firmer. This gives depth like an out-of-focus background behind a lens. Over it lies a
 * shade under the text ([veil]).
 */
class FieldScene(
    /** The ground the shapes lie on (opaque ARGB). */
    val base: Int,
    /** The ground's cover color, linear sRGB (for the lights). */
    val baseLight: FloatArray,
    /** Back to front. */
    val blobs: List<FieldBlob>,
    /**
     * Over the shapes: the text shade, for the screen and [CoverField.OVERSCAN_DP] around it.
     * Null where only the lights need the field.
     */
    val veil: Bitmap?,
    val dark: Boolean,
)

object CoverField {
    /** The field reaches this far beyond each screen edge, so the screen's drift (±6 dp) never shows a border. */
    const val OVERSCAN_DP = 18f

    /** The veil's resolution: it only holds soft ramps. */
    const val VEIL_W = 240
    const val VEIL_H = 135

    /** The shade (on paper the brightening) under the text: at least this, more where the field needs it. */
    const val TEXT_SCRIM_MIN = 0.06f
    const val TEXT_SCRIM_MAX = 0.7f

    /** The colors are made a little more vivid once mixed, so where opposite colors meet they stay colorful, not gray. */
    const val SATURATION = 1.2f

    /** The screen's aspect the field is laid out for (the TV's). */
    const val ASPECT = 16f / 9f

    /**
     * The field for a cover ([thumb], [size]² pixels, and its [analysis]). [strength] is the
     * light level (1 = as designed): how colorful the field is. Without a [layout] only the
     * shapes are made (what the lights need). With it the veil is made too: [quietInk], the
     * quietest text, sets the shade under the text so it keeps 4.5:1 wherever the shapes drift.
     * The picture casts no shadow on the field: nothing frames it.
     */
    fun build(
        thumb: IntArray,
        size: Int,
        analysis: CoverAnalysis,
        appearance: Appearance,
        strength: Float,
        layout: StageGrid?,
        quietInk: Int? = null,
    ): FieldScene {
        val dark = appearance == Appearance.DARK
        val s = strength.coerceIn(0.3f, 1.5f)
        val sources = sources(thumb, size, analysis)
        val seed = sources.fold(17) { h, src -> 31 * h + (src.lab.l * 997).toInt() + (src.lab.a * 7919).toInt() + (src.lab.b * 104729).toInt() }
        val rnd = kotlin.random.Random(seed)
        val dominant = sources.maxBy { it.weight }
        val base = tone(dominant.lab, Tier.GROUND, s, dark)
        // Each color gets a mass of its own: a large deep pool where the color sits in the cover,
        // spread over the whole screen and kept apart from the others, so the screen shows the
        // cover's colors side by side instead of one mixed tone. The dominant color lies
        // furthest back, the rarer ones over it.
        val masses = sources.take(5)
        val homes = spread(masses.map { home(it, rnd) }, MIN_APART)
        val blobs = ArrayList<FieldBlob>()
        for ((i, src) in masses.withIndex()) blobs += blob(src, Tier.FAR, homes[i], s, dark, rnd)
        // Inside the most characteristic masses a lighter core sits off the middle. The mass gets
        // depth (a deep rim, a lit core) and changes shape as the two drift apart and together.
        val characteristic = masses.indices.sortedByDescending { masses[it].score }.take(3)
        for (i in characteristic) {
            val h = homes[i]
            val core = floatArrayOf(h[0] + rnd.r(-0.1f, 0.1f), h[1] + rnd.r(-0.1f, 0.1f))
            blobs += blob(masses[i], Tier.MIDDLE, core, s, dark, rnd)
        }
        // In front: one or two pools of light in the cover's brightest colorful tones, on the
        // picture's side of the screen (around and behind it, never under the text).
        val bright = sources.sortedByDescending { it.score * (0.5f + it.lab.l) }.take(if (sources.size >= 3) 2 else 1)
        for ((k, src) in bright.withIndex()) {
            val near = floatArrayOf(if (k == 0) rnd.r(0.04f, 0.2f) else rnd.r(0.2f, 0.34f), if (k == 0) rnd.r(0.08f, 0.35f) else rnd.r(0.65f, 0.92f))
            blobs += blob(src, Tier.NEAR, near, s, dark, rnd)
        }
        val field = FieldScene(base, linear(dominant.lab), blobs, null, dark)
        if (layout == null) return field
        return FieldScene(base, field.baseLight, blobs, veil(field, layout, quietInk), dark)
    }

    /** How far apart (shares of the screen's width) the color masses' homes are pushed. */
    private const val MIN_APART = 0.42f

    private fun kotlin.random.Random.r(a: Float, b: Float) = a + (b - a) * nextFloat()

    /** Where [src] sits in the cover, mapped to the screen: the cover's left on the screen's left, spread a little wider. */
    private fun home(src: Source, rnd: kotlin.random.Random) = floatArrayOf(
        0.5f + (src.x - 0.5f) * 1.8f + rnd.r(-0.15f, 0.15f),
        0.5f + (src.y - 0.5f) * 1.8f + rnd.r(-0.15f, 0.15f),
    )

    /**
     * [homes] pushed apart until no two are closer than [apart] (measured in shares of the
     * screen's width, so a pool is round on the screen), kept on the screen and a little beyond.
     */
    private fun spread(homes: List<FloatArray>, apart: Float): List<FloatArray> {
        repeat(60) {
            for (i in homes.indices) for (j in i + 1 until homes.size) {
                val dx = homes[j][0] - homes[i][0]
                val dy = (homes[j][1] - homes[i][1]) / ASPECT
                val d = hypot(dx, dy).coerceAtLeast(1e-3f)
                if (d >= apart) continue
                // Push them apart along their line. Two in the same place go sideways.
                val ux = if (d > 1e-3f) dx / d else 1f
                val uy = if (d > 1e-3f) dy / d else 0f
                val push = (apart - d) / 2f
                homes[i][0] -= ux * push
                homes[j][0] += ux * push
                homes[i][1] -= uy * push * ASPECT
                homes[j][1] += uy * push * ASPECT
            }
            for (h in homes) {
                h[0] = h[0].coerceIn(-0.04f, 1.04f)
                h[1] = h[1].coerceIn(-0.06f, 1.06f)
            }
        }
        return homes
    }

    // --- the cover's colors -------------------------------------------------------------------

    /** A color of the cover, how much of it there is, where it sits (0..1) and how characteristic it is. */
    private class Source(val lab: Lab, val weight: Float, val x: Float, val y: Float, val score: Float)

    /**
     * The cover's color clusters with their place in the picture: each thumbnail pixel goes to
     * the nearest cluster; the cluster sits where its pixels are on average.
     */
    private fun sources(thumb: IntArray, size: Int, analysis: CoverAnalysis): List<Source> {
        val clusters = analysis.clusters.filter { it.weight >= 0.02f }.ifEmpty { analysis.clusters }.take(6)
        if (clusters.isEmpty()) return listOf(Source(Lab(0.3f, 0f, 0f), 1f, 0.5f, 0.5f, 0f))
        val sx = FloatArray(clusters.size)
        val sy = FloatArray(clusters.size)
        val n = IntArray(clusters.size)
        for (y in 0 until size) for (x in 0 until size) {
            val p = thumb[y * size + x]
            if ((p ushr 24) < 128) continue
            val lab = Oklab.fromArgb(p)
            var best = 0
            var bestD = Float.MAX_VALUE
            for (i in clusters.indices) {
                val d = lab.distanceSq(clusters[i].lab)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            sx[best] += (x + 0.5f) / size
            sy[best] += (y + 0.5f) / size
            n[best]++
        }
        return clusters.mapIndexed { i, c ->
            val cx = if (n[i] > 0) sx[i] / n[i] else 0.5f
            val cy = if (n[i] > 0) sy[i] / n[i] else 0.5f
            // A small vivid area characterizes a cover more than a large gray one.
            val score = sqrt(c.weight) * (c.lab.chroma + 0.02f) * (1f - (2f * c.lab.l - 1.1f).pow(2)).coerceAtLeast(0.2f)
            Source(c.lab, c.weight, cx, cy, score)
        }
    }

    // --- tones ----------------------------------------------------------------------------------

    private enum class Tier { GROUND, FAR, MIDDLE, NEAR }

    /**
     * A color of the cover as one of the field's tones. On the dark wall these are a deep ground,
     * deep pools, lit cores and light in front. Each keeps its hue and carries more of its color
     * than the cover's average does (rich, but never neon). On paper the same steps are pale
     * washes, the pools a shade deeper than the paper, the light in front whiter.
     */
    private fun tone(lab: Lab, tier: Tier, s: Float, dark: Boolean): Int {
        val lch = lab.toLch()
        // A neutral color takes on a slight trace of the lamp's warm hue.
        val neutral = lch.c < 0.025f
        val hue = if (neutral) CoverPalette.WARM_HUE else lch.h
        val c0 = if (neutral) 0.014f else lch.c
        val own = lch.l.coerceIn(0f, 1f)
        val l: Float
        val c: Float
        if (dark) {
            when (tier) {
                Tier.GROUND -> {
                    l = 0.15f + 0.05f * own
                    c = minOf(c0 * 1.0f, 0.07f * s, 0.45f * l)
                }
                Tier.FAR -> {
                    l = 0.21f + 0.08f * own.pow(0.8f)
                    c = minOf(c0 * 1.25f, 0.115f * s, 0.48f * l)
                }
                Tier.MIDDLE -> {
                    l = 0.29f + 0.07f * own.pow(0.8f)
                    c = minOf(c0 * 1.3f, 0.14f * s, 0.46f * l)
                }
                Tier.NEAR -> {
                    l = 0.44f + 0.12f * own
                    c = minOf(c0 * 1.2f, 0.15f * s, 0.34f * l)
                }
            }
        } else {
            when (tier) {
                Tier.GROUND -> {
                    l = 0.925f
                    c = min(c0 * 0.35f, 0.022f * s)
                }
                Tier.FAR -> {
                    l = 0.885f + 0.03f * own
                    c = min(c0 * 0.5f, 0.045f * s)
                }
                Tier.MIDDLE -> {
                    l = 0.86f + 0.03f * own
                    c = min(c0 * 0.6f, 0.055f * s)
                }
                Tier.NEAR -> {
                    l = 0.975f
                    c = min(c0 * 0.25f, 0.02f * s)
                }
            }
        }
        return Oklab.gamutMap(Lamp.apply(Lch(l, c, hue).toLab())).toArgb()
    }

    private fun linear(lab: Lab): FloatArray = Oklab.toLinear(Oklab.gamutMap(lab)).also { for (i in 0..2) it[i] = it[i].coerceIn(0f, 1f) }

    /**
     * One pool of [src]'s color at [tier] around [home]. Far ones are large, very soft and slow;
     * near ones are small, a little firmer and quicker (parallax).
     */
    private fun blob(src: Source, tier: Tier, home: FloatArray, s: Float, dark: Boolean, rnd: kotlin.random.Random): FieldBlob {
        fun r(a: Float, b: Float) = rnd.r(a, b)
        fun turn(fast: Float, slow: Float) = (if (rnd.nextBoolean()) 1 else -1) * r(360f / slow, 360f / fast)
        val w = sqrt(src.weight.coerceIn(0.02f, 1f))
        val color = tone(src.lab, tier, s, dark)
        val light = linear(src.lab)
        val phase = FloatArray(5) { r(0f, 6.2832f) }
        return when (tier) {
            Tier.FAR -> FieldBlob(
                color, light, alpha = 0.92f,
                radius = 0.34f + 0.2f * w, aspect = r(0.6f, 0.85f), focus = 0f, glow = false, depth = 0,
                homeX = home[0], homeY = home[1], wanderX = r(0.1f, 0.16f), wanderY = r(0.1f, 0.16f),
                periodX = r(110f, 160f), periodY = r(120f, 170f), phase = phase,
                turnStart = r(0f, 360f), turnSpeed = turn(200f, 300f), breath = 0.06f, breathPeriod = r(60f, 90f),
            )
            Tier.MIDDLE -> FieldBlob(
                color, light, alpha = 0.8f,
                radius = 0.19f + 0.1f * w, aspect = r(0.55f, 0.8f), focus = 0.3f, glow = false, depth = 1,
                homeX = home[0], homeY = home[1], wanderX = r(0.12f, 0.18f), wanderY = r(0.12f, 0.2f),
                periodX = r(80f, 110f), periodY = r(85f, 120f), phase = phase,
                turnStart = r(0f, 360f), turnSpeed = turn(150f, 220f), breath = 0.07f, breathPeriod = r(45f, 70f),
            )
            else -> FieldBlob(
                color, light, alpha = if (dark) 0.5f else 0.6f,
                radius = r(0.13f, 0.18f), aspect = r(0.5f, 0.72f), focus = 0.25f, glow = true, depth = 2,
                homeX = home[0], homeY = home[1], wanderX = r(0.06f, 0.1f), wanderY = r(0.12f, 0.2f),
                periodX = r(55f, 75f), periodY = r(60f, 85f), phase = phase,
                turnStart = r(0f, 360f), turnSpeed = turn(110f, 160f), breath = 0.08f, breathPeriod = r(35f, 55f),
            )
        }
    }

    /** How a pool's strength falls off from its middle (0) to its rim (1): far ones softly, near ones with a clearer shoulder. */
    fun falloff(rho: Float, focus: Float): Float {
        if (rho >= 1f) return 0f
        val plateau = 0.05f + 0.45f * focus
        val t = ((rho - plateau) / (1f - plateau)).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return (1f - s).pow(1.7f - 0.7f * focus)
    }

    // --- the veil -------------------------------------------------------------------------------

    /**
     * Over the shapes (ARGB, [VEIL_W] × [VEIL_H] for the screen and the overscan around it): the
     * shade under the text (on paper a brightening), as one color and opacity per point.
     * Nothing darkens the screen's edges: there the field meets the light behind the TV.
     */
    private fun veil(field: FieldScene, layout: StageGrid, quietInk: Int?): Bitmap {
        val screen = layout.screen
        val dark = field.dark
        val scrim = textScrim(field, layout, quietInk)
        val over = OVERSCAN_DP
        val w = screen.widthDp + 2 * over
        val h = screen.heightDp + 2 * over
        val px = IntArray(VEIL_W * VEIL_H)
        // The text block's area: from the status line to "Up next", the column's width (in Cover
        // Flow from the farthest left it can stand).
        val tx0 = textLeft(layout) - 16f
        val tx1 = layout.columnLeft + layout.columnWidth + 8f
        val ty0 = layout.statusTop - 20f
        val ty1 = layout.footerBottom + 13f
        val pr = Oklab.srgbToLinear(field.base shr 16)
        val pg = Oklab.srgbToLinear(field.base shr 8)
        val pb = Oklab.srgbToLinear(field.base)
        for (y in 0 until VEIL_H) {
            val py = (y + 0.5f) * h / VEIL_H - over
            for (x in 0 until VEIL_W) {
                val ppx = (x + 0.5f) * w / VEIL_W - over
                // The text's shade: full over the block, fading out softly around it.
                val dx = max(max(tx0 - ppx, ppx - tx1), 0f)
                val dy = max(max(ty0 - py, py - ty1), 0f)
                val text = scrim * exp(-(dx * dx + dy * dy) / (2f * TEXT_SCRIM_SIGMA * TEXT_SCRIM_SIGMA))
                // field × keep + color × alpha. In the dark the shade takes light away; on paper it
                // brightens toward the ground.
                val darken = if (dark) text else 0f
                val lighten = if (dark) 0f else text
                val keep = (1f - darken) * (1f - lighten)
                val alpha = 1f - keep
                val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
                val argb = if (a == 0 || lighten <= 0f) {
                    a shl 24
                } else {
                    // The color that, laid over at [alpha], adds the ground's light: ground × lighten × (1 − darken).
                    fun ch(lin: Float) = (Oklab.linearToSrgb(lin * lighten * (1f - darken) / alpha) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    (a shl 24) or (ch(pr) shl 16) or (ch(pg) shl 8) or ch(pb)
                }
                px[y * VEIL_W + x] = argb
            }
        }
        return Bitmap.createBitmap(px, VEIL_W, VEIL_H, Bitmap.Config.ARGB_8888)
    }

    /**
     * The farthest left the text column's edge can stand (dp): in Cover Flow it moves left when
     * cards are missing ([FlowGeometry.balance]), and the shade under it reaches every place.
     */
    private fun textLeft(layout: StageGrid): Float =
        layout.columnLeft + if (layout.view == StageView.FLOW) FlowGeometry(layout).leftmostColumn else 0f

    /** How soft the text shade's edge is (σ, dp). */
    private const val TEXT_SCRIM_SIGMA = 40f

    /**
     * How strong the shade under the text must be. The field is sampled over the text block
     * through time (its shapes drift), and the quietest text has to keep 4.5:1 (with a margin)
     * against the lightest color it ever stands on (on paper, the darkest).
     */
    fun textScrim(field: FieldScene, layout: StageGrid, quietInk: Int?): Float {
        val ink = quietInk ?: return TEXT_SCRIM_MIN
        val inkY = relativeLuminance(ink)
        val screen = layout.screen
        val out = FloatArray(3)
        var worst = if (field.dark) 0f else 1f
        val u0 = textLeft(layout) / screen.widthDp
        val u1 = (layout.columnLeft + layout.columnWidth) / screen.widthDp
        val v0 = layout.statusTop / screen.heightDp
        val v1 = layout.footerBottom / screen.heightDp
        val places = FloatArray(field.blobs.size * FieldModel.STRIDE)
        var t = 0.0
        while (t < SCRIM_HORIZON_S) {
            FieldModel.places(field, t, places)
            for (i in 0..GRID_U) for (j in 0..GRID_V) {
                val u = u0 + (u1 - u0) * i / GRID_U.toFloat()
                val v = v0 + (v1 - v0) * j / GRID_V.toFloat()
                FieldModel.color(field, u, v, places, out)
                val y = 0.2126f * toLinear(out[0]) + 0.7152f * toLinear(out[1]) + 0.0722f * toLinear(out[2])
                worst = if (field.dark) max(worst, y) else min(worst, y)
            }
            t += SCRIM_STEP_S
        }
        val needed = if (field.dark) {
            // Black over the field at alpha a (in sRGB): luminance × (1 − a)^2.2.
            val allowed = (inkY + 0.05f) / TARGET - 0.05f
            if (worst <= allowed) 0f else 1f - (allowed / worst).coerceIn(0f, 1f).pow(1f / 2.2f)
        } else {
            // The ground over the field: toward the ground's luminance.
            val groundY = relativeLuminance(field.base)
            val needY = TARGET * (inkY + 0.05f) - 0.05f
            if (worst >= needY) 0f else ((needY - worst) / (groundY - worst).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
        }
        return needed.coerceIn(TEXT_SCRIM_MIN, TEXT_SCRIM_MAX)
    }

    /**
     * How far through time the text shade looks (seconds of the field's time), how often, and on
     * how fine a grid over the text. The pools can only move a little between samples, and the
     * target ratio leaves room for that.
     */
    private const val SCRIM_HORIZON_S = 1200.0
    private const val SCRIM_STEP_S = 4.0
    private const val GRID_U = 8
    private const val GRID_V = 12
    private const val TARGET = 4.8f

    /** sRGB (0..1) to linear via a table: the screen has only 256 levels, and the sampling reads many thousands. */
    private val linearTable = FloatArray(256) { Oklab.srgbToLinear(it / 255f) }

    private fun toLinear(v: Float): Float = linearTable[(v * 255f + 0.5f).toInt().coerceIn(0, 255)]

    private fun relativeLuminance(argb: Int): Float =
        0.2126f * Oklab.srgbToLinear(argb shr 16) + 0.7152f * Oklab.srgbToLinear(argb shr 8) + 0.0722f * Oklab.srgbToLinear(argb)
}

/**
 * Where the field's shapes stand at a moment of the field's own time [t] (seconds; it only
 * advances while music plays, scaled by the motion setting). Each drifts around its home on a
 * path of two slow waves per axis (never visibly repeating), turns slowly and breathes a
 * little. The nearer a shape is, the quicker it moves.
 */
object FieldMotion {
    /**
     * Floats per shape in [place]'s output: center x, center y (shares of the screen), turn
     * (degrees), radius (share of the width).
     */
    const val STRIDE = 4

    fun place(b: FieldBlob, t: Double, out: FloatArray, o: Int = 0) {
        out[o] = b.homeX + b.wanderX * (0.65f * wave(t, b.periodX, b.phase[0]) + 0.35f * wave(t, b.periodX * 0.37f, b.phase[1]))
        out[o + 1] = b.homeY + b.wanderY * (0.65f * wave(t, b.periodY, b.phase[2]) + 0.35f * wave(t, b.periodY * 0.43f, b.phase[3]))
        out[o + 2] = ((b.turnStart + b.turnSpeed * t) % 360.0).toFloat()
        out[o + 3] = b.radius * (1f + b.breath * wave(t, b.breathPeriod, b.phase[4]))
    }

    /** sin(2π t / period + phase), exact however long the field has been running. */
    private fun wave(t: Double, period: Float, phase: Float): Float =
        sin(2.0 * PI * ((t / period) % 1.0) + phase).toFloat()
}

/**
 * The field computed point by point, the way the screen composes it: the ground, each shape
 * over it (painted, or added as light) with its falloff, then a little more vivid. It is used
 * for the lights (the color at the screen's edges) and for the text's contrast. The screen draws
 * the shapes on the GPU ([io.github.hazymorning.klangbild.ui.stage.FieldLayer]).
 */
object FieldModel {
    /** Floats per shape in [places]: [FieldMotion.place]'s four, then the turn's cosine and sine. */
    const val STRIDE = 6

    /** Where every shape of [field] stands at time [t]: worked out once, then read for many points. */
    fun places(field: FieldScene, t: Double, out: FloatArray = FloatArray(field.blobs.size * STRIDE)): FloatArray {
        for (i in field.blobs.indices) {
            val b = field.blobs[i]
            val o = i * STRIDE
            FieldMotion.place(b, t, out, o)
            val a = Math.toRadians(out[o + 2].toDouble())
            out[o + 4] = cos(a).toFloat()
            out[o + 5] = sin(a).toFloat()
        }
        return out
    }

    /** The wall's color (sRGB 0..1, before the veil) at ([u], [v]), in shares of the screen, at time [t]. */
    fun color(field: FieldScene, u: Float, v: Float, t: Double, out: FloatArray) = color(field, u, v, places(field, t), out)

    /** The same with the shapes' [places] at hand. */
    fun color(field: FieldScene, u: Float, v: Float, places: FloatArray, out: FloatArray) {
        var r = ((field.base shr 16) and 0xFF) / 255f
        var g = ((field.base shr 8) and 0xFF) / 255f
        var b = (field.base and 0xFF) / 255f
        for (i in field.blobs.indices) {
            val blob = field.blobs[i]
            val a = blob.alpha * strength(blob, u, v, places, i * STRIDE)
            if (a <= 0f) continue
            val sr = ((blob.color shr 16) and 0xFF) / 255f
            val sg = ((blob.color shr 8) and 0xFF) / 255f
            val sb = (blob.color and 0xFF) / 255f
            if (blob.glow) {
                r += sr * a * (1f - r)
                g += sg * a * (1f - g)
                b += sb * a * (1f - b)
            } else {
                r += (sr - r) * a
                g += (sg - g) * a
                b += (sb - b) * a
            }
        }
        saturate(r, g, b, CoverField.SATURATION, out)
    }

    /**
     * The cover's colors at ([u], [v]) at time [t], blended as the screen blends its shapes
     * (linear sRGB). This is what a light there shows.
     */
    fun light(field: FieldScene, u: Float, v: Float, t: Double, out: FloatArray) {
        val places = places(field, t)
        out[0] = field.baseLight[0]
        out[1] = field.baseLight[1]
        out[2] = field.baseLight[2]
        for (i in field.blobs.indices) {
            val blob = field.blobs[i]
            val a = blob.alpha * strength(blob, u, v, places, i * STRIDE)
            if (a <= 0f) continue
            for (k in 0..2) out[k] += (blob.light[k] - out[k]) * a
        }
    }

    /** The shape's falloff at ([u], [v]): into its own turned, stretched frame, then [CoverField.falloff]. */
    private fun strength(b: FieldBlob, u: Float, v: Float, p: FloatArray, o: Int): Float {
        val dx = u - p[o]
        val dy = (v - p[o + 1]) / CoverField.ASPECT
        val c = p[o + 4]
        val s = p[o + 5]
        val x = (dx * c + dy * s) / p[o + 3]
        val y = (-dx * s + dy * c) / (p[o + 3] * b.aspect)
        return b.strengthAt(sqrt(x * x + y * y))
    }

    /** Android's saturation matrix (luma weights 0.213 / 0.715 / 0.072), as the screen applies it. */
    fun saturate(r: Float, g: Float, b: Float, s: Float, out: FloatArray) {
        val lr = 0.213f * (1f - s)
        val lg = 0.715f * (1f - s)
        val lb = 0.072f * (1f - s)
        out[0] = ((lr + s) * r + lg * g + lb * b).coerceIn(0f, 1f)
        out[1] = (lr * r + (lg + s) * g + lb * b).coerceIn(0f, 1f)
        out[2] = (lr * r + lg * g + (lb + s) * b).coerceIn(0f, 1f)
    }
}
