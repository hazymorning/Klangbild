package io.github.hazymorning.klangbild.ui.text

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * [text] in [style] on balanced lines ([BalancedBreaker]) within the width it is given: a
 * [BasicText] that looks as it does with a balanced line break.
 */
@Composable
fun BalancedText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
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
