package io.github.hazymorning.klangbild.ui.text

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak

/** [BalancedBreaker], since Skia has no balanced line breaking of its own. */
actual fun lineBalancer(measurer: TextMeasurer): LineBalancer = BalancedBreaker(measurer)

/** Any break that balances: BalancedBreaker sets every text it is given on balanced lines. */
actual val sentenceLineBreak: LineBreak = LineBreak.Heading

/** A [BasicText] with line feeds where the balanced lines end, as wide as those lines were set for. */
@Composable
actual fun BalancedText(text: AnnotatedString, style: TextStyle, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val lines = remember(text, style, constraints.maxWidth, measurer) { BalancedBreaker(measurer).lines(text, style, constraints.maxWidth) }
        BasicText(
            withLineFeeds(text, lines.ends),
            Modifier.layout { measurable, given ->
                val p = measurable.measure(lines.constraints.copy(minHeight = given.minHeight, maxHeight = given.maxHeight))
                layout(p.width, p.height) { p.place(0, 0) }
            },
            style = lines.style(style),
        )
    }
}
