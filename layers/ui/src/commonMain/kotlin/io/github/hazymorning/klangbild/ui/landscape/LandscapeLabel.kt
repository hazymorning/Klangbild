package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.TrackText
import io.github.hazymorning.klangbild.landscape.LabelSide
import io.github.hazymorning.klangbild.landscape.PaperMotif
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.ImageOps
import io.github.hazymorning.klangbild.render.imageBitmapOf
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.rememberFrames
import io.github.hazymorning.klangbild.ui.rememberOffMain
import io.github.hazymorning.klangbild.ui.stage.fitWords
import io.github.hazymorning.klangbild.ui.texts
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.faces
import io.github.hazymorning.klangbild.ui.theme.textType
import io.github.hazymorning.klangbild.ui.theme.titleType
import kotlin.math.roundToInt

/** What the label on the near bank says: what plays (or played last), its cover's scene once it is ready, and whether it plays. */
class LabelText(val track: TrackInfo, val scene: ArtworkScene?, val playing: Boolean, val positionNow: () -> Long?) {
    val key: String get() = track.key
}

/**
 * The music's label in the landscape, lying on the near bank like a small print with its
 * caption: the cover (56 dp, its corners rounded a little), beside it the title in Jost, the
 * artist in DM Sans and a slim bar for how far the track has played (dimmed while paused). What
 * stands beside the cover is one block, exactly centered on it, measured on what is seen. It runs
 * from the title's cap height to the bar's lower edge (filling the cover's height) or, where there
 * is no bar (a station has no length), to the last baseline. The inks are the bank's
 * ([io.github.hazymorning.klangbild.landscape.PaperLight.labelInks]): 4.5:1 in every light. A new
 * track's label cross-fades in place.
 */
@Composable
fun LandscapeLabel(text: LabelText, side: LabelSide, inks: () -> IntArray, modifier: Modifier = Modifier, coverLight: () -> Float = { 1f }) {
    // Only another track cross-fades; a pause or a new cover changes the label in place. The one
    // shown always reads the latest, and the one leaving keeps what it said.
    val latest by rememberUpdatedState(text)
    val said = remember { HashMap<String, LabelText>() }
    val key = text.key
    if (said.size > 4) said.keys.retainAll(setOf(key))
    said[key] = text
    Crossfade(
        targetState = key,
        modifier = modifier.fillMaxSize(),
        animationSpec = tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer),
        label = "landscape-label",
    ) { k ->
        val t = if (k == latest.key) latest else said[k]
        if (t != null) MusicLabel(t, side, inks, coverLight)
    }
}

/** The label's box on the screen (dp): left edge, top, the cover's side, the text column's left edge and width. */
private class LabelBox(side: LabelSide) {
    val left = if (side == LabelSide.RIGHT) PaperMotif.LABEL_RIGHT_X else PaperMotif.LABEL_LEFT_X
    val top = PaperMotif.LABEL_BOTTOM - PaperMotif.LABEL_HEIGHT
    val cover = PaperMotif.LABEL_HEIGHT
    val textLeft = left + cover + GAP
    val textWidth = PaperMotif.LABEL_MAX_WIDTH - cover - GAP
}

@Composable
private fun MusicLabel(m: LabelText, side: LabelSide, inks: () -> IntArray, coverLight: () -> Float) {
    val box = remember(side) { LabelBox(side) }
    val density = LocalDensity.current.density
    val others = texts()[Strings.artistsOthers]
    val text = remember(m.track, others) { TrackText.from(m.track, others) }
    val faces = faces()
    val titleStyle = remember(faces) { faces.titleType(TITLE_SP, 400) }
    val artistStyle = remember(faces) { faces.textType(ARTIST_SP, 460) }
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val widthPx = (box.textWidth * density).roundToInt()
    val title = remember(text, widthPx, titleStyle) { fitWords(measurer, text.title, titleStyle, widthPx) }
    val artist = remember(text, widthPx, artistStyle) {
        val options = text.artistOptions()
        options.firstOrNull { measurer.measure(AnnotatedString(it), artistStyle, maxLines = 1, softWrap = false).size.width <= widthPx }
            ?: options.lastOrNull()?.let { fitWords(measurer, it, artistStyle, widthPx) }
    }
    val sizePx = (box.cover * density).roundToInt()
    val scene = m.scene
    // By the print itself: a station's song keeps its key when its own picture replaces the station's.
    val cover: ImageBitmap? = rememberOffMain(scene?.cover, sizePx) { scene?.let { miniCover(it.cover, sizePx) } }
    val fill by animateFloatAsState(if (m.playing) 1f else 0.55f, tween(Motion.PAUSE_MS, easing = Motion.Dimmer), label = "label-bar")
    val duration = m.track.durationMs
    val bar = duration != null && duration > 0L
    // The landscape's tick redraws the bar; none without a bar.
    val tick = rememberFrames(fast = m.playing, running = bar, fastMs = LANDSCAPE_TICK_MS)
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Canvas(Modifier) {
                val img = cover ?: return@Canvas
                val r = CORNER_DP * density
                val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
                // At night the print dims with the picture around it.
                val k = coverLight().coerceIn(0f, 1f)
                clipPath(clip) { drawImage(img, colorFilter = if (k < 0.995f) dimmer(k) else null) }
            }
            BasicText(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = { Color(inks()[0]) })
            if (artist != null) BasicText(artist, style = artistStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = { Color(inks()[1]) }) else Box(Modifier)
            Canvas(Modifier.graphicsLayer()) {
                val length = duration?.takeIf { it > 0L } ?: return@Canvas
                tick.value // redraw on every refresh
                val ink = Color(inks()[1])
                val f = ((m.positionNow() ?: 0L).toFloat() / length).coerceIn(0f, 1f)
                val cr = CornerRadius(size.height / 2f)
                drawRoundRect(ink.copy(alpha = 0.16f), cornerRadius = cr)
                if (f > 0f) drawRoundRect(ink.copy(alpha = fill), Offset.Zero, Size((size.width * f).coerceAtLeast(size.height), size.height), cr)
            }
        },
    ) { measurables, constraints ->
        fun px(dp: Float) = (dp * density).roundToInt()
        val coverP = measurables[0].measure(Constraints.fixed(sizePx, sizePx))
        val textC = Constraints(maxWidth = widthPx)
        val titleP = measurables[1].measure(textC)
        val artistP = measurables[2].measure(textC)
        val barP = measurables[3].measure(Constraints.fixed(px(BAR_WIDTH_DP), px(BAR_DP)))
        fun Placeable.first(): Int = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: height
        val left = px(box.left)
        val top = px(box.top)
        val textLeft = px(box.textLeft)
        // One block beside the cover, its middle on the cover's middle: from the title's cap
        // height to the last baseline, and on to the bar's lower edge when there is a bar.
        val cap = titleStyle.fontSize.toPx() * Fonts.CAP_HEIGHT
        val lines = cap + if (artistP.height > 0) px(ARTIST_STEP_DP) else 0
        val block = if (bar) lines + px(BAR_FROM_BASELINE_DP) + barP.height else lines
        val titleBaseline = (top + sizePx / 2f - block / 2f + cap).roundToInt()
        val lastBaseline = titleBaseline + if (artistP.height > 0) px(ARTIST_STEP_DP) else 0
        layout(constraints.maxWidth, constraints.maxHeight) {
            coverP.place(left, top)
            titleP.place(textLeft, titleBaseline - titleP.first())
            if (artistP.height > 0) artistP.place(textLeft, lastBaseline - artistP.first())
            if (bar) barP.place(textLeft, lastBaseline + px(BAR_FROM_BASELINE_DP))
        }
    }
}

/** Darker by [k] (0..1), colors kept. */
private fun dimmer(k: Float) = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, 1f, 0f)),
)

/** The cover at exactly [size] px (Lanczos, once per track): the GPU never scales it. */
private fun miniCover(src: ImageBitmap, size: Int): ImageBitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.readPixels(px)
    val out = ImageOps.resize(px, w, h, size, size)
    return imageBitmapOf(out, size, size)
}

private const val GAP = 18f
private const val TITLE_SP = 24f
private const val ARTIST_SP = 17f
private const val ARTIST_STEP_DP = 23f
private const val BAR_DP = 3f
private const val BAR_WIDTH_DP = 190f

/**
 * From the last baseline to the bar's top edge. With a title, an artist and the bar, the block is
 * exactly as tall as the cover (16.8 + 23 + 13.2 + 3 = 56 dp).
 */
private const val BAR_FROM_BASELINE_DP = 13.2f
private const val CORNER_DP = 4f
