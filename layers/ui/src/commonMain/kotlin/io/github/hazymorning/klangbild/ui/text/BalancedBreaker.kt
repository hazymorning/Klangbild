package io.github.hazymorning.klangbild.ui.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Balanced line breaks, the same on every platform. This is Android's line breaker for
 * `LineBreak.Strategy.Balanced` (minikin's OptimalLineBreaker as Android 12 to 14 have it, and
 * as Robolectric runs it) over ICU's line break opportunities ([LineBreakRules]), with the
 * widths that [measurer] gives. On Android those widths are minikin's own, so the breaks are
 * the ones Android makes for a balanced [TextStyle.lineBreak].
 *
 * Every width is summed and compared as minikin does it, in 32-bit floats and in the same
 * order, because balanced breaking often chooses between breaks that score the same ("la la la
 * la la"), and then the last bit of a sum decides. On one line, Compose gives a position as
 * minikin's sum of the advances before it; where the text has runs in other sizes or is right
 * to left, the sums are taken apart and added up again (see [advances], [rightToLeftSums]).
 *
 * What it doesn't do as Android does: hyphenation ([androidx.compose.ui.text.style.Hyphens.Auto]),
 * tabs, a [TextIndent], inline content, and ICU's dictionaries for Thai, Lao, Khmer and Myanmar;
 * a paragraph that mixes left-to-right and right-to-left text is broken at the right places but
 * with sums that can differ in their last bit.
 */
class BalancedBreaker(private val measurer: TextMeasurer) {
    /**
     * Where each line of [text] in [style] ends when it is laid out like
     * `measurer.measure(text, style, constraints = Constraints(maxWidth = maxWidth))` with a
     * balanced line break: the offsets that `getLineEnd` gives, the last one the text's length.
     * The breaks are those of ICU's rules without a strictness, as Android 12 and older and
     * Robolectric make them, which leave a line break's strictness out.
     */
    fun lineEnds(text: AnnotatedString, style: TextStyle, maxWidth: Int): IntArray = lines(text, style, maxWidth).ends

    /** The lines of [text] as [lineEnds] gives them, and how to set the text on them. */
    fun lines(text: AnnotatedString, style: TextStyle, maxWidth: Int): Lines {
        val s = text.text
        if (s.isEmpty()) return Lines(intArrayOf(0), Constraints(maxWidth = maxWidth), style.textDirection)
        val locale = (style.localeList ?: LocaleList.current).localeList.firstOrNull()
        val rules = LineBreakTables.rules(locale?.language.orEmpty(), locale?.script.orEmpty())
        // Android breaks each paragraph on its own; a paragraph's line feed ends its last line.
        val paragraphs = ArrayList<Paragraph>()
        var start = 0
        while (start < s.length) {
            val newline = s.indexOf('\n', start)
            val end = if (newline < 0) s.length else newline + 1
            val contentEnd = if (newline < 0) s.length else newline
            paragraphs += Paragraph(start, end, if (contentEnd > start) measure(text.subSequence(start, contentEnd), style, rules) else null)
            start = end
        }
        // A text whose paragraphs each fit on a line is laid out as wide as it is: its intrinsic
        // width, rounded up (Compose gives the layout that width, not the whole maxWidth). That
        // breaks it as maxWidth would, unless its widest paragraph is a hair over a whole pixel,
        // which the intrinsic width (the same advances, added in another order) can round to.
        var width = maxWidth
        val widest = paragraphs.maxOf { it.measured?.fullWidth() ?: 0f }
        if (widest <= maxWidth && widest - floor(widest) in Float.MIN_VALUE..ROUNDING) {
            val intrinsic = measurer.measure(text, style, softWrap = false, maxLines = 1).multiParagraph.intrinsics.maxIntrinsicWidth
            width = minOf(maxWidth, ceil(intrinsic).toInt())
        }
        val out = ArrayList<Int>()
        for (p in paragraphs) {
            val ends = p.measured?.breaks(width.toFloat()) ?: intArrayOf(0)
            for ((i, end) in ends.withIndex()) out += if (i == ends.lastIndex) p.end else p.start + end
        }
        // After a final line feed comes one more line, an empty one.
        if (s.endsWith('\n')) out += s.length
        // Text that gets no line feed is laid out as the balanced text was; with them, exactly as wide.
        val fed = out.size > paragraphs.size + if (s.endsWith('\n')) 1 else 0
        val constraints = if (fed) Constraints.fixedWidth(width) else Constraints(maxWidth = maxWidth)
        val directions = paragraphs.mapNotNull { it.measured?.rtl }.distinct()
        val direction = when (directions.singleOrNull()) {
            true -> TextDirection.Rtl
            false -> TextDirection.Ltr
            null -> style.textDirection
        }
        return Lines(out.toIntArray(), constraints, direction)
    }

    /**
     * [text] in [style] laid out on [lines], which [lines] gave for them, at most [maxLines] lines
     * with [overflow]: the layout a balanced line break gives it.
     */
    fun layout(
        text: AnnotatedString,
        style: TextStyle,
        lines: Lines,
        maxLines: Int = Int.MAX_VALUE,
        overflow: TextOverflow = TextOverflow.Clip,
    ): TextLayoutResult =
        measurer.measure(withLineFeeds(text, lines.ends, maxLines), lines.style(style), overflow, maxLines = maxLines, constraints = lines.constraints)

    /**
     * Where a text's lines end ([ends], the last one its length), the constraints to set it on
     * them with ([constraints]), and the direction its paragraphs have ([direction], or the
     * style's own when they differ). Text that [withLineFeeds] changes needs exactly the width
     * its balanced layout had, or centered lines move, and this direction, because each line
     * becomes a paragraph of its own and would otherwise take the direction of its own first
     * letter.
     */
    class Lines(val ends: IntArray, val constraints: Constraints, val direction: TextDirection) {
        /** [style] for the text set on these lines: in their direction, with a simple line break. */
        fun style(style: TextStyle): TextStyle = style.merge(TextStyle(textDirection = direction, lineBreak = LineBreak.Simple))
    }

    private class Paragraph(val start: Int, val end: Int, val measured: Measured?)

    /** A word's sums from its start, and the offsets in it that are inside a ligature. */
    private class Word(val sums: FloatArray, val joined: Set<Int>)

    /** A paragraph's break opportunities, the sums at them, and the sizes its penalties come from. */
    private class Measured(
        /** True if the paragraph is right to left. */
        val rtl: Boolean,
        /** Word breaks, ascending; the last is the paragraph's end. */
        val breaks: IntArray,
        /** For each word break, 1 if it is inside an email address or a URL (minikin's "break badness"), else 0. */
        val badness: IntArray,
        /** For each word break, the sum of all advances before it. */
        val sumBefore: FloatArray,
        /** For each word break, the sum of the advances before it without the spaces at its end. */
        val sumVisible: FloatArray,
        /** For each word break, the font size (px) and horizontal scale of the run it ends. */
        val size: FloatArray,
        val scaleX: FloatArray,
        /** The font size (px) and horizontal scale of each run of the paragraph, which set the line penalty. */
        val runSizes: FloatArray,
        val runScales: FloatArray,
        /** The sums inside a word from its start, and where it can't break, for breaking where nothing else fits. */
        val wordSums: (start: Int, end: Int) -> Word,
    ) {
        fun fullWidth(): Float = sumVisible.last()

        /** minikin's populateCandidates and LineBreakOptimizer.computeBreaks for a balanced, ragged paragraph. */
        fun breaks(width: Float): IntArray {
            val offsets = ArrayList<Int>()
            val preBreaks = ArrayList<Float>()
            val postBreaks = ArrayList<Float>()
            val penalties = ArrayList<Float>()
            fun push(offset: Int, pre: Float, post: Float, penalty: Float) {
                offsets += offset
                preBreaks += pre
                postBreaks += post
                penalties += penalty
            }
            push(0, 0f, 0f, 0f)
            var linePenalty = 0f
            for (r in runSizes.indices) linePenalty = maxOf(linePenalty, hyphenPenalty(runSizes[r], runScales[r], width) * LINE_PENALTY_MULTIPLIER)
            var previous = 0
            var sumAtPrevious = 0f
            for (k in breaks.indices) {
                val end = breaks[k]
                // A word wider than a line may break between any two of its characters.
                if (sumVisible[k] - sumAtPrevious > width) {
                    val word = wordSums(previous, end)
                    for (i in previous + 1 until end) {
                        val offset = i - previous
                        // No advance of its own: inside a cluster or a ligature.
                        if (word.sums[offset + 1] == word.sums[offset] || offset in word.joined) continue
                        val at = sumAtPrevious + word.sums[offset]
                        push(i, at, at, SCORE_DESPERATE)
                    }
                }
                push(end, sumBefore[k], sumVisible[k], hyphenPenalty(size[k], scaleX[k], width) * badness[k])
                previous = end
                sumAtPrevious = sumBefore[k]
            }
            return optimize(offsets, preBreaks, postBreaks, penalties, width, linePenalty)
        }

        /** minikin's measure of what a hyphen costs, computed in double precision and kept as a float. */
        private fun hyphenPenalty(size: Float, scaleX: Float, width: Float): Float =
            (0.5 * size.toDouble() * scaleX.toDouble() * width.toDouble()).toFloat()

        private fun optimize(
            offsets: List<Int>,
            preBreaks: List<Float>,
            postBreaks: List<Float>,
            penalties: List<Float>,
            width: Float,
            linePenalty: Float,
        ): IntArray {
            val count = offsets.size
            val scores = FloatArray(count)
            val previous = IntArray(count)
            var active = 0
            for (i in 1 until count) {
                var best = Float.MAX_VALUE
                var bestPrevious = 0
                val leftEdge = postBreaks[i] - width
                var bestHope = 0f
                for (j in active until i) {
                    val jScore = scores[j]
                    if (jScore + bestHope >= best) continue
                    val delta = preBreaks[j] - leftEdge
                    // Balanced breaking scores the last line too; a line too long is out.
                    val widthScore = if (delta < 0) SCORE_OVERFULL else delta * delta
                    if (delta < 0) active = j + 1 else bestHope = widthScore
                    val score = jScore + widthScore
                    // Of equal scores, the later line start wins.
                    if (score <= best) {
                        best = score
                        bestPrevious = j
                    }
                }
                scores[i] = best + penalties[i] + linePenalty
                previous[i] = bestPrevious
            }
            val ends = ArrayList<Int>()
            var i = count - 1
            while (i > 0) {
                ends += offsets[i]
                i = previous[i]
            }
            ends.reverse()
            return ends.toIntArray()
        }
    }

    /** The break opportunities, sums and sizes of one paragraph, a text without a line feed. */
    private fun measure(text: AnnotatedString, style: TextStyle, rules: LineBreakRules): Measured {
        val s = text.text
        val wordBreaker = WordBreaker(s, rules.boundaries(s))
        val ends = ArrayList<Int>()
        val badness = ArrayList<Int>()
        while (true) {
            val end = wordBreaker.next()
            if (end < 0) break
            ends += end
            badness += wordBreaker.badness()
            if (end >= s.length) break
        }
        val spans = metricSpans(text)
        val line = measureLine(AnnotatedString(s, spanStyles = spans), style)
        val rtl = line.getParagraphDirection(0) == ResolvedTextDirection.Rtl
        val backwards = BooleanArray(s.length) { line.getBidiRunDirection(it) == ResolvedTextDirection.Rtl }
        val mixed = backwards.any { it != rtl }
        // The runs: minikin measures and penalizes each stretch of one style on its own, and
        // each stretch of one direction.
        val styleStarts = (spans.flatMap { listOf(it.start, it.end) } + 0).filter { it in 0 until s.length }.distinct().sorted()
        val runStarts = (styleStarts + (1 until s.length).filter { backwards[it] != backwards[it - 1] }).distinct().sorted()
        val sum: (Int) -> Float
        val wordSums: (Int, Int) -> Word
        if (rtl && !mixed && styleStarts.size == 1) {
            val needed = ends.flatMap { listOf(it, visibleEnd(s, it)) }
            val sums = rightToLeftSums(AnnotatedString(s, spanStyles = spans), style, needed)
            sum = { sums.getValue(it) }
            wordSums = { start, end ->
                val word = rightToLeftLine(AnnotatedString(s.substring(start, end), spanStyles = metricSpans(text.subSequence(start, end))), style)
                val right = word.getHorizontalPosition(0, usePrimaryDirection = true)
                Word(FloatArray(end - start + 1) { right - word.getHorizontalPosition(it, usePrimaryDirection = true) }, emptySet())
            }
        } else if (!rtl && !mixed && styleStarts.size == 1) {
            // One run: the positions on one line are minikin's sums themselves.
            val positions = HashMap<Int, Float>()
            sum = { offset -> positions.getOrPut(offset) { line.getHorizontalPosition(offset, usePrimaryDirection = true) } }
            wordSums = { start, end ->
                val wordText = AnnotatedString(s.substring(start, end), spanStyles = metricSpans(text.subSequence(start, end)))
                val word = measureLine(wordText, style)
                val sums = FloatArray(end - start + 1) { word.getHorizontalPosition(it, usePrimaryDirection = true) }
                Word(sums, ligatureInsides(wordText, style, sums))
            }
        } else {
            // Several runs: Android sums the positions of each run on its own, so the sums are
            // made here from the characters' advances, in minikin's order.
            val widths = advances(s, spans, runStarts, backwards, style)
            val prefix = FloatArray(s.length + 1)
            for (i in widths.indices) prefix[i + 1] = prefix[i] + widths[i]
            sum = { prefix[it] }
            wordSums = { start, end ->
                val out = FloatArray(end - start + 1)
                for (i in start until end) out[i - start + 1] = out[i - start] + widths[i]
                Word(out, emptySet())
            }
        }
        val density = line.layoutInput.density
        fun sizeAt(index: Int): Float = minikinSize(runStyle(style, spans, index).fontSize, style.fontSize, density)
        fun scaleAt(index: Int): Float = runStyle(style, spans, index).textGeometricTransform?.scaleX ?: 1f
        return Measured(
            rtl = rtl,
            breaks = ends.toIntArray(),
            badness = badness.toIntArray(),
            sumBefore = FloatArray(ends.size) { sum(ends[it]) },
            sumVisible = FloatArray(ends.size) { sum(visibleEnd(s, ends[it])) },
            size = FloatArray(ends.size) { sizeAt(ends[it] - 1) },
            scaleX = FloatArray(ends.size) { scaleAt(ends[it] - 1) },
            runSizes = FloatArray(runStarts.size) { sizeAt(runStarts[it]) },
            runScales = FloatArray(runStarts.size) { scaleAt(runStarts[it]) },
            wordSums = wordSums,
        )
    }

    /** Where the text before [end] ends without the spaces that vanish at a line's end. */
    private fun visibleEnd(s: String, end: Int): Int {
        var visible = end
        while (visible > 0 && isLineEndSpace(s[visible - 1])) visible--
        return visible
    }

    /**
     * The advance of each character of [text] as minikin has it for breaking: a cluster's whole
     * advance at its first character and 0 at the others. A run's clusters are where its sum
     * grows on a line of its own. Each cluster is then measured at the start of a line, followed
     * by the next cluster of its word since kerning changes the first of two glyphs, so that its
     * advance is the first term of the sum there and comes out exact. Where that disagrees with
     * the run's own sums (a no-break space or a comma takes the font of the character before it,
     * which a cluster on its own line doesn't have), the difference of the run's sums is used.
     * A right-to-left run (in a paragraph that mixes directions) is shaped as a whole, joining
     * its letters, so there the differences of its sums are used, which can be off in their
     * last bit.
     */
    private fun advances(
        text: String,
        spans: List<AnnotatedString.Range<SpanStyle>>,
        runStarts: List<Int>,
        backwards: BooleanArray,
        style: TextStyle,
    ): FloatArray {
        val widths = FloatArray(text.length)
        for ((r, a) in runStarts.withIndex()) {
            val b = runStarts.getOrElse(r + 1) { text.length }
            var runSpan: SpanStyle? = null
            for (span in spans) if (a >= span.start && a < span.end) runSpan = runSpan?.merge(span.item) ?: span.item
            fun styled(t: String) = AnnotatedString(t, spanStyles = if (runSpan == null) emptyList() else listOf(AnnotatedString.Range(runSpan, 0, t.length)))
            val run = styled(text.substring(a, b))
            if (backwards[a]) {
                val alone = rightToLeftLine(run, style)
                val right = alone.getHorizontalPosition(0, usePrimaryDirection = true)
                val sums = FloatArray(b - a + 1) { right - alone.getHorizontalPosition(it, usePrimaryDirection = true) }
                for (i in a until b) widths[i] = sums[i + 1 - a] - sums[i - a]
                continue
            }
            val alone = measureLine(run, style, TextDirection.Ltr)
            val sums = FloatArray(b - a + 1) { alone.getHorizontalPosition(it, usePrimaryDirection = true) }
            val joined = ligatureInsides(run, style, sums)
            val starts = (a until b).filter { sums[it + 1 - a] != sums[it - a] && (it - a) !in joined }
            if (starts.isEmpty()) continue
            val lines = StringBuilder()
            val lineStarts = IntArray(starts.size)
            for ((c, start) in starts.withIndex()) {
                val end = starts.getOrElse(c + 1) { b }
                val partner = if (end < b && !isCacheBreak(text, end)) starts.getOrElse(c + 2) { b } else end
                lineStarts[c] = lines.length
                lines.appendRange(text, start, partner).append(" \n")
            }
            val layout = measureLines(styled(lines.toString()), style, TextDirection.Ltr)
            for ((c, start) in starts.withIndex()) {
                val end = starts.getOrElse(c + 1) { b }
                val first = layout.getHorizontalPosition(lineStarts[c] + end - start, usePrimaryDirection = true)
                val before = sums[start - a]
                val after = sums[end - a]
                widths[start] = if (before + first == after) first else after - before
            }
        }
        return widths
    }

    /**
     * The offsets of [text] (one run) that are inside a ligature, given its positions [sums] on
     * one line. Android spreads a ligature's advance evenly over its characters, so a position
     * inside one looks like a position between two characters of equal width; it is inside a
     * ligature if a zero-width non-joiner put there, which forbids ligatures and nothing else,
     * changes the width.
     */
    private fun ligatureInsides(text: AnnotatedString, style: TextStyle, sums: FloatArray): Set<Int> {
        val suspects = (1 until text.length).filter { j ->
            val before = sums[j] - sums[j - 1]
            val after = sums[j + 1] - sums[j]
            before > 0f && after > 0f && abs(before - after) <= EVEN_SPLIT
        }
        if (suspects.isEmpty()) return emptySet()
        // Without letter spacing, which minikin would give the joiner too (ligatures only go when
        // it is above 0.03 em, so they stay as they are); the text as it is comes first.
        val lines = StringBuilder()
        val ends = IntArray(suspects.size + 1)
        lines.append(text.text)
        ends[0] = lines.length
        lines.append(" \n")
        for ((i, j) in suspects.withIndex()) {
            lines.appendRange(text.text, 0, j).append(ZWNJ).appendRange(text.text, j, text.length)
            ends[i + 1] = lines.length
            lines.append(" \n")
        }
        val tight = TextUnit(0f, TextUnitType.Em)
        val runSpans = text.spanStyles.map { AnnotatedString.Range(it.item.merge(SpanStyle(letterSpacing = tight)), 0, lines.length) }
        val layout = measureLines(AnnotatedString(lines.toString(), spanStyles = runSpans), style.merge(TextStyle(letterSpacing = tight)))
        val whole = layout.getHorizontalPosition(ends[0], usePrimaryDirection = true)
        return suspects.filterIndexedTo(HashSet()) { i, _ -> layout.getHorizontalPosition(ends[i + 1], usePrimaryDirection = true) != whole }
    }

    /**
     * The sums at [offsets] of a right-to-left paragraph. There a position is the line's right
     * edge less the sum, which is exact when the sum is at least half the line (Sterbenz), so
     * each sum is read from the shortest beginning of the text that ends at a break and still
     * holds it; up to a break, the text is shaped as in the whole paragraph.
     */
    private fun rightToLeftSums(text: AnnotatedString, style: TextStyle, offsets: List<Int>): Map<Int, Float> {
        val out = HashMap<Int, Float>()
        var layout: TextLayoutResult? = null
        for (k in offsets.distinct().sortedDescending()) {
            if (k == 0) {
                out[0] = 0f
                continue
            }
            val current = layout
            if (current != null) {
                val right = current.getHorizontalPosition(0, usePrimaryDirection = true)
                val sum = right - current.getHorizontalPosition(k, usePrimaryDirection = true)
                if (sum >= right / 2) {
                    out[k] = sum
                    continue
                }
            }
            val prefix = rightToLeftLine(text.subSequence(0, k), style)
            layout = prefix
            out[k] = prefix.getHorizontalPosition(0, usePrimaryDirection = true) - prefix.getHorizontalPosition(k, usePrimaryDirection = true)
        }
        return out
    }

    /** [text] and a space as one right-aligned, right-to-left line exactly as wide as it is. */
    private fun rightToLeftLine(text: AnnotatedString, style: TextStyle): TextLayoutResult =
        measurer.measure(
            withSpace(text),
            style.merge(TextStyle(textAlign = TextAlign.Right, textDirection = TextDirection.Rtl, textIndent = TextIndent.None, lineBreak = LineBreak.Simple)),
            overflow = TextOverflow.Clip,
            softWrap = false,
        )

    /**
     * [text] on one line, followed by a space. Positions on it are the advances summed from the
     * line's start, as minikin sums them for breaking; the space keeps every position inside the
     * run (at a run's very end, Android adds up whole words instead, which rounds differently).
     */
    private fun measureLine(text: AnnotatedString, style: TextStyle, direction: TextDirection = TextDirection.Unspecified): TextLayoutResult =
        measureLines(withSpace(text), style, direction)

    /** [text] as it is, each line feed starting a line at the left edge, in [direction] or the style's. */
    private fun measureLines(text: AnnotatedString, style: TextStyle, direction: TextDirection = TextDirection.Unspecified): TextLayoutResult =
        measurer.measure(
            text,
            style.merge(TextStyle(textAlign = TextAlign.Left, textDirection = direction, textIndent = TextIndent.None, lineBreak = LineBreak.Simple)),
            overflow = TextOverflow.Clip,
            softWrap = false,
            constraints = Constraints(minWidth = WIDE, maxWidth = WIDE),
        )

    /** [text] with a space after it in the style of its last character. */
    private fun withSpace(text: AnnotatedString): AnnotatedString {
        val len = text.length
        return AnnotatedString(text.text + " ", spanStyles = text.spanStyles.map { if (it.end == len) AnnotatedString.Range(it.item, it.start, len + 1) else it })
    }

    /** The style of the run at [index]: [style] with the spans over it. */
    private fun runStyle(style: TextStyle, spans: List<AnnotatedString.Range<SpanStyle>>, index: Int): SpanStyle {
        var run = style.toSpanStyle()
        for (span in spans) if (index >= span.start && index < span.end) run = run.merge(span.item)
        return run
    }

    /**
     * The font size as minikin has it, which sets the line penalty: in px, cut to a whole pixel
     * (Compose's text paint has no linear metrics).
     */
    private fun minikinSize(size: TextUnit, base: TextUnit, density: Density): Float {
        val px = with(density) {
            when {
                size.type == TextUnitType.Sp -> size.toPx()
                size.type == TextUnitType.Em && base.type == TextUnitType.Sp -> base.toPx() * size.value
                base.type == TextUnitType.Sp -> base.toPx()
                else -> DEFAULT_FONT_SIZE * density.density * density.fontScale
            }
        }
        return px.toInt().toFloat()
    }

    private companion object {
        const val SCORE_OVERFULL = 1e12f
        const val SCORE_DESPERATE = 1e10f
        const val LINE_PENALTY_MULTIPLIER = 2f
        const val DEFAULT_FONT_SIZE = 14f

        /** A width no line of text reaches (px). */
        const val WIDE = 100_000

        /** How far two sums of the same advances can differ, added in another order (px). */
        const val ROUNDING = 0.05f

        /** How close two advances are to be a ligature's equal shares (px). */
        const val EVEN_SPLIT = 0.002f

        const val ZWNJ = '‌'
    }
}

/**
 * [text] with its lines ended where [ends] says: the space a line ends on becomes a line feed,
 * and a line that ends without one (between ideographs, after a slash, inside a word too long
 * for the line) gets a line feed put in. When at most [maxLines] lines are shown, the last one
 * shown is left to hold the rest, which Android ellipsizes as it does now. Spans keep their
 * characters; a line feed put in takes the spans of the character before it.
 *
 * Set in [BalancedBreaker.Lines.style] with the same maxLines and overflow, and with
 * [BalancedBreaker.Lines.constraints] (as [BalancedBreaker.layout] sets it), it draws the pixels
 * the balanced text draws. Only a paragraph that mixes directions can come out differently: a
 * neutral character next to a break (a bracket, a quotation mark, a no-break space) can take
 * its direction from the line on the other side, and Android orders the runs of a paragraph's
 * later lines by levels it reads from the paragraph's start, which a line of its own doesn't
 * share.
 */
fun withLineFeeds(text: AnnotatedString, ends: IntArray, maxLines: Int = Int.MAX_VALUE): AnnotatedString {
    val s = text.text
    val replace = HashSet<Int>()
    val insert = ArrayList<Int>()
    for ((line, end) in ends.withIndex()) {
        if (line >= maxLines - 1 || end >= s.length || end <= 0 || s[end - 1] == '\n') continue
        if (s[end - 1] == ' ') replace += end - 1 else insert += end
    }
    if (replace.isEmpty() && insert.isEmpty()) return text
    val out = StringBuilder()
    var next = 0
    for (i in s.indices) {
        if (next < insert.size && insert[next] == i) {
            out.append('\n')
            next++
        }
        out.append(if (i in replace) '\n' else s[i])
    }
    // An index moves one place on for every line feed put in at or before it: a span that ends
    // where a line feed goes in takes it, one that starts there begins after it.
    fun moved(index: Int) = index + insert.count { it <= index }
    fun <T> remap(ranges: List<AnnotatedString.Range<T>>) = ranges.map { AnnotatedString.Range(it.item, moved(it.start), moved(it.end)) }
    return AnnotatedString(out.toString(), spanStyles = remap(text.spanStyles), paragraphStyles = remap(text.paragraphStyles))
}

/** The spans of [text] that change how wide its characters are, without the ones that only color them. */
private fun metricSpans(text: AnnotatedString): List<AnnotatedString.Range<SpanStyle>> = text.spanStyles.mapNotNull { range ->
    val s = range.item
    val metric = SpanStyle(
        fontSize = s.fontSize,
        fontWeight = s.fontWeight,
        fontStyle = s.fontStyle,
        fontSynthesis = s.fontSynthesis,
        fontFamily = s.fontFamily,
        fontFeatureSettings = s.fontFeatureSettings,
        letterSpacing = s.letterSpacing,
        baselineShift = s.baselineShift,
        textGeometricTransform = s.textGeometricTransform,
        localeList = s.localeList,
    )
    if (metric == SpanStyle() || range.start >= range.end) null else AnnotatedString.Range(metric, range.start, range.end)
}

/**
 * True if minikin shapes the text before [index] apart from the text after it (its layout
 * pieces end at spaces and bidirectional controls, and before CJK ideographs), so no kerning
 * crosses [index].
 */
private fun isCacheBreak(text: CharSequence, index: Int): Boolean {
    fun after(c: Int) = c == 0x20 || c in 0x2000..0x200A || c == 0x3000 || c in 0x2066..0x2069 || c in 0x202A..0x202E || c == 0x200E || c == 0x200F
    return after(text[index - 1].code) || after(text[index].code) || text[index].code in 0x3400..0x9FFF
}

/** A space that disappears at the end of a line: Unicode's spaces but the no-break ones, and the line feed. */
private fun isLineEndSpace(c: Char): Boolean {
    val code = c.code
    return c == '\n' || c == ' ' || code == 0x1680 || (code in 0x2000..0x200A && code != 0x2007) || code == 0x205F || code == 0x3000
}

/**
 * The word breaks of minikin's WordBreaker: ICU's boundaries ([icu]), but never right after a
 * hyphen or a soft hyphen (breaking there is hyphenation's job, which Compose leaves off),
 * never inside an emoji sequence, and inside an email address or a URL only where the Chicago
 * Manual of Style allows it.
 */
private class WordBreaker(private val text: String, private val icu: IntArray) {
    private var current = 0
    private var last = 0
    private var scanOffset = 0
    private var inEmailOrUrl = false
    private var icuIndex = 0

    /** The next word break, or -1 after the end. */
    fun next(): Int {
        last = current
        detectEmailOrUrl()
        current = if (inEmailOrUrl) nextInEmailOrUrl() else nextFromIcu()
        return current
    }

    /** 1 for a break inside an email address or a URL, which costs as much as a hyphen. */
    fun badness(): Int = if (inEmailOrUrl && current < scanOffset) 1 else 0

    /** ICU's first boundary after [offset]. */
    private fun following(offset: Int): Int {
        var low = 0
        var high = icu.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (icu[middle] <= offset) low = middle + 1 else high = middle
        }
        icuIndex = low
        return if (icuIndex < icu.size) icu[icuIndex] else DONE
    }

    private fun icuNext(): Int {
        if (icuIndex < icu.size) icuIndex++
        return if (icuIndex < icu.size) icu[icuIndex] else DONE
    }

    private fun isBoundary(offset: Int): Boolean = offset == 0 || icu.contains(offset)

    private fun nextFromIcu(): Int {
        var result = following(current)
        while (!isValidBreak(result)) result = icuNext()
        return result
    }

    private fun isValidBreak(i: Int): Boolean {
        if (i == DONE || i == text.length) return true
        var before = codePointBefore(text, i)
        var beforeStart = i - charCount(before)
        if (isLineBreakingHyphen(before) || before == SOFT_HYPHEN) return false
        // The Myanmar virama stacks; ICU breaks after it in kinzi sequences.
        if (before == 0x1039) return false
        val after = codePointAt(text, i)
        if (before == ZWJ && inRanges(LineBreakTables.emoji, after)) return false
        if (inRanges(LineBreakTables.emojiModifier, after)) {
            if (before == 0xFE0F && beforeStart > 0) {
                before = codePointBefore(text, beforeStart)
                beforeStart -= charCount(before)
            }
            if (isEmojiBase(before)) return false
        }
        return true
    }

    private fun detectEmailOrUrl() {
        if (last < scanOffset) return
        var state = START
        var i = last
        while (i < text.length) {
            val c = text[i].code
            if (c <= 0x20 || c > 0x7E) break
            if (state == START && c == '@'.code) {
                state = SAW_AT
            } else if (state == START && c == ':'.code) {
                state = SAW_COLON
            } else if (state == SAW_COLON || state == SAW_COLON_SLASH) {
                state = if (c == '/'.code) state + 1 else START
            }
            i++
        }
        if (state == SAW_AT || state == SAW_COLON_SLASH_SLASH) {
            if (!isBoundary(i)) i = following(i)
            inEmailOrUrl = true
        } else {
            inEmailOrUrl = false
        }
        scanOffset = i
    }

    private fun nextInEmailOrUrl(): Int {
        var lastChar = text[last]
        var i = last + 1
        while (i < scanOffset) {
            if (lastChar == ':' || lastChar == '=' || lastChar == '&') break
            if (lastChar == '/' && i >= last + 2 && text[i - 2] == '/') break
            val thisChar = text[i]
            if (lastChar != '-') {
                if (thisChar in BREAK_BEFORE) break
                if (thisChar == '/' && lastChar != '/' && !(i + 1 < scanOffset && text[i + 1] == '/')) break
            }
            lastChar = thisChar
            i++
        }
        return i
    }

    private companion object {
        const val DONE = -1
        const val SOFT_HYPHEN = 0xAD
        const val ZWJ = 0x200D
        const val START = 0
        const val SAW_AT = 1
        const val SAW_COLON = 2
        const val SAW_COLON_SLASH = 3
        const val SAW_COLON_SLASH_SLASH = 4
        const val BREAK_BEFORE = "~.,-_?#%=&"

        fun isLineBreakingHyphen(c: Int): Boolean =
            c == 0x002D || c == 0x058A || c == 0x05BE || c == 0x1400 || c == 0x2010 || c == 0x2013 || c == 0x2027 || c == 0x2E17 || c == 0x2E40

        /** Two handshakes that left Unicode's emoji bases but that fonts still treat as bases. */
        fun isEmojiBase(c: Int): Boolean = c == 0x1F91D || c == 0x1F93C || inRanges(LineBreakTables.emojiBase, c)
    }
}
