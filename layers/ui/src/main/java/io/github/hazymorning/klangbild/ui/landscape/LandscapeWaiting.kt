package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hazymorning.klangbild.landscape.BakeProgress
import io.github.hazymorning.klangbild.text.R
import io.github.hazymorning.klangbild.text.texts
import io.github.hazymorning.klangbild.ui.LocalStill
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.QuietColors
import io.github.hazymorning.klangbild.ui.stage.BAR_THICKNESS
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.rememberInkShift
import io.github.hazymorning.klangbild.ui.theme.textType
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The sign that the landscape is on its way while its first picture is baked (a few seconds, more
 * at 4K): one quiet line of words over a bar drawn like the stage's progress bar, the two together
 * in the screen's very middle (from the words' cap height to the bar's lower edge). The bar fills
 * as the paper is cut ([progress]), at an even pace ([WaitingPace]) and never going back; once the
 * picture is ready it runs to its end, and the sign goes while the landscape comes up. It comes
 * only when the picture keeps one waiting, so a picture baked ahead comes up without it.
 */
@Composable
internal fun LandscapeWaiting(ready: Boolean, progress: () -> BakeProgress = { BakeProgress.NONE }) {
    val still = LocalStill.current
    val alpha = remember { Animatable(if (still) 1f else 0f) }
    var gone by remember { mutableStateOf(ready) }
    val filled = remember { mutableFloatStateOf(if (still) progress().done else 0f) }
    if (!still) {
        LaunchedEffect(ready) {
            if (ready) {
                alpha.animateTo(0f, tween(WAIT_OUT_MS, easing = Motion.Sine))
                gone = true
            } else {
                gone = false
                delay(WAIT_AFTER_MS)
                alpha.animateTo(1f, tween(WAIT_IN_MS, easing = Motion.Sine))
            }
        }
    }
    if (gone) return
    if (!still) {
        val isReady by rememberUpdatedState(ready)
        val baked by rememberUpdatedState(progress)
        LaunchedEffect(Unit) {
            val pace = WaitingPace()
            while (true) withFrameNanos { ns -> filled.floatValue = pace.at(ns / 1e9, baked(), isReady) }
        }
    }
    val colors = remember { QuietColors.of() }
    val style = textType(WAIT_TEXT_SP, WAIT_TEXT_WEIGHT, colors.secondary)
    val words = texts()[R.string.landscape_waiting]
    // Centered on its ink: the first and last letters' side bearings differ.
    val shift = rememberInkShift(words, style)
    Layout(
        content = {
            BasicText(words, Modifier.offset { IntOffset(shift.roundToInt(), 0) }, style = style, maxLines = 1)
            Canvas(Modifier.size(WAIT_BAR_DP.dp, BAR_THICKNESS.dp)) {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(colors.primary.copy(alpha = 0.16f), cornerRadius = r)
                val f = filled.floatValue
                // Never shorter than it is high: a round dot at the very start.
                if (f > 0f) drawRoundRect(colors.primary, Offset.Zero, Size((size.width * f).coerceAtLeast(size.height), size.height), r)
            }
        },
        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val text = measurables[0].measure(loose)
        val bar = measurables[1].measure(loose)
        val cap = WAIT_TEXT_SP.sp.toPx() * Fonts.CAP_HEIGHT
        val gap = WAIT_GAP_DP.dp.toPx()
        // The bar on whole pixels, so its edges stay crisp; the words hang above it.
        val barTop = ((constraints.maxHeight - cap - gap - bar.height) / 2f + cap + gap).roundToInt()
        val baseline = barTop - gap
        layout(constraints.maxWidth, constraints.maxHeight) {
            text.place((constraints.maxWidth - text.width) / 2, (baseline - text[FirstBaseline]).roundToInt())
            bar.place((constraints.maxWidth - bar.width) / 2, barTop)
        }
    }
}

/**
 * How far the waiting bar is filled. A bake tells its progress only as each part starts, and some
 * parts take far longer than others, so the bar runs on between the reports at the pace measured
 * so far, never past the end of the part under way and never back. Before the first part is done
 * it guesses [FIRST_GUESS_S] for the whole bake. Once the picture is ready it runs quickly to its end.
 */
internal class WaitingPace {
    /** How much of the bar is filled (0 to 1). */
    var filled = 0f
        private set
    private var frameAt = Double.NaN

    // The first report seen and the latest one: when (s) and how far.
    private var firstAt = Double.NaN
    private var first = 0f
    private var lastAt = 0.0
    private var last = -1f
    private var pace = 1f / FIRST_GUESS_S

    /** The bar at [now] (s), with the bake [baked] so far and the picture [ready] or not. */
    fun at(now: Double, baked: BakeProgress, ready: Boolean): Float {
        val dt = if (frameAt.isNaN()) 0f else (now - frameAt).toFloat()
        frameAt = now
        if (baked.done != last) {
            if (firstAt.isNaN() || baked.done < last) {
                // The first report, or a bake starting over: the pace is measured from here.
                firstAt = now
                first = baked.done
            } else if (now > firstAt) {
                pace = ((baked.done - first) / (now - firstAt)).toFloat()
            }
            last = baked.done
            lastAt = now
        }
        val aim = if (ready) 1f else minOf(maxOf(baked.upTo, baked.done), last + pace * (now - lastAt).toFloat())
        if (aim > filled) filled += (aim - filled) * (1f - exp(-dt / (if (ready) FINISH_S else FOLLOW_S)))
        return filled
    }

    private companion object {
        /** The whole bake's time guessed before its first part is done (s): a slow device's at 4K. */
        const val FIRST_GUESS_S = 15f

        /** How closely the bar follows its aim (s), and how quickly it runs to its end once the picture is ready. */
        const val FOLLOW_S = 0.3f
        const val FINISH_S = 0.1f
    }
}

/**
 * The sign comes after this long (the screen switch keeps the landscape dark for its first
 * 900 ms anyway) and fades in this long (ms); it goes quicker, gone while the landscape is still
 * dark, so the two never lie over each other.
 */
private const val WAIT_AFTER_MS = 1_000L
private const val WAIT_IN_MS = 600
private const val WAIT_OUT_MS = 300

/** The words (sp, weight), the room from their baseline down to the bar, and the bar's width (dp). */
private const val WAIT_TEXT_SP = 17f
private const val WAIT_TEXT_WEIGHT = 440
private const val WAIT_GAP_DP = 16f
private const val WAIT_BAR_DP = 240f
