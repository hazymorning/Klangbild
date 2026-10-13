package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.render.SceneBuilder
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.text.texts
import io.github.hazymorning.klangbild.ui.stage.ArtworkLayer
import io.github.hazymorning.klangbild.ui.stage.BaseWall
import io.github.hazymorning.klangbild.ui.stage.WallLayer
import io.github.hazymorning.klangbild.ui.stage.asColor
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.textType
import io.github.hazymorning.klangbild.ui.theme.titleType
import kotlin.math.max

object SetupArt {
    /**
     * The QR code as a printed card at the exact on-screen pixel size of the cover slot:
     * ivory paper (a whiter one on the light wall), dark modules on whole pixels, generous
     * quiet zone. Becomes the "cover" of the setup scene, so it hangs on the wall in the same
     * light as an album.
     */
    fun card(qr: Array<BooleanArray>?, sizePx: Int, appearance: Appearance = Appearance.DARK): Bitmap {
        val bmp = createBitmap(sizePx, sizePx)
        val c = Canvas(bmp)
        c.drawColor(if (appearance == Appearance.DARK) 0xFFEAE2D4.toInt() else 0xFFF7F4EE.toInt())
        if (qr != null) {
            val n = qr.size
            val module = max(1, (sizePx * 0.80f / n).toInt())
            val offset = (sizePx - module * n) / 2
            val p = Paint().apply { color = 0xFF1C1814.toInt(); isAntiAlias = false }
            for (y in 0 until n) for (x in 0 until n) {
                if (qr[y][x]) {
                    val l = offset + x * module
                    val t = offset + y * module
                    c.drawRect(l.toFloat(), t.toFloat(), (l + module).toFloat(), (t + module).toFloat(), p)
                }
            }
        }
        return bmp
    }

    fun scene(info: SetupInfo?, screen: ScreenSpec, appearance: Appearance = Appearance.DARK): ArtworkScene {
        // The code hangs where the artwork would.
        val layout = StageGrid(screen)
        val px = (layout.art * screen.density).toInt()
        val card = card(info?.qr, px, appearance)
        return SceneBuilder.build("setup|${info?.url}|${info?.code}", card, "setup", screen, appearance)
    }

    /** "http://192.168.1.23:8765" → "192.168.1.23:8765" (what people actually type). */
    fun shortUrl(url: String): String = url.removePrefix("http://").removePrefix("https://").trimEnd('/')
}

/**
 * Setup: the QR code hangs on the wall where the artwork would, in the same light; beside
 * it two sentences say what to do. The six-digit code is set large for typing the address by
 * hand. Code and text stand as one group in the middle of the screen: as much room left of
 * the code as right of the longest line.
 */
@Composable
fun SetupScreen(
    reason: SetupReason,
    info: SetupInfo?,
    scene: ArtworkScene,
    modifier: Modifier = Modifier,
) {
    val p = scene.palette
    val layout = scene.layout
    val balanced = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)
    val label = textType(15f, 500, p.inkQuiet.asColor(), tracking = 0.01f)
    val body = textType(19f, 420, p.inkMuted.asColor(), lineHeight = 27f).merge(TextStyle(lineBreak = balanced))
    val t = texts()
    val lead = t[
        when (reason) {
            SetupReason.FIRST_RUN -> R.string.setup_first_run
            SetupReason.AUTH_FAILED -> R.string.setup_rejected
            SetupReason.REQUESTED -> R.string.setup_change
        },
    ]
    // A short word that opens a sentence never ends a line, and articles and adjectives stay
    // with their noun: the texts carry no-break spaces for that.
    val text = t[if (reason == SetupReason.AUTH_FAILED) R.string.setup_text_rejected else R.string.setup_text]
    val hint = if (reason == SetupReason.REQUESTED) t[R.string.setup_back] else null
    val address = info?.url?.let { url ->
        buildAnnotatedString {
            append(t[R.string.setup_in_browser])
            append(" ")
            // A little air between the figures, so the dots stay visible from the sofa.
            withStyle(SpanStyle(letterSpacing = 0.03.em)) { append(SetupArt.shortUrl(url)) }
        }
    } ?: AnnotatedString(t[R.string.setup_no_network])
    // Until the code is drawn its line stays empty but keeps its height.
    val code = info?.code?.chunked(3)?.joinToString(" ") ?: "\u00A0"
    val name = titleType(44f, 420, p.ink.asColor(), tracking = -0.005f)
    val leadStyle = textType(21f, 520, p.inkMuted.asColor(), lineHeight = 27f).merge(TextStyle(lineBreak = balanced))
    val codeStyle = titleType(44f, 400, p.ink.asColor(), tracking = 0.01f)
    val addressStyle = body.copy(color = p.inkQuiet.asColor())
    // How wide the text really is: its longest line, as it breaks in the column.
    val measurer = rememberTextMeasurer()
    val local = LocalDensity.current
    val widest = remember(reason, info, layout, local, t) {
        val column = Constraints(maxWidth = with(local) { layout.columnWidth.dp.roundToPx() })
        listOf(AnnotatedString("Klangbild") to name, AnnotatedString(lead) to leadStyle, AnnotatedString(text) to body, AnnotatedString(code) to codeStyle, address to addressStyle)
            .maxOf { (t, style) ->
                val r = measurer.measure(t, style, constraints = column)
                (0 until r.lineCount).maxOf { r.getLineRight(it) - r.getLineLeft(it) }
            }
            .let { with(local) { it.toDp().value } }
    }
    // The group moves right by half of what the column leaves empty.
    val shift = ((layout.columnWidth - widest) / 2f).coerceAtLeast(0f)
    Box(modifier.fillMaxSize()) {
        BaseWall(p.wall.asColor())
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = shift.dp.toPx() }) {
            WallLayer(scene)
            ArtworkLayer(scene)
            // The block hangs from the card's top edge: the name's cap height on it.
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Column(Modifier.width(layout.columnWidth.dp)) {
                        BasicText("Klangbild", style = name)
                        Spacer(Modifier.height(4.dp))
                        BasicText(lead, style = leadStyle)
                        Spacer(Modifier.height(20.dp))
                        BasicText(text, style = body)
                        Spacer(Modifier.height(30.dp))
                        BasicText(t[R.string.setup_code], style = label)
                        Spacer(Modifier.height(2.dp))
                        BasicText(code, style = codeStyle)
                        Spacer(Modifier.height(8.dp))
                        BasicText(address, style = addressStyle)
                    }
                },
            ) { measurables, constraints ->
                val block = measurables.first().measure(Constraints())
                val first = block[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
                val baseline = (layout.artTop * density).roundToInt() + (44f * fontScale * density * Fonts.CAP_HEIGHT).roundToInt()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    block.place((layout.columnLeft * density).roundToInt(), baseline - first)
                }
            }
            if (hint != null) {
                BasicText(
                    hint,
                    style = label,
                    modifier = Modifier.offset(layout.columnLeft.dp, (layout.artTop + layout.art - 18f).dp),
                )
            }
        }
    }
}
