package io.github.hazymorning.klangbild.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow

/**
 * Sets text on balanced lines, of nearly equal length, as its style's [LineBreak] asks for them:
 * [LineBreak.Heading] for titles and the words of songs, [sentenceLineBreak] for sentences.
 * Android sets them with its own line breaker. Where Compose draws with Skia (the JVM and the
 * browser), which has none, [BalancedBreaker] sets them as Android does.
 */
interface LineBalancer {
    /** Where each line of [text] in [style] ends within [maxWidth] px: the offsets `getLineEnd` gives, the last one the text's length. */
    fun lineEnds(text: AnnotatedString, style: TextStyle, maxWidth: Int): IntArray

    /**
     * [text] in [style] laid out within [maxWidth] px, at most [maxLines] lines with [overflow].
     * Its layoutInput holds what sets it so again: the text, the style and the constraints.
     */
    fun layout(text: AnnotatedString, style: TextStyle, maxWidth: Int, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip): TextLayoutResult
}

/** The platform's [LineBalancer], measuring with [measurer]. */
expect fun lineBalancer(measurer: TextMeasurer): LineBalancer

/** Balanced lines for sentences, like the menu's explanations: Android's balanced strategy with normal strictness. */
expect val sentenceLineBreak: LineBreak

/** [text] in [style] on balanced lines ([LineBalancer]) within the width it is given. */
@Composable
expect fun BalancedText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier)
