package io.github.hazymorning.klangbild.color

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** A color cluster of the cover in Oklab with its share of the image (0..1). */
data class ColorCluster(val lab: Lab, val weight: Float)

/** A cover's colors as clusters, and its mean lightness (Oklab). */
data class CoverAnalysis(
    val clusters: List<ColorCluster>,
    val meanL: Float,
)

/**
 * The colors of one scene, derived from the cover but tamed: the wall and three levels of
 * ink. The ink is near-neutral with a breath of the cover's hue, never pure white or black,
 * always readable. Values are ARGB ints so this stays pure Kotlin (the UI wraps them in
 * Compose colors).
 */
data class ScenePalette(
    val appearance: Appearance,
    /** The wall: black in the dark; warm paper in the light, faintly tinted by the cover. */
    val wall: Int,
    /** The title. */
    val ink: Int,
    /** Artist, clock and the progress bar's fill. */
    val inkMuted: Int,
    /** Everything else. */
    val inkQuiet: Int,
    /**
     * The progress bar's fill: the artist's level on the plain wall. On the cover's colors
     * ([CoverPalette.derive] with `coverField`) it takes the cover's leading hue, light enough to stand out.
     */
    val accent: Int = inkMuted,
)

object CoverPalette {
    private const val K = 6
    private const val ITERATIONS = 14
    private const val NEUTRAL_CHROMA = 0.035f

    /** Hue [a] moved toward [b] by [t] along the shorter arc (degrees). */
    private fun mixHue(a: Float, b: Float, t: Float): Float {
        var d = (b - a) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return ((a + d * t) % 360f + 360f) % 360f
    }

    /**
     * Analyzes ARGB pixels (typically a 48 to 64 px thumbnail). Deterministic for equal input.
     */
    fun analyze(pixels: IntArray): CoverAnalysis {
        val labs = ArrayList<Lab>(pixels.size)
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            labs += Oklab.fromArgb(p)
        }
        if (labs.isEmpty()) return CoverAnalysis(emptyList(), 0f)
        val meanL = labs.sumOf { it.l.toDouble() }.toFloat() / labs.size
        return CoverAnalysis(kMeans(labs), meanL)
    }

    private fun kMeans(points: List<Lab>): List<ColorCluster> {
        val rnd = Random(0x4B42) // deterministic
        val k = min(K, points.size)
        // k-means++ initialization
        val centers = ArrayList<Lab>(k)
        centers += points[rnd.nextInt(points.size)]
        val dist = FloatArray(points.size) { Float.MAX_VALUE }
        while (centers.size < k) {
            var sum = 0.0
            val last = centers.last()
            for (i in points.indices) {
                dist[i] = min(dist[i], points[i].distanceSq(last))
                sum += dist[i]
            }
            if (sum <= 0.0) break
            var r = rnd.nextDouble() * sum
            var chosen = points.size - 1
            for (i in points.indices) {
                r -= dist[i]
                if (r <= 0) {
                    chosen = i
                    break
                }
            }
            centers += points[chosen]
        }
        val assign = IntArray(points.size)
        repeat(ITERATIONS) {
            for (i in points.indices) {
                var best = 0
                var bestD = Float.MAX_VALUE
                for (c in centers.indices) {
                    val d = points[i].distanceSq(centers[c])
                    if (d < bestD) {
                        bestD = d
                        best = c
                    }
                }
                assign[i] = best
            }
            for (c in centers.indices) {
                var l = 0.0
                var a = 0.0
                var b = 0.0
                var n = 0
                for (i in points.indices) {
                    if (assign[i] != c) continue
                    l += points[i].l
                    a += points[i].a
                    b += points[i].b
                    n++
                }
                if (n > 0) centers[c] = Lab((l / n).toFloat(), (a / n).toFloat(), (b / n).toFloat())
            }
        }
        val counts = IntArray(centers.size)
        for (a in assign) counts[a]++
        val raw = centers.indices
            .filter { counts[it] > 0 }
            .map { ColorCluster(centers[it], counts[it].toFloat() / points.size) }
            .sortedByDescending { it.weight }
        // Merge clusters that are perceptually the same color.
        val merged = ArrayList<ColorCluster>()
        for (c in raw) {
            val twin = merged.indexOfFirst { it.lab.distanceSq(c.lab) < 0.035f * 0.035f }
            if (twin >= 0) {
                val t = merged[twin]
                val w = t.weight + c.weight
                merged[twin] = ColorCluster(t.lab.lerp(c.lab, c.weight / w), w)
            } else {
                merged += c
            }
        }
        return merged.sortedByDescending { it.weight }
    }

    /**
     * Significance of a cluster as a *color* of the cover. Area matters, but a small
     * vivid area (a red dot on cream) characterizes a cover more than a large gray one.
     */
    private fun colorScore(c: ColorCluster): Float =
        c.weight.toDouble().pow(0.55).toFloat() * (c.lab.chroma - 0.02f).coerceAtLeast(0f) *
            // very dark or very light clusters carry little color information
            (1f - (2f * c.lab.l - 1.1f).pow(2)).coerceAtLeast(0.15f)

    /**
     * The palette for [analysis] on the [appearance] background, seen in the [Lamp]. [wallTint]
     * scales how much of the cover's color the light background takes (the Intensity setting; 0
     * gives it none). With [coverField] the text stands on the field, the cover colors
     * background, instead of the plain one.
     */
    fun derive(
        analysis: CoverAnalysis,
        appearance: Appearance = Appearance.DARK,
        wallTint: Float = 1f,
        coverField: Boolean = false,
    ): ScenePalette {
        val chromatic = analysis.clusters
            .filter { it.lab.chroma >= NEUTRAL_CHROMA && it.weight >= 0.02f }
            .sortedByDescending { colorScore(it) }
        val lead = chromatic.firstOrNull()?.lab
        val hue = lead?.hue ?: Lamp.HUE
        // Text is near-neutral: only a breath of the cover's hue so it belongs to the
        // picture, but never ivory. A neutral cover gets neutral text.
        val neutralChroma = if (lead == null) 0.003f else min(0.009f, lead.chroma * 0.08f + 0.002f)
        // On the cover's own colors the text takes a little more of their hue, but stays near-neutral.
        val inkChroma = neutralChroma * (if (coverField) 1.6f else 1f)

        // Three levels (lightness in Oklab): the title; artist, clock and the progress bar's
        // fill; everything else. They step down evenly, as far as the quietest can go: on black
        // it keeps 4.5:1 with a quarter to spare. On paper they step up from near-black as far as
        // 4.5:1 allows. Grays closer than that look like a mistake side by side.
        val levels = if (appearance == Appearance.DARK) {
            // On the cover's own colors the lower two levels stand a little lighter: they are read
            // against color, not black.
            if (coverField) floatArrayOf(0.94f, 0.82f, 0.71f) else floatArrayOf(0.93f, 0.77f, 0.61f)
        } else {
            floatArrayOf(LIGHT_INK, LIGHT_INK + 0.13f, LIGHT_INK + 0.25f)
        }
        val wall = if (appearance == Appearance.DARK) {
            0xFF000000.toInt()
        } else {
            // Paper, a little warm, taking a breath of the cover's color pulled toward the warm
            // side, so a red cover gives peach, never pink. Paper stays paper: its hue stays within
            // the warm range, and a cool cover only gives it less color, never mint.
            val tint = (if (lead == null) 0.002f else min(0.007f, lead.chroma * 0.06f + 0.002f)) * wallTint.coerceIn(0f, 1.5f)
            val pulled = mixHue(hue, Lamp.HUE, 0.5f)
            val paperHue = when {
                pulled in PAPER_HUES -> pulled
                hueDistance(pulled, PAPER_HUES.start) < hueDistance(pulled, PAPER_HUES.endInclusive) -> PAPER_HUES.start
                else -> PAPER_HUES.endInclusive
            }
            val away = hueDistance(pulled, paperHue)
            val paperTint = tint * (1f - away / 90f).coerceIn(0f, 1f)
            Lamp.apply(Lch(PAPER, 0.005f + paperTint, paperHue).toLab()).toArgb()
        }
        // On paper the quietest level still keeps 4.5:1. On the pale cover field the text stands
        // on its darkest part, not on the paper.
        val paperLimit = when {
            appearance == Appearance.DARK -> 1f
            coverField -> min(lightestInkOn(wall), lightestInkOn(Lamp.apply(Lch(FIELD_PALEST_DARK, 0.04f, hue).toLab()).toArgb()))
            else -> lightestInkOn(wall)
        }
        fun ink(level: Int): Int {
            val lit = Lamp.apply(Lch(levels[level], inkChroma, hue).toLab()).toLch()
            val l = if (appearance == Appearance.DARK) lit.l else minOf(lit.l, paperLimit - INK_STEP * (2 - level))
            return Lch(l, min(lit.c, MAX_INK_CHROMA), lit.h).toLab().toArgb()
        }
        val muted = ink(1)
        // The bar's fill on the cover's colors: their leading hue, light (or dark on paper) enough
        // to read against them. A neutral cover keeps the artist's level.
        val accent = if (!coverField || lead == null) {
            muted
        } else if (appearance == Appearance.DARK) {
            Lamp.apply(Lch(0.80f, (lead.chroma * 0.9f).coerceIn(0.05f, 0.12f), lead.hue).toLab()).toArgb()
        } else {
            val lit = Lamp.apply(Lch(0.44f, (lead.chroma * 0.9f).coerceIn(0.05f, 0.11f), lead.hue).toLab()).toLch()
            Lch(minOf(lit.l, paperLimit - INK_STEP), lit.c, lit.h).toLab().toArgb()
        }
        return ScenePalette(
            appearance = appearance,
            wall = wall,
            ink = ink(0),
            inkMuted = muted,
            inkQuiet = ink(2),
            accent = accent,
        )
    }

    /** Lightness (Oklab) of the light wall: paper, not white. A full screen of white glares. */
    const val PAPER = 0.915f

    /** The hues paper may take (degrees): from peach to straw. */
    val PAPER_HUES = 50f..90f

    /** The most color text takes. */
    const val MAX_INK_CHROMA = 0.018f

    /** The darkest the pale cover field gets (Oklab L): text is set against it. */
    const val FIELD_PALEST_DARK = 0.87f

    /** The least step in lightness between two text levels where one has to give way. */
    private const val INK_STEP = 0.1f

    /** The lightest text (Oklab L) that still keeps 4.5:1 (with a little margin) on [wall]. */
    fun lightestInkOn(wall: Int): Float {
        val l = Oklab.fromArgb(wall).l
        val y = (l * l * l + 0.05f) / 4.6f - 0.05f
        return if (y <= 0f) 0f else cbrt(y)
    }

    /** Angle between two hues along the shorter arc (degrees). */
    private fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** Lightness of the title on paper; the other two levels follow in steps. */
    const val LIGHT_INK = 0.24f
}
