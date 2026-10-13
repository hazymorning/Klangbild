package io.github.hazymorning.klangbild.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hazymorning.klangbild.R
import kotlin.math.roundToInt

/**
 * The two typefaces (both SIL Open Font License, shipped unmodified, licenses in
 * assets/licenses):
 *
 * - [TITLE]: Jost (Owen Earl), a geometric sans in the line of Paul Renner's Futura, for the
 *   titles, the large clock and the headings. Its round, even forms and small x-height keep
 *   large sizes calm.
 * - [TEXT]: DM Sans (Colophon Foundry), a geometric grotesque with optical sizes and a larger
 *   x-height, for everything read at 15 to 21 sp from the sofa: artists, albums, times, status,
 *   menus. Its optical-size axis is set to the size in sp, so small lines get the looser,
 *   sturdier cut.
 *
 * Both have a cap height of 0.70 em, so lines aligned by cap height or baseline agree across
 * the two. Hierarchy comes from size, weight and three ink levels, never from capitals,
 * letter-spacing, italics or a third family. Scripts the faces do not cover (Japanese,
 * Arabic and others) fall back to the system's fonts.
 */
enum class Face(
    internal val font: Int,
    /** The weights the styles use; a family holds exactly these, so no weight is ever faked. */
    internal val weights: IntArray,
    /** The optical-size axis (sp), or null for a face without one. */
    internal val opsz: ClosedFloatingPointRange<Float>?,
    /** How far a colon is raised to sit in the middle of the figures (em). */
    internal val colonRaise: Float,
) {
    TITLE(R.font.jost, intArrayOf(320, 400, 420, 430, 440), null, 0.123f),
    TEXT(R.font.dmsans, intArrayOf(400, 420, 440, 460, 480, 500, 520, 560, 600), 9f..40f, 0.10f),
}

object Fonts {
    /** Cap height of both faces as a share of the size (for aligning by cap height). */
    const val CAP_HEIGHT = 0.70f

    /** How far DM Sans's descenders (p, q, y) reach below the baseline, as a share of the size. */
    const val DESCENT = 0.22f

    private val families = HashMap<Long, FontFamily>()

    /** [face] for text at [size] sp. */
    fun family(face: Face, size: Float): FontFamily {
        val opsz = face.opsz?.let { size.coerceIn(it.start, it.endInclusive).roundToInt() } ?: 0
        val key = face.ordinal.toLong() shl 32 or opsz.toLong()
        return synchronized(families) {
            families.getOrPut(key) {
                FontFamily(
                    face.weights.map { weight ->
                        val settings = if (face.opsz != null) {
                            FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opsz.toFloat()))
                        } else {
                            FontVariation.Settings(FontVariation.weight(weight))
                        }
                        Font(resId = face.font, weight = FontWeight(weight), variationSettings = settings)
                    },
                )
            }
        }
    }
}

/**
 * A text style in [face] at [size] sp: line height [lineHeight] sp, tracking [tracking] em.
 * Layouts align the result by its baselines and [Fonts.CAP_HEIGHT], never by the boxes.
 */
fun type(
    face: Face,
    size: Float,
    weight: Int,
    color: Color = Color.Unspecified,
    lineHeight: Float = size * 1.25f,
    tracking: Float = 0f,
): TextStyle = TextStyle(
    fontFamily = Fonts.family(face, size),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.em,
    color = color,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** A style in the title face (Jost). */
fun titleType(size: Float, weight: Int, color: Color = Color.Unspecified, lineHeight: Float = size * 1.2f, tracking: Float = 0f) =
    type(Face.TITLE, size, weight, color, lineHeight, tracking)

/** A style in the text face (DM Sans). */
fun textType(size: Float, weight: Int, color: Color = Color.Unspecified, lineHeight: Float = size * 1.3f, tracking: Float = 0f) =
    type(Face.TEXT, size, weight, color, lineHeight, tracking)

/**
 * Times set like a clock face. Every digit stands in a cell as wide as the widest digit and
 * centered in it (letter-spacing is split evenly on both sides of a glyph), so a ticking time
 * never shifts the line. The colon is raised to the middle of the figures; set on the
 * baseline, it sags below them.
 */
class Figures internal constructor(
    private val cell: Float,
    private val advance: FloatArray,
    private val tracking: Float,
    private val colonRaise: Float,
) {
    fun of(text: String): AnnotatedString = buildAnnotatedString {
        for (ch in text) {
            when {
                ch in '0'..'9' -> withStyle(SpanStyle(letterSpacing = (tracking + cell - advance[ch - '0']).em)) { append(ch) }
                ch == ':' -> withStyle(SpanStyle(baselineShift = BaselineShift(colonRaise))) { append(ch) }
                else -> append(ch)
            }
        }
    }
}

/** [Figures] for [style] in [face], measured once. */
@Composable
fun rememberFigures(style: TextStyle, face: Face): Figures {
    val measurer = rememberTextMeasurer(cacheSize = 12)
    return remember(style, face) {
        val plain = style.copy(letterSpacing = 0.em)
        val sizePx = measurer.measure("0", plain).let { r -> r.layoutInput.density.run { plain.fontSize.toPx() } }
        val advance = FloatArray(10) { d -> measurer.measure(d.toString(), plain, softWrap = false).getLineRight(0) / sizePx }
        Figures(advance.max(), advance, if (style.letterSpacing.isEm) style.letterSpacing.value else 0f, face.colonRaise)
    }
}

/**
 * How far [text] in [style] has to move right so that what is seen (its ink, not its
 * advance box) stands centered where the box would be (px). A line leans toward the side
 * whose first or last letter stands farther in; a line in the middle of the screen must not.
 */
@Composable
fun rememberInkShift(text: String, style: TextStyle): Float {
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    return remember(text, style, resolver, density) {
        val typeface = resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal).value as? android.graphics.Typeface
            ?: return@remember 0f
        val paint = android.graphics.Paint().apply {
            this.typeface = typeface
            textSize = with(density) { style.fontSize.toPx() }
            if (style.letterSpacing.isEm) letterSpacing = style.letterSpacing.value
        }
        // The outlines' exact bounds: whole pixels would leave the clock up to a pixel off.
        val outline = android.graphics.Path()
        paint.getTextPath(text, 0, text.length, 0f, 0f, outline)
        val ink = android.graphics.RectF()
        outline.computeBounds(ink, true)
        paint.measureText(text) / 2f - (ink.left + ink.right) / 2f
    }
}

/**
 * The rest clock's figures as outlines, set by hand: by the font's own advances and kerning, the
 * colon raised to the middle of the figures (set on the baseline it sags below them; how far
 * depends on the weight, so it is measured on the outlines), and the one drawn in by most of its
 * side bearings, which at this size leave it standing apart from its neighbors. As outlines the
 * figures are known exactly where they are seen: [ink] is what centers them.
 */
class ClockFigures(time: String, typeface: android.graphics.Typeface, sizePx: Float, tracking: Float) {
    /** The figures' outlines, the baseline starting at the origin. */
    val outline = android.graphics.Path()

    /** Where the ink is, around the same origin. */
    val ink = android.graphics.RectF()

    init {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = sizePx
        }
        val glyph = android.graphics.Path()
        val bounds = android.graphics.RectF()
        fun middle(ch: String): Float {
            paint.getTextPath(ch, 0, 1, 0f, 0f, glyph)
            glyph.computeBounds(bounds, true)
            return bounds.centerY()
        }
        val raise = middle(":") - middle("0")
        var pulled = 0f
        for (i in time.indices) {
            val one = time[i] == '1'
            if (one) pulled += ONE_LEFT * sizePx
            // The pen where the font puts this figure, the kerning with the one before it included.
            val x = paint.measureText(time, 0, i + 1) - paint.measureText(time, i, i + 1) + i * tracking * sizePx - pulled
            paint.getTextPath(time, i, i + 1, x, if (time[i] == ':') -raise else 0f, glyph)
            outline.addPath(glyph)
            if (one) pulled += ONE_RIGHT * sizePx
        }
        outline.computeBounds(ink, true)
    }

    private companion object {
        /**
         * How much of the one's side bearings is taken in (em), on its left and on its right: Jost
         * gives it 0.08 and 0.13, the other figures 0.02 to 0.06.
         */
        const val ONE_LEFT = 0.03f
        const val ONE_RIGHT = 0.08f
    }
}

/** [ClockFigures] for [time] in [style] (its tracking in em). */
@Composable
fun rememberClockFigures(time: String, style: TextStyle): ClockFigures {
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    return remember(time, style, resolver, density) {
        val typeface = resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal).value as? android.graphics.Typeface
            ?: android.graphics.Typeface.DEFAULT
        val tracking = if (style.letterSpacing.isEm) style.letterSpacing.value else 0f
        ClockFigures(time, typeface, with(density) { style.fontSize.toPx() }, tracking)
    }
}
