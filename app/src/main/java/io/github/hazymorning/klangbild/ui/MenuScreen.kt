package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawBehind
import io.github.hazymorning.klangbild.math.smoothstep
import io.github.hazymorning.klangbild.ui.stage.BEYOND_EDGES_DP
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.app.MenuState
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.CoverPalette
import io.github.hazymorning.klangbild.color.Lamp
import io.github.hazymorning.klangbild.config.MenuEntry
import io.github.hazymorning.klangbild.config.MenuRow
import io.github.hazymorning.klangbild.config.MenuSection
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.render.LampLight
import io.github.hazymorning.klangbild.render.ImageOps
import io.github.hazymorning.klangbild.render.LinearImage
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.WallGlow
import io.github.hazymorning.klangbild.text.texts
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.textType
import io.github.hazymorning.klangbild.ui.theme.titleType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.exp
import kotlin.math.min

/** Geometry of the menu (dp): the line under the cursor always sits at the screen's middle. */
object MenuGrid {
    /** Pitch of the lines. */
    const val ROW = 40f

    /** Lines start to fade at this distance from the cursor's line. */
    const val FADE_FROM = 56f

    /** Lines are gone at this distance, well before the black band along the screen's edges. */
    const val FADE_TO = 226f

    /**
     * The light behind the cursor's line: a soft, low band (Gaussian σ across and along the
     * line, dp) that lights this line only, and its brightest point (≈ L* 10).
     */
    const val GLOW_SIGMA_X = 120f
    const val GLOW_SIGMA_Y = 12f
    const val GLOW_PEAK = 0.011f

    /** A line's label and value: size (sp) and weight. */
    const val LABEL_SP = 21f
    const val LABEL_WEIGHT = 480
    const val VALUE_SP = 17f
    const val VALUE_WEIGHT = 440

    /** A value on a scale has a slim track before it (dp): its length, and the room after it. */
    const val SLIDER_WIDTH = 72f
    const val SLIDER_GAP = 14f

    /** The least room between a line's label and its value (dp): a line needs no more than the column. */
    const val LABEL_GAP = 16f

    /** Opacity of a line at [distance] dp from the cursor's line. */
    fun fade(distance: Float): Float = 1f - smoothstep(FADE_FROM, FADE_TO, abs(distance))
}

/**
 * The settings menu over whatever is on screen. It fades in over a dark wall; the stage
 * behind it keeps running and is there again the moment the menu closes. While the queue's way
 * just chosen is [preparing], the Queue line's value shimmers ([Shimmer]).
 */
@Composable
fun MenuOverlay(menu: MenuState?, appearance: Appearance, modifier: Modifier = Modifier, preparing: Boolean = false) {
    // The last menu stays while it fades out.
    val last = remember { arrayOfNulls<MenuState>(1) }
    if (menu != null) last[0] = menu
    val shown = remember { Animatable(0f) }
    LaunchedEffect(menu != null) {
        shown.animateTo(if (menu != null) 1f else 0f, tween(if (menu != null) 450 else 600, easing = Motion.Dimmer))
    }
    if (menu == null && shown.value <= 0f) return
    val state = menu ?: last[0] ?: return
    // Faded draw by draw, not through a layer of the screen's size: the menu's ground reaches
    // beyond the screen's edges, and a layer would cut it off there while it fades.
    MenuScreen(
        state,
        modifier.graphicsLayer {
            alpha = shown.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
        appearance,
        preparing,
    )
}

/** The menu's colors: near-neutral white on black or near-black on paper. */
private class MenuColors(
    val wall: Color,
    /** The light behind the chosen line, added to the wall. */
    val band: Color,
    val heading: Color,
    val description: Color,
    val hint: Color,
    val label: Color,
    val labelFocus: Color,
    val value: Color,
    val valueFocus: Color,
    /** A line that cannot work yet: quieter, and chosen no brighter than another line at rest. */
    val dim: Color,
) {
    companion object {
        fun of(appearance: Appearance): MenuColors {
            fun c(l: Float, chroma: Float = 0.004f) = Color(Lamp.neutral(l, chroma))
            return if (appearance == Appearance.DARK) {
                MenuColors(
                    wall = Color.Black,
                    band = Color(LampLight.lightTint(LampLight.LAMP, MenuGrid.GLOW_PEAK)),
                    heading = c(0.90f),
                    description = c(0.72f),
                    hint = c(0.60f),
                    label = c(0.64f),
                    labelFocus = c(0.93f),
                    value = c(0.60f),
                    valueFocus = c(0.82f),
                    dim = c(0.57f),
                )
            } else {
                // On paper the chosen line lies in a band of slightly whiter light.
                MenuColors(
                    wall = c(CoverPalette.PAPER, 0.006f),
                    band = Color(0xFF0D0C0B),
                    heading = c(0.26f),
                    description = c(0.42f),
                    hint = c(0.52f),
                    label = c(0.48f),
                    labelFocus = c(0.24f),
                    value = c(0.52f),
                    valueFocus = c(0.36f),
                    dim = c(0.53f),
                )
            }
        }
    }
}

/**
 * The menu itself, on the stage's grid, in two levels: the groups, then the settings of the one
 * opened. The heading (the group's name inside one) and the chosen line's explanation hang where
 * the print would be; the lines stand in the text column. The line under the cursor stays in the
 * middle of the screen, lit by a lamp; the list glides through that light and fades with
 * distance (no frame, no scroll bar, no check box). Values are words. Going into a group or back
 * out, the level left fades and the new one comes up, already resting on its line.
 */
@Composable
fun MenuScreen(menu: MenuState, modifier: Modifier = Modifier, appearance: Appearance = Appearance.DARK, preparing: Boolean = false) {
    val colors = remember(appearance) { MenuColors.of(appearance) }
    // The level left keeps showing as it was while it fades.
    val levels = remember { HashMap<MenuSection?, MenuState>() }
    levels[menu.section] = menu

    BoxWithConstraints(
        modifier.fillMaxSize().drawBehind {
            // The ground reaches beyond the screen: the drift against burn-in never shows what lies under it.
            val m = BEYOND_EDGES_DP * density
            drawRect(colors.wall, Offset(-m, -m), Size(size.width + 2 * m, size.height + 2 * m))
        },
    ) {
        val density = LocalDensity.current.density
        val screen = ScreenSpec((maxWidth.value * density).roundToInt(), (maxHeight.value * density).roundToInt(), density)
        val layout = StageGrid(screen)
        val focusY = maxHeight.value / 2f

        val glowCx = layout.columnLeft + layout.columnWidth / 2f
        val field = rememberOffMain(screen, colors) {
            val px = glowField(glowCx, focusY, screen.widthDp, screen.heightDp, ImageOps.ditherFor(colors.band.toArgb()))
            Bitmap.createBitmap(px, GLOW_MAP_W, GLOW_MAP_H, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
        if (field != null) Canvas(Modifier.fillMaxSize()) {
            // The band's light adds to the wall, on black and on paper alike.
            drawImage(
                field,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                colorFilter = ColorFilter.tint(colors.band, BlendMode.Modulate),
                filterQuality = FilterQuality.Low,
                blendMode = BlendMode.Plus,
            )
        }
        // The level left goes first, then the new one comes: two lists never lie over each other.
        AnimatedContent(
            targetState = menu.section,
            transitionSpec = {
                fadeIn(tween(LEVEL_IN_MS, delayMillis = LEVEL_OUT_MS, easing = Motion.Dimmer)) togetherWith
                    fadeOut(tween(LEVEL_OUT_MS, easing = Motion.Dimmer))
            },
            label = "menu-level",
        ) { section ->
            MenuLevel(levels[section] ?: menu, layout, focusY, colors, preparing)
        }
    }
}

/** One level of the menu: its heading, the chosen line's explanation and hint on the left, its lines on the right. */
@Composable
private fun MenuLevel(menu: MenuState, layout: StageGrid, focusY: Float, colors: MenuColors, preparing: Boolean) {
    val cursor = menu.cursor.coerceIn(0, menu.rows.size - 1)
    val row = menu.rows[cursor]
    val t = texts()
    // The list moves so that the chosen line comes to rest in the light; a held key runs it on evenly.
    val offset = animateGlide(focusY - (cursor * MenuGrid.ROW + MenuGrid.ROW / 2f), GLIDE_MS)

    // Left: the heading at the print's top edge, the explanation level with the chosen line.
    val headingStyle = titleType(36f, 420, colors.heading)
    BaselineAt(x = layout.margin, baseline = layout.artTop + 36f * Fonts.CAP_HEIGHT, width = layout.art) {
        BasicText(menu.section?.let { t[it.title] } ?: t[R.string.menu_heading], style = headingStyle)
    }
    // While a key is held the list runs on under the light: the explanation goes, and comes
    // back with the line the key is let go on.
    Crossfade(
        targetState = row.description.takeUnless { menu.held },
        animationSpec = tween(320, easing = Motion.Dimmer),
        label = "menu-description",
    ) { text ->
        if (text != null) BaselineAt(x = layout.margin, baseline = focusY + LABEL_BASELINE_FROM_CENTER, width = layout.art - 24f) {
            BasicText(
                text,
                style = textType(18f, 420, colors.description, lineHeight = 26f).merge(
                    TextStyle(lineBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)),
                ),
            )
        }
    }
    BaselineAt(x = layout.margin, baseline = layout.artTop + layout.art, width = layout.art) {
        BasicText(t[if (menu.section == null) R.string.menu_hint_groups else R.string.menu_hint], style = textType(15f, 500, colors.hint, tracking = 0.01f))
    }

    // Right: the lines in the text column.
    val labelStyle = textType(MenuGrid.LABEL_SP, MenuGrid.LABEL_WEIGHT)
    val valueStyle = textType(MenuGrid.VALUE_SP, MenuGrid.VALUE_WEIGHT)
    menu.rows.forEachIndexed { i, line ->
        Box(
            Modifier
                .offset(x = layout.columnLeft.dp)
                .width(layout.columnWidth.dp)
                .height(MenuGrid.ROW.dp)
                .graphicsLayer {
                    val y = i * MenuGrid.ROW + offset.value
                    translationY = (y * this.density).roundToInt().toFloat()
                    alpha = MenuGrid.fade(y + MenuGrid.ROW / 2f - focusY)
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            MenuItem(line, i == cursor, labelStyle, valueStyle, colors, preparing)
        }
    }
}

@Composable
private fun MenuItem(row: MenuRow, focused: Boolean, labelStyle: TextStyle, valueStyle: TextStyle, colors: MenuColors, preparing: Boolean) {
    // A line that cannot work yet stays quiet; chosen, it is only as bright as another line at rest.
    val label = when {
        row.dimmed -> if (focused) colors.label else colors.dim
        else -> if (focused) colors.labelFocus else colors.label
    }
    val value = when {
        row.dimmed -> if (focused) colors.value else colors.dim
        else -> if (focused) colors.valueFocus else colors.value
    }
    val labelColor by animateColorAsState(label, tween(260, easing = Motion.Dimmer), label = "label")
    val valueColor by animateColorAsState(value, tween(260, easing = Motion.Dimmer), label = "value")
    val lift = with(LocalDensity.current) { SLIDER_LIFT.dp.roundToPx() }
    // The queue's way just chosen is on its way: the chosen Queue line's value waits in the quiet
    // ink of a name at rest (its own name stands brighter than its value) and shimmers until the
    // screen shows it. A line at rest has no quieter ink left to wait in that still reads well, so
    // it stays as it is, and a pass running as the cursor leaves ends in the line's own ink.
    val sweep = if (row.entry == MenuEntry.QUEUE) rememberSweep(preparing && focused) else null
    Row(Modifier.fillMaxWidth()) {
        BasicText(row.label, Modifier.alignByBaseline(), style = labelStyle, color = { labelColor }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        // A value on a scale shows where it stands: a slim track like the progress bar's, before the number.
        row.level?.let { level ->
            val fill by animateFloatAsState(level, tween(260, easing = Motion.Dimmer), label = "level")
            Canvas(
                Modifier
                    .alignBy { it.measuredHeight + lift }
                    .padding(end = MenuGrid.SLIDER_GAP.dp)
                    .size(MenuGrid.SLIDER_WIDTH.dp, SLIDER_HEIGHT.dp),
            ) {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(valueColor.copy(alpha = valueColor.alpha * SLIDER_TRACK), cornerRadius = r)
                if (fill > 0f) drawRoundRect(valueColor, size = Size(maxOf(size.height, size.width * fill), size.height), cornerRadius = r)
            }
        }
        Crossfade(
            targetState = row.value,
            modifier = Modifier.alignByBaseline(),
            animationSpec = tween(260, easing = Motion.Dimmer),
            label = "menu-value",
        ) { value ->
            if (sweep != null) {
                ShimmerText(value, valueStyle, sweep, ink = { valueColor }, waiting = if (focused) colors.label else colors.value)
            } else {
                BasicText(value, style = valueStyle, color = { valueColor }, maxLines = 1)
            }
        }
    }
}

/** Places [content] so that its first baseline lies at [baseline] (dp from the top). */
@Composable
private fun BaselineAt(x: Float, baseline: Float, width: Float, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val p = measurables.first().measure(Constraints(maxWidth = (width * density).roundToInt()))
        val b = p[FirstBaseline].takeIf { it != androidx.compose.ui.layout.AlignmentLine.Unspecified } ?: 0
        layout(constraints.maxWidth, constraints.maxHeight) {
            p.place((x * density).roundToInt(), (baseline * density).roundToInt() - b)
        }
    }
}

/**
 * The slider of a value on a scale: its size (dp), its gap to the number, how strongly its track shows, and how far
 * above the baseline its lower edge sits (its middle on the figures' middle).
 */
private const val SLIDER_HEIGHT = 4f
private const val SLIDER_TRACK = 0.28f
private const val SLIDER_LIFT = 4f

/** The list's glide by a line from rest (ms). */
private const val GLIDE_MS = 340

/** A group opening, or the menu going back to the groups: the level left fades, then the new one comes (ms). */
private const val LEVEL_OUT_MS = 140
private const val LEVEL_IN_MS = 220

/** From a line's center to its label's baseline (21 sp in a 40 dp line). */
private const val LABEL_BASELINE_FROM_CENTER = 7f

private const val GLOW_MAP_W = 480
private const val GLOW_MAP_H = 270

/**
 * The light behind the chosen line: a soft, low band around ([cx], [cy]) dp that lights
 * this line and not its neighbors (grayscale, dithered, tinted when drawn). It never moves;
 * the list moves through it. Like every light, it is gone before the band along the screen's
 * edges.
 */
fun glowField(cx: Float, cy: Float, widthDp: Float, heightDp: Float, ditherLsb: Float): IntArray {
    val img = LinearImage(GLOW_MAP_W, GLOW_MAP_H)
    val sx = widthDp / GLOW_MAP_W
    val sy = heightDp / GLOW_MAP_H
    for (y in 0 until GLOW_MAP_H) for (x in 0 until GLOW_MAP_W) {
        val px = (x + 0.5f) * sx
        val py = (y + 0.5f) * sy
        val u = (px - cx) / MenuGrid.GLOW_SIGMA_X
        val v = (py - cy) / MenuGrid.GLOW_SIGMA_Y
        val edges = smoothstep(WallGlow.EDGE_BLACK_DP, WallGlow.EDGE_BLACK_DP + 80f, min(px, widthDp - px)) *
            smoothstep(WallGlow.EDGE_BLACK_DP, WallGlow.EDGE_BLACK_DP + 40f, min(py, heightDp - py))
        val value = exp(-0.5f * (u * u + v * v)) * edges
        val o = y * GLOW_MAP_W + x
        img.r[o] = value
        img.g[o] = value
        img.b[o] = value
    }
    return ImageOps.toArgbDithered(img, seed = 5, ditherLsb = ditherLsb, blackBelow = 0.0005f)
}
