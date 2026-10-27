package io.github.hazymorning.klangbild.ui

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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hazymorning.klangbild.app.MenuState
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.CoverPalette
import io.github.hazymorning.klangbild.color.Lamp
import io.github.hazymorning.klangbild.config.MenuEntry
import io.github.hazymorning.klangbild.config.MenuRow
import io.github.hazymorning.klangbild.config.MenuSection
import io.github.hazymorning.klangbild.math.smoothstep
import io.github.hazymorning.klangbild.render.ImageOps
import io.github.hazymorning.klangbild.render.LampLight
import io.github.hazymorning.klangbild.render.LinearImage
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.render.WallGlow
import io.github.hazymorning.klangbild.render.imageBitmapOf
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.ui.stage.BaseWall
import io.github.hazymorning.klangbild.ui.text.BalancedText
import io.github.hazymorning.klangbild.ui.text.sentenceLineBreak
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.faces
import io.github.hazymorning.klangbild.ui.theme.firstBaseline
import io.github.hazymorning.klangbild.ui.theme.textType
import io.github.hazymorning.klangbild.ui.theme.titleType
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

/** Geometry of the menu (dp): the chosen row always stands in the middle of the screen. */
object MenuGrid {
    /** Pitch of the rows. */
    const val ROW = 40f

    /** Rows start to fade this far from the chosen row. */
    const val FADE_FROM = 56f

    /** Rows are gone this far away, well before the black band along the screen's edges. */
    const val FADE_TO = 226f

    /**
     * The light behind the chosen row: a soft, low band (Gaussian σ along the row and across it,
     * dp) that lights this row only, and its brightest point (≈ L* 10).
     */
    const val GLOW_SIGMA_X = 120f
    const val GLOW_SIGMA_Y = 12f
    const val GLOW_PEAK = 0.011f

    /** A row's label and value: size (sp) and weight. */
    const val LABEL_SP = 21f
    const val LABEL_WEIGHT = 480
    const val VALUE_SP = 17f
    const val VALUE_WEIGHT = 440

    /** A value on a scale has a slim track before it (dp): its length, and the room after it. */
    const val SLIDER_WIDTH = 72f
    const val SLIDER_GAP = 14f

    /** The least room between a row's label and its value (dp), so every row fits the column (LanguageTest checks it). */
    const val LABEL_GAP = 16f

    /** Opacity of a row at [distance] dp from the chosen row. */
    fun fade(distance: Float): Float = 1f - smoothstep(FADE_FROM, FADE_TO, abs(distance))
}

/**
 * The settings menu, fading in over whatever is on screen and out again, on its own background
 * (black or light). The screen beneath stays composed, so it is back the moment the menu
 * closes. While the queue display just chosen is [pending], the Queue row's value breathes
 * ([rememberBreath]).
 */
@Composable
fun MenuOverlay(menu: MenuState?, appearance: Appearance, modifier: Modifier = Modifier, pending: Boolean = false) {
    // The last menu stays while it fades out.
    val last = remember { arrayOfNulls<MenuState>(1) }
    if (menu != null) last[0] = menu
    val shown = remember { Animatable(0f) }
    LaunchedEffect(menu != null) {
        shown.animateTo(if (menu != null) 1f else 0f, tween(if (menu != null) 450 else 600, easing = Motion.Dimmer))
    }
    if (menu == null && shown.value <= 0f) return
    val state = menu ?: last[0] ?: return
    // Faded draw by draw, not through a layer of the screen's size: the menu's background
    // reaches beyond the screen's edges, and a layer would cut it off there while it fades.
    MenuScreen(
        state,
        modifier.graphicsLayer {
            alpha = shown.value
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
        appearance,
        pending,
    )
}

/** The menu's colors: near-neutral white on black, or near-black on the light background. */
private class MenuColors(
    val wall: Color,
    /** The light behind the chosen row, added to the background. */
    val band: Color,
    val heading: Color,
    val description: Color,
    val hint: Color,
    val label: Color,
    val labelFocus: Color,
    val value: Color,
    val valueFocus: Color,
    /** A row that cannot work yet: quieter, and chosen no brighter than another row at rest. */
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
                // On the light background the chosen row lies in a band of slightly whiter light.
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
 * The menu on the stage's grid, in two levels: the groups, then the settings of the group
 * opened. The heading and the chosen row's description stand where the artwork would be, and
 * the rows stand in the text column. The chosen row stays in the middle of the screen in a band
 * of light, and the list glides through it and fades with distance. Going into a group or back
 * out, the level left fades out, then the new one fades in on its chosen row.
 */
@Composable
fun MenuScreen(menu: MenuState, modifier: Modifier = Modifier, appearance: Appearance = Appearance.DARK, pending: Boolean = false) {
    val colors = remember(appearance) { MenuColors.of(appearance) }
    // The level left keeps showing as it was while it fades.
    val levels = remember { HashMap<MenuSection?, MenuState>() }
    levels[menu.section] = menu

    BoxWithConstraints(modifier.fillMaxSize()) {
        BaseWall(colors.wall)
        val density = LocalDensity.current.density
        val screen = ScreenSpec((maxWidth.value * density).roundToInt(), (maxHeight.value * density).roundToInt(), density)
        val layout = StageGrid(screen)
        val focusY = maxHeight.value / 2f

        val glowCx = layout.columnLeft + layout.columnWidth / 2f
        val map = rememberOffMain(screen, colors) {
            val px = glowMap(glowCx, focusY, screen.widthDp, screen.heightDp, ImageOps.ditherFor(colors.band.toArgb()))
            imageBitmapOf(px, GLOW_MAP_W, GLOW_MAP_H)
        }
        if (map != null) Canvas(Modifier.fillMaxSize()) {
            // The band's light adds to the background, dark or light.
            drawImage(
                map,
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
            MenuLevel(levels[section] ?: menu, layout, focusY, colors, pending)
        }
    }
}

/** One level of the menu: its heading, the chosen row's description and the hint on the left, its rows on the right. */
@Composable
private fun MenuLevel(menu: MenuState, layout: StageGrid, focusY: Float, colors: MenuColors, pending: Boolean) {
    val cursor = menu.cursor.coerceIn(0, menu.rows.size - 1)
    val chosen = menu.rows[cursor]
    val t = texts()
    // The list moves so that the chosen row comes to rest in the light; a held key runs it on evenly.
    val offset = animateGlide(focusY - (cursor * MenuGrid.ROW + MenuGrid.ROW / 2f), GLIDE_MS)

    // Left: the heading's cap height on the artwork's top edge, the description level with the
    // chosen row.
    val faces = faces()
    val headingStyle = faces.titleType(HEADING_SP, 420, colors.heading)
    val headingCap = with(LocalDensity.current) { HEADING_SP.sp.toDp().value } * Fonts.CAP_HEIGHT
    BaselineAt(x = layout.margin, baseline = layout.artTop + headingCap, width = layout.art) {
        BasicText(menu.section?.let { t[it.title] } ?: t[Strings.menuHeading], style = headingStyle)
    }
    // While a key is held the list runs on under the light: the description goes, and comes
    // back with the row the key is let go on.
    Crossfade(
        targetState = chosen.description.takeUnless { menu.held },
        animationSpec = tween(320, easing = Motion.Dimmer),
        label = "menu-description",
    ) { text ->
        if (text != null) BaselineAt(x = layout.margin, baseline = focusY + LABEL_BASELINE_FROM_CENTER, width = layout.art - 24f) {
            BalancedText(AnnotatedString(text), faces.textType(18f, 420, colors.description, lineHeight = 26f).merge(TextStyle(lineBreak = sentenceLineBreak)))
        }
    }
    BaselineAt(x = layout.margin, baseline = layout.artTop + layout.art, width = layout.art) {
        BasicText(t[if (menu.section == null) Strings.menuHintGroups else Strings.menuHint], style = faces.textType(15f, 500, colors.hint, tracking = 0.01f))
    }

    // Right: the rows in the text column.
    val labelStyle = faces.textType(MenuGrid.LABEL_SP, MenuGrid.LABEL_WEIGHT)
    val valueStyle = faces.textType(MenuGrid.VALUE_SP, MenuGrid.VALUE_WEIGHT)
    menu.rows.forEachIndexed { i, row ->
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
            MenuItem(row, i == cursor, labelStyle, valueStyle, colors, pending)
        }
    }
}

@Composable
private fun MenuItem(row: MenuRow, focused: Boolean, labelStyle: TextStyle, valueStyle: TextStyle, colors: MenuColors, pending: Boolean) {
    // A row that cannot work yet stays quiet; chosen, it is only as bright as another row at rest.
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
    // While the queue display just chosen is pending, the chosen Queue row's value breathes
    // toward the ink of a value at rest. A row at rest has no quieter ink left that still reads
    // well, so it doesn't breathe, and a breath under way when the cursor leaves glides back.
    val breath = if (row.entry == MenuEntry.QUEUE) rememberBreath(pending && focused) else null
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
            BasicText(value, style = valueStyle, color = { breathed(valueColor, colors.value, breath?.value ?: 0f) }, maxLines = 1)
        }
    }
}

/** Places [content] so that its first baseline lies at [baseline] (dp from the top). */
@Composable
private fun BaselineAt(x: Float, baseline: Float, width: Float, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val p = measurables.first().measure(Constraints(maxWidth = (width * density).roundToInt()))
        layout(constraints.maxWidth, constraints.maxHeight) {
            p.place((x * density).roundToInt(), (baseline * density).roundToInt() - p.firstBaseline())
        }
    }
}

/**
 * The slider before a value on a scale: its height (dp), how strongly its track shows, and how
 * far its lower edge sits above the value's baseline (dp, its middle on the figures' middle).
 */
private const val SLIDER_HEIGHT = 4f
private const val SLIDER_TRACK = 0.28f
private const val SLIDER_LIFT = 4f

/** The heading's size (sp). */
private const val HEADING_SP = 36f

/** The list's glide by one row from rest (ms). */
private const val GLIDE_MS = 340

/** A group opening, or the menu going back to the groups: the level left fades, then the new one comes (ms). */
private const val LEVEL_OUT_MS = 140
private const val LEVEL_IN_MS = 220

/** From a row's center to its label's baseline (21 sp in a 40 dp row). */
private const val LABEL_BASELINE_FROM_CENTER = 7f

private const val GLOW_MAP_W = 480
private const val GLOW_MAP_H = 270

/**
 * The light behind the chosen row: a soft, low band around ([cx], [cy]) dp that lights this row
 * and not its neighbors (grayscale, dithered, tinted when drawn). It never moves; the list moves
 * through it. Like every light, it is gone before the band along the screen's edges.
 */
private fun glowMap(cx: Float, cy: Float, widthDp: Float, heightDp: Float, ditherLsb: Float): IntArray {
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
