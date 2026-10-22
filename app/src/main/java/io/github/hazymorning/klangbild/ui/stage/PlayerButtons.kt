package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.hazymorning.klangbild.app.QuickState
import io.github.hazymorning.klangbild.config.QuickItem
import io.github.hazymorning.klangbild.config.QuickRow
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.textType
import kotlin.math.roundToInt

/**
 * The player's buttons under the right end of the progress bar: the song's words and the way
 * into the settings, named as the quick settings name them ("Lyrics On", "Settings"). They stand
 * on the stage all the time, so it is plain what the remote can do; down chooses among them
 * ([QuickState.cursor]) and lights the chosen one. Each is as wide as its widest value, so
 * nothing moves when a value changes. Their inks are the stage's and go over with the wall.
 */
@Composable
internal fun PlayerButtons(buttons: QuickState, scene: ArtworkScene, modifier: Modifier = Modifier, alpha: () -> Float = { 1f }) {
    val layout = scene.layout
    val styles = remember(scene.palette) { StageStyles(scene.palette) }
    val inkSpec = tween<androidx.compose.ui.graphics.Color>(Motion.WALL_CHANGE_MS, easing = Motion.Sine)
    val ink by animateColorAsState(styles.ink, inkSpec, label = "button-ink")
    val muted by animateColorAsState(styles.muted, inkSpec, label = "button-muted")
    val quiet by animateColorAsState(styles.quiet, inkSpec, label = "button-quiet")
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val style = remember { textType(BUTTON_SP, BUTTON_WEIGHT, lineHeight = BUTTON_SP * 1.3f, tracking = 0.01f) }
    val texts = remember(buttons.rows, style) { buttons.rows.map { ButtonText.of(it, measurer, style) } }
    val lit: List<State<Float>> = buttons.rows.mapIndexed { i, row ->
        key(row.key) {
            animateFloatAsState(if (i == buttons.cursor) 1f else 0f, tween(FOCUS_MS, easing = Motion.Dimmer), label = "button-lit")
        }
    }
    Canvas(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha().coerceIn(0f, 1f) }) {
        val d = density
        val top = (layout.buttonsTop * d).roundToInt().toFloat()
        val h = ((layout.buttonsBottom - layout.buttonsTop) * d).roundToInt().toFloat()
        val pad = (PAD_DP * d).roundToInt()
        val cap = BUTTON_SP * d * Fonts.CAP_HEIGHT
        // The text's cap height stands in the button's middle.
        val baseline = (top + h / 2f + cap / 2f).roundToInt()
        var right = ((layout.barLeft + layout.barWidth) * d).roundToInt()
        for (i in texts.indices.reversed()) {
            val b = texts[i]
            val f = lit.getOrNull(i)?.value ?: 0f
            val w = b.width + 2 * pad
            val left = right - w
            drawRoundRect(
                ink.copy(alpha = REST_FILL + (CHOSEN_FILL - REST_FILL) * f),
                Offset(left.toFloat(), top),
                Size(w.toFloat(), h),
                CornerRadius(CORNER_DP * d),
            )
            val color = lerp(muted, ink, f)
            drawText(b.label, color = color, topLeft = Offset((left + pad).toFloat(), (baseline - b.label.firstBaseline).roundToInt().toFloat()))
            b.value?.let { v ->
                val x = left + pad + b.labelWidth + b.space
                drawText(v, color = if (b.mutedValue) lerp(quiet, muted, f) else color, topLeft = Offset(x.toFloat(), (baseline - v.firstBaseline).roundToInt().toFloat()))
            }
            right = left - (GAP_DP * d).roundToInt()
        }
    }
}

/**
 * A button's words, set once: its name and its value ("Lyrics" and "On"; the way into the settings
 * only by its name), and how wide it is with the widest value it can show (px).
 */
private class ButtonText(
    val label: TextLayoutResult,
    val value: TextLayoutResult?,
    val labelWidth: Int,
    val space: Int,
    val width: Int,
    val mutedValue: Boolean,
) {
    companion object {
        fun of(row: QuickRow, measurer: TextMeasurer, style: TextStyle): ButtonText {
            fun set(s: String) = measurer.measure(AnnotatedString(s), style, maxLines = 1, softWrap = false)
            val label = set(row.label)
            if (row.item == QuickItem.SETTINGS) return ButtonText(label, null, label.size.width, 0, label.size.width, false)
            val value = set(row.value)
            val widest = row.values.maxOfOrNull { set(it).size.width } ?: value.size.width
            val space = set(" ").size.width
            return ButtonText(label, value, label.size.width, space, label.size.width + space + maxOf(widest, value.size.width), row.muted)
        }
    }
}

/** The buttons' words: the text face at the labels' size, a little heavier (sp, weight). */
private const val BUTTON_SP = 15f
private const val BUTTON_WEIGHT = 500

/** Room beside a button's words, between two buttons, and a button's corners (dp). */
private const val PAD_DP = 18f
private const val GAP_DP = 12f
private const val CORNER_DP = 8f

/** How much of the title's ink a button's ground takes, at rest and chosen. */
private const val REST_FILL = 0.08f
private const val CHOSEN_FILL = 0.22f

/** The light moves to the chosen button in this long (ms). */
private const val FOCUS_MS = 220
