package io.github.hazymorning.klangbild.ui.text

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/** Android's own line breaker, which balances the lines as the style's [LineBreak] asks. */
actual fun lineBalancer(measurer: TextMeasurer): LineBalancer = object : LineBalancer {
    override fun lineEnds(text: AnnotatedString, style: TextStyle, maxWidth: Int): IntArray {
        val r = measurer.measure(text, style, constraints = Constraints(maxWidth = maxWidth))
        return IntArray(r.lineCount) { r.getLineEnd(it) }
    }

    override fun layout(text: AnnotatedString, style: TextStyle, maxWidth: Int, maxLines: Int, overflow: TextOverflow): TextLayoutResult =
        measurer.measure(text, style, overflow, maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth))
}

actual val sentenceLineBreak: LineBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)

@Composable
actual fun BalancedText(text: AnnotatedString, style: TextStyle, modifier: Modifier) = BasicText(text, modifier, style)
