package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.color.ScenePalette
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.text.texts
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.textType
import io.github.hazymorning.klangbild.ui.theme.titleType
import kotlin.math.roundToInt

/*
 * How the stage's text is set: the styles (one title face, one text face, three inks), the
 * title's ladder of sizes, and the plan that fits title, version, artist and album into the
 * column (balanced breaks, never inside a word, short words kept with the next).
 */

/**
 * The stage's typography: sentence case; hierarchy from size, weight and three levels of
 * brightness. The title is in the title face, large and light (never below 24 sp). Everything
 * else is in the text face (never above 21 sp): the artist a little heavier, so the two balance
 * each other, and everything else quiet. Times take the title face's figures.
 */
class StageStyles(palette: ScenePalette) {
    val ink = palette.ink.asColor()
    val muted = palette.inkMuted.asColor()
    val quiet = palette.inkQuiet.asColor()

    /** The progress bar's fill: the artist's level, or on the cover's colors their leading hue. */
    val accent = palette.accent.asColor()

    /** The progress bar's track: the title's color, or the cover's leading hue, faint. */
    val track = if (palette.accent == palette.inkMuted) ink.copy(alpha = 0.16f) else accent.copy(alpha = 0.24f)

    /**
     * The title at one step of [titleLadder]. Japanese, Chinese and Korean titles are a little smaller (their characters
     * stand taller than capitals).
     */
    fun title(step: TitleStep, cjk: Boolean = false): TextStyle {
        val size = if (cjk) step.size * CJK_SCALE else step.size
        return titleType(
            size = size,
            weight = step.weight,
            color = ink,
            lineHeight = size * step.leading + if (cjk) 6f else 0f,
            tracking = if (cjk) 0f else step.tracking,
        ).merge(TextStyle(lineBreak = LineBreak.Heading, textDirection = TextDirection.Content, textAlign = TextAlign.Left))
    }

    /** The version under the title ("Radio Edit"): a quiet subtitle at half the title's size, 17 to 21 sp. */
    fun version(titleSize: Float): TextStyle = textType((titleSize * 0.5f).coerceIn(17f, 21f), 420, quiet).text()

    val work = textType(17f, 440, quiet, lineHeight = 23f, tracking = 0.005f).text()
    val artist = textType(21f, 500, muted, lineHeight = 27f).text()
    val artistSmall = textType(19f, 500, muted, lineHeight = 25f).text()
    val album = textType(17f, 400, quiet, lineHeight = 23f, tracking = 0.005f).text()

    /** Status, station: small and calm, with a little air (light on black closes up). */
    val label = textType(15f, 440, quiet, lineHeight = 20f, tracking = 0.015f)

    /** Times (the clock, the progress bar's times): figures in the title face, whose digits are even. */
    val time = titleType(15f, 440, quiet, lineHeight = 20f, tracking = 0.015f)
    val next = textType(15f, 440, quiet, lineHeight = 20f, tracking = 0.015f).merge(TextStyle(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left))

    /** "Up next" differs from what follows by weight alone. */
    val nextLead = SpanStyle(fontWeight = FontWeight(560))

    private fun TextStyle.text() = merge(TextStyle(textDirection = TextDirection.Content, textAlign = TextAlign.Left))

    companion object {
        const val CJK_SCALE = 0.88f
    }
}

/** One size of the title: [size] sp, at most [lines] lines, [tracking] em. */
class TitleStep(val size: Float, val lines: Int, val weight: Int, val leading: Float, val tracking: Float)

/**
 * Title sizes from large to small: two lines at display size, then three lines a little
 * smaller and (slightly) heavier so they look as strong; four lines only as the last resort.
 * Large sizes close up a little (−1%); the line spacing leaves room for an umlaut under a
 * descender.
 */
val titleLadder = listOf(
    TitleStep(44f, 2, 400, 1.13f, -0.01f),
    TitleStep(40f, 2, 400, 1.13f, -0.01f),
    TitleStep(34f, 3, 420, 1.14f, -0.005f),
    TitleStep(30f, 3, 420, 1.15f, -0.005f),
    TitleStep(26f, 3, 430, 1.16f, 0f),
    TitleStep(24f, 4, 430, 1.16f, 0f),
)

/** Vertical rhythm of the text block: space from a line's baseline to the next one's cap height (dp). */
internal object Gaps {
    const val WORK = 14f
    const val VERSION = 14f
    const val ARTIST = 16f
    const val ALBUM = 10f
    const val BOTTOM = 26f
}

/**
 * The text block may take at most this share of the artwork's height (from the first cap
 * height to the last baseline): the artwork is the heaviest element and stays clearly the
 * tallest.
 */
const val BLOCK_SHARE = 0.62f

/** The measured decisions for the text block: title step and breaks, the artists fitted onto one line, and more. */
class TextPlan(
    val title: AnnotatedString,
    val titleStyle: TextStyle,
    val titleLines: Int,
    /** The styles of the block's lines. */
    val styles: StageStyles,
    /** "Radio Edit" under the title. */
    val version: String?,
    val versionStyle: TextStyle,
    val artists: String?,
    val artistStyle: TextStyle,
    /** "Album · 2024", or only the album when both don't fit. */
    val album: String?,
    /** What comes next: title and artist, or only the title when both don't fit. */
    val next: String?,
    /** The words before it ("Up next"). */
    val nextLead: String,
)

/** Measures the block once per model. */
@Composable
fun rememberTextPlan(model: StageModel): TextPlan {
    val measurer = rememberTextMeasurer(cacheSize = 24)
    val localDensity = LocalDensity.current
    val nextLead = model.nextLead ?: texts()[R.string.stage_next]
    return remember(
        model.text, model.year, model.next, model.scene.layout, model.scene.palette, model.progress, model.line, model.durationMs == null,
        model.chapter != null, localDensity, nextLead,
    ) {
        planText(model, measurer, localDensity, nextLead)
    }
}

private fun planText(
    model: StageModel,
    measurer: androidx.compose.ui.text.TextMeasurer,
    density: androidx.compose.ui.unit.Density,
    nextLead: String,
): TextPlan {
    val t = model.text
    val layout = model.scene.layout
    val d = density.density
    val colW = (layout.columnWidth * d).roundToInt()
    val normal = StageStyles(model.scene.palette)
    val cjk = isMostlyCjk(t.title)
    val version = t.version

    val title = EmojiText.annotate(t.title, dimTo = normal.ink.copy(alpha = 0.62f))

    // Everything but the title, from the title's last baseline down (or the work line above
    // it), to know how much height the title may take.
    fun cap(style: TextStyle) = style.fontSize.value * Fonts.CAP_HEIGHT * d
    fun linesHeight(text: String, style: TextStyle, lines: Int): Float {
        val r = measurer.measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = colW), maxLines = lines)
        return r.getLineBaseline(r.lineCount - 1) - r.getLineBaseline(0)
    }
    fun budget(step: TitleStep): Float {
        var rest = 0f
        if (t.work != null) rest += cap(normal.work) + linesHeight(t.work, normal.work, 2) + Gaps.WORK * d
        if (version != null) rest += Gaps.VERSION * d + cap(normal.version(step.size))
        rest += Gaps.ARTIST * d + cap(normal.artist)
        if (t.album != null || model.year != null || model.chapter != null) rest += Gaps.ALBUM * d + cap(normal.album)
        if (model.line != null || (model.progress && model.durationMs != null)) {
            rest += Gaps.BOTTOM * d + (BAR_THICKNESS + BAR_LABEL_GAP) * d + cap(normal.label)
        }
        return layout.art * BLOCK_SHARE * d - rest
    }

    val choice = pickTitleSize(title, { step -> normal.title(step, cjk) }, measurer, colW, ::budget, d)
        ?: TitleChoice(titleLadder.last(), title)
    val block = normal

    // Artists on one line: the most complete form that fits, at the artist's size, then a
    // step smaller, before anything is cut.
    val options = t.artistOptions()
    fun fits(option: String, style: TextStyle) =
        measurer.measure(AnnotatedString(option), style, maxLines = 1, softWrap = false).size.width <= colW
    var artistStyle = block.artist
    var artists: String? = null
    for (style in listOf(block.artist, block.artistSmall)) {
        artists = options.firstOrNull { fits(it, style) }
        if (artists != null) {
            artistStyle = style
            break
        }
    }
    if (artists == null && options.isNotEmpty()) {
        artists = options.last()
        artistStyle = block.artistSmall
    }
    // The album with its year; the year goes first when both don't fit.
    val albumYear = listOfNotNull(t.album, model.year?.toString()).joinToString(TimeText.SEPARATOR).ifEmpty { null }
    val album = if (albumYear != null && t.album != null && model.year != null && !fits(albumYear, block.album)) t.album else albumYear
    // What comes next: title and artist, or the title alone rather than a cut-off artist.
    val next = model.next?.let { full ->
        if (model.nextTitle == null || measurer.measure(nextText(full, normal, nextLead), normal.next, maxLines = 1, softWrap = false).size.width <= colW) {
            full
        } else {
            model.nextTitle
        }
    }
    return TextPlan(
        title = choice.text,
        titleStyle = block.title(choice.step, cjk),
        titleLines = choice.step.lines,
        styles = block,
        version = version,
        versionStyle = block.version(choice.step.size),
        artists = artists,
        artistStyle = artistStyle,
        album = album,
        next = next,
        nextLead = nextLead,
    )
}

/**
 * The lines of the text block, for transitions that treat them one by one: new lines rise one
 * after another, each [stagger] ms after the first.
 */
enum class TextLine(val stagger: Int) { WORK(0), TITLE(0), VERSION(50), ARTIST(80), ALBUM(140), BOTTOM(200), NEXT(260) }

private class TitleChoice(val step: TitleStep, val text: AnnotatedString)

/**
 * The title at the largest step of the ladder that fits its line limit and the height it is
 * given ([budget], from its first cap height to its last baseline), with balanced line breaks.
 * At each step, a version that may only break after the dashes between a movement's sections is
 * tried first (a line may end after "Presto" and its dash, but never between "Allegro" and
 * "assai"). If only the free version fits, one step smaller is still worth it when the dash
 * version fits there. Returns null if nothing fits.
 */
private fun pickTitleSize(
    text: AnnotatedString,
    styleOf: (TitleStep) -> TextStyle,
    measurer: androidx.compose.ui.text.TextMeasurer,
    maxW: Int,
    budget: (TitleStep) -> Float,
    density: Float,
): TitleChoice? {
    val base = glueShortWords(glueParentheses(text))
    val glued = glueBetweenDashes(base)
    fun height(r: TextLayoutResult): Float =
        r.layoutInput.style.fontSize.value * Fonts.CAP_HEIGHT * density + (r.getLineBaseline(r.lineCount - 1) - r.getLineBaseline(0))
    fun gluedFits(step: TitleStep): Boolean {
        if (glued == null) return false
        val g = measurer.measure(glued, style = styleOf(step), constraints = Constraints(maxWidth = maxW), maxLines = step.lines + 1)
        return g.lineCount <= step.lines && height(g) <= budget(step) && breaksOnlyAfterDashes(g)
    }
    for ((i, step) in titleLadder.withIndex()) {
        if (gluedFits(step)) return TitleChoice(step, glued!!)
        val r = measurer.measure(base, style = styleOf(step), constraints = Constraints(maxWidth = maxW), maxLines = step.lines + 1)
        if (r.lineCount <= step.lines && height(r) <= budget(step) && !breaksInsideWord(r)) {
            val smaller = titleLadder.getOrNull(i + 1)
            if (smaller != null && gluedFits(smaller)) return TitleChoice(smaller, glued!!)
            return TitleChoice(step, base)
        }
    }
    return null
}

/**
 * A short word (one to three letters: "im", "de", "der", "You") stays with the word after it,
 * so no line of a title ends on it: "Spiegel / im Spiegel", never "Spiegel im / Spiegel".
 */
internal fun glueShortWords(text: AnnotatedString): AnnotatedString {
    val s = text.text
    val chars = s.toCharArray()
    var start = 0
    var changed = false
    for (i in chars.indices) {
        if (chars[i] != ' ') continue
        val word = s.substring(start, i)
        if (word.length in 1..3 && word.all { it.isLetter() } && i + 1 < chars.size && chars[i + 1].isLetterOrDigit()) {
            chars[i] = '\u00A0'
            changed = true
        }
        start = i + 1
    }
    return if (!changed) text else AnnotatedString(String(chars), spanStyles = text.spanStyles, paragraphStyles = text.paragraphStyles)
}

/** Short parentheses never break inside: "(alla marcia)" stays one piece. */
internal fun glueParentheses(text: AnnotatedString): AnnotatedString {
    val s = text.text
    if (!s.contains('(')) return text
    val chars = s.toCharArray()
    var open = -1
    for (i in chars.indices) {
        when (chars[i]) {
            '(' -> open = i
            ')' -> {
                if (open >= 0 && i - open <= 28) for (j in open until i) if (chars[j] == ' ') chars[j] = ' '
                open = -1
            }
        }
    }
    return AnnotatedString(String(chars), spanStyles = text.spanStyles, paragraphStyles = text.paragraphStyles)
}

/** The title with every space that is not right after a dash made non-breaking; null without dashes. */
private fun glueBetweenDashes(text: AnnotatedString): AnnotatedString? {
    val s = text.text
    if (!s.contains(" – ")) return null
    val chars = s.toCharArray()
    for (i in chars.indices) {
        if (chars[i] == ' ' && !(i > 0 && chars[i - 1] == '–')) chars[i] = ' '
    }
    return AnnotatedString(String(chars), spanStyles = text.spanStyles, paragraphStyles = text.paragraphStyles)
}

/** True if every line but the last ends right after a dash (U+2013) and a space. */
private fun breaksOnlyAfterDashes(r: TextLayoutResult): Boolean {
    val text = r.layoutInput.text.text
    for (i in 0 until r.lineCount - 1) {
        val end = r.getLineEnd(i)
        if (end < 2 || text[end - 1] != ' ' || text[end - 2] != '–') return false
    }
    return true
}

/** True if a line ends in the middle of a word (the breaker had no better option). */
private fun breaksInsideWord(r: TextLayoutResult): Boolean {
    val text = r.layoutInput.text.text
    for (i in 0 until r.lineCount - 1) {
        val end = r.getLineEnd(i)
        if (end <= 0 || end >= text.length) continue
        val before = text[end - 1]
        val after = text[end]
        if (before.isLetterOrDigit() && after.isLetterOrDigit()) return true
    }
    return false
}

/** True if most letters of [text] are Chinese, Japanese or Korean (no capitals, full-width). */
fun isMostlyCjk(text: String): Boolean {
    var cjk = 0
    var letters = 0
    for (ch in text) {
        if (!ch.isLetter()) continue
        letters++
        val c = ch.code
        if (c in 0x3040..0x30FF || c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF || c in 0xAC00..0xD7AF || c in 0xF900..0xFAFF) cjk++
    }
    return letters > 0 && cjk * 2 >= letters
}

/** True if [text] contains letters of a right-to-left script. */
fun isRightToLeft(text: String): Boolean = text.any {
    val d = Character.getDirectionality(it)
    d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
}

/** "Up next" ([lead]) in a heavier weight, 12 dp, then what comes next (isolated if right-to-left). */
internal fun nextText(text: String, styles: StageStyles, lead: String): AnnotatedString = buildAnnotatedString {
    withStyle(styles.nextLead) { append(lead) }
    // A no-break space widened to about 12 dp.
    withStyle(SpanStyle(letterSpacing = 0.55.em)) { append(" ") }
    append(if (isRightToLeft(text)) "\u2067$text\u2069" else text)
}

/**
 * Large type has more room before its first stem than small type; left as it is, a title
 * looks indented against the lines below it. Pulled left by the difference (dp).
 */
internal fun opticalIndent(size: Float): Float = -(0.065f * (size - 15f)).coerceAtLeast(0f)

/**
 * [text] on one line of [width] px: whole, or cut after its last whole word that leaves room
 * for an ellipsis ("Conversation with Mrs. Grubach, then …"). A single word too long for the
 * line is left to the line's own ellipsis.
 */
internal fun fitWords(measurer: TextMeasurer, text: String, style: TextStyle, width: Int): String {
    fun fits(s: String) = measurer.measure(AnnotatedString(s), style, maxLines = 1, softWrap = false).size.width <= width
    if (fits(text)) return text
    var end = text.length
    while (true) {
        end = text.lastIndexOfAny(charArrayOf(' ', ' '), end - 1)
        if (end <= 0) return text
        // Never end on a dash or a separator: they are trimmed before the ellipsis is added.
        val head = text.substring(0, end).trimEnd(' ', ' ', '–', '·', ',', ':', ';')
        if (head.isNotEmpty() && fits("$head …")) return "$head …"
    }
}
