package io.github.hazymorning.klangbild.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * Three dots after a value while what it names is being prepared (the queue display just chosen,
 * until the screen shows it), the way an ellipsis says that something is in progress. They fade
 * in, pulse one after another for as long as the wait lasts, and fade out when it ends. The value
 * itself keeps its ink. Their alpha is read while drawing, so the pulse only redraws.
 */
@Stable
internal class PendingDots(private val shown: State<Float>, private val phase: State<Float>) {
    /** Draws the dots after [text], on its baseline, in [ink]. */
    fun draw(scope: DrawScope, text: TextLayoutResult, ink: Color) = with(scope) {
        val shown = shown.value
        if (shown <= 0f) return@with
        val em = text.layoutInput.style.fontSize.toPx()
        val r = DOT_EM * em / 2f
        val y = text.firstBaseline - r
        val x0 = text.size.width + GAP_EM * em + r
        for (i in 0 until 3) {
            val p = phase.value - i * STAGGER
            val pulse = (1f - cos(2f * PI.toFloat() * (p - floor(p)))) / 2f
            val a = shown * (LOW + (1f - LOW) * pulse)
            drawCircle(ink, r, Offset(x0 + i * STEP_EM * em, y), alpha = ink.alpha * a)
        }
    }

    companion object {
        /** The room the dots take after a value, in ems of its size. */
        const val WIDTH_EM = GAP_EM + 2 * STEP_EM + DOT_EM
    }
}

/** The dots for a value; they show while [pending]. In a still they stand as they are. */
@Composable
internal fun rememberPendingDots(pending: Boolean): PendingDots {
    val shown = remember { Animatable(if (pending) 1f else 0f) }
    val phase = remember { Animatable(0f) }
    if (!LocalStill.current) {
        LaunchedEffect(pending) {
            if (pending) {
                launch { shown.animateTo(1f, tween(FADE_MS, easing = Motion.Dimmer)) }
                while (true) {
                    phase.snapTo(0f)
                    phase.animateTo(1f, tween(CYCLE_MS, easing = LinearEasing))
                }
            } else {
                shown.animateTo(0f, tween(FADE_MS, easing = Motion.Dimmer))
            }
        }
    }
    return remember { PendingDots(shown.asState(), phase.asState()) }
}

/** [text] on one line in [style] and [color], with [dots] after it while they show. */
@Composable
internal fun ValueText(text: String, style: TextStyle, color: () -> Color, dots: PendingDots?, modifier: Modifier = Modifier) {
    if (dots == null) {
        BasicText(text, modifier, style = style, color = color, maxLines = 1)
        return
    }
    // Laid out before it is drawn, so the dots know where the value ends.
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    BasicText(
        text,
        modifier.drawWithContent {
            drawContent()
            layout[0]?.let { dots.draw(this, it, color()) }
        },
        style = style,
        color = color,
        maxLines = 1,
        onTextLayout = { layout[0] = it },
    )
}

/** A dot's diameter, the space before the first one, and the step from dot to dot, in ems. */
private const val DOT_EM = 0.14f
private const val GAP_EM = 0.28f
private const val STEP_EM = 0.32f

/** One pulse through all three dots (ms), and how far each dot follows the one before, in pulses. */
private const val CYCLE_MS = 1_200
private const val STAGGER = 0.2f

/** A dot's alpha between its pulses. */
private const val LOW = 0.25f

/** The dots fade in and out this fast (ms). */
private const val FADE_MS = 300
