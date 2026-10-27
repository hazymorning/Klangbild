package io.github.hazymorning.klangbild.ui

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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.SetupReason
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.SceneBuilder
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.ui.stage.CoverLayer
import io.github.hazymorning.klangbild.ui.stage.BaseWall
import io.github.hazymorning.klangbild.ui.stage.WallLayer
import io.github.hazymorning.klangbild.ui.stage.asColor
import io.github.hazymorning.klangbild.ui.text.BalancedText
import io.github.hazymorning.klangbild.ui.text.lineBalancer
import io.github.hazymorning.klangbild.ui.text.sentenceLineBreak
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.faces
import io.github.hazymorning.klangbild.ui.theme.firstBaseline
import io.github.hazymorning.klangbild.ui.theme.textType
import io.github.hazymorning.klangbild.ui.theme.titleType
import kotlin.math.roundToInt

/** What the setup screen shows besides its text. */
object SetupArt {
    /** The setup screen's scene: the QR card ([QrCard]) as its cover, in the light a cover has. */
    fun scene(info: SetupInfo?, screen: ScreenSpec, appearance: Appearance = Appearance.DARK): ArtworkScene {
        val layout = StageGrid(screen)
        // Rounded as the scene sizes its cover, so the card is never resampled.
        val px = (layout.art * screen.density).roundToInt()
        val card = QrCard.of(info?.qr, px, appearance)
        return SceneBuilder.build("setup|${info?.url}|${info?.code}", card, null, null, screen, appearance)
    }

    /** "http://192.168.1.23:8765" → "192.168.1.23:8765" (what people actually type). */
    fun shortUrl(url: String): String = url.removePrefix("http://").removePrefix("https://").trimEnd('/')
}

/**
 * Setup: the QR code stands where the artwork would, in the same light, and beside it two
 * sentences say what to do. The six-digit code, which the setup page asks for when its address
 * is typed by hand, is set large. Code and text stand as one group in the middle of the screen:
 * as much room left of the code as right of the longest line.
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
    val faces = faces()
    val label = faces.textType(15f, 500, p.inkQuiet.asColor(), tracking = 0.01f)
    val body = faces.textType(19f, 420, p.inkMuted.asColor(), lineHeight = 27f).merge(TextStyle(lineBreak = sentenceLineBreak))
    val t = texts()
    // The setup strings carry no-break spaces, so a short word that opens a sentence never ends
    // a line and articles and adjectives stay with their noun.
    val lead = t[
        when (reason) {
            SetupReason.FIRST_RUN -> Strings.setupFirstRun
            SetupReason.AUTH_FAILED -> Strings.setupRejected
            SetupReason.REQUESTED -> Strings.setupChange
        },
    ]
    val text = t[if (reason == SetupReason.AUTH_FAILED) Strings.setupTextRejected else Strings.setupText]
    val hint = if (reason == SetupReason.REQUESTED) t[Strings.setupBack] else null
    val address = info?.url?.let { url ->
        buildAnnotatedString {
            append(t[Strings.setupInBrowser])
            append(" ")
            // A little air between the figures, so the dots stay visible from the sofa.
            withStyle(SpanStyle(letterSpacing = 0.03.em)) { append(SetupArt.shortUrl(url)) }
        }
    } ?: AnnotatedString(t[Strings.setupNoNetwork])
    // Until the code is drawn its line stays empty but keeps its height.
    val code = info?.code?.chunked(3)?.joinToString(" ") ?: "\u00A0"
    val name = faces.titleType(NAME_SP, 420, p.ink.asColor(), tracking = -0.005f)
    val leadStyle = faces.textType(21f, 520, p.inkMuted.asColor(), lineHeight = 27f).merge(TextStyle(lineBreak = sentenceLineBreak))
    val codeStyle = faces.titleType(44f, 400, p.ink.asColor(), tracking = 0.01f)
    val addressStyle = body.copy(color = p.inkQuiet.asColor())
    // How wide the text really is: its longest line, as it breaks in the column (the lead, the
    // text and the address on balanced lines).
    val measurer = rememberTextMeasurer()
    val widest = with(LocalDensity.current) {
        remember(reason, info, layout, this, t, faces) {
            val column = layout.columnWidth.dp.roundToPx()
            val balancer = lineBalancer(measurer)
            val plain = listOf(AnnotatedString(APP_NAME) to name, AnnotatedString(code) to codeStyle)
                .map { (s, style) -> measurer.measure(s, style, constraints = Constraints(maxWidth = column)) }
            val balanced = listOf(AnnotatedString(lead) to leadStyle, AnnotatedString(text) to body, address to addressStyle)
                .map { (s, style) -> balancer.layout(s, style, column) }
            (plain + balanced)
                .maxOf { r -> (0 until r.lineCount).maxOf { r.getLineRight(it) - r.getLineLeft(it) } }
                .toDp().value
        }
    }
    // The group moves right by half of what the column leaves empty.
    val shift = ((layout.columnWidth - widest) / 2f).coerceAtLeast(0f)
    Box(modifier.fillMaxSize()) {
        BaseWall(p.wall.asColor())
        // By whole pixels, so the card's modules stay sharp.
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = shift.dp.roundToPx().toFloat() }) {
            WallLayer(scene)
            CoverLayer(scene)
            // The block hangs from the card's top edge: the name's cap height on it.
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Column(Modifier.width(layout.columnWidth.dp)) {
                        BasicText(APP_NAME, style = name)
                        Spacer(Modifier.height(4.dp))
                        BalancedText(AnnotatedString(lead), leadStyle)
                        Spacer(Modifier.height(20.dp))
                        BalancedText(AnnotatedString(text), body)
                        Spacer(Modifier.height(30.dp))
                        BasicText(t[Strings.setupCode], style = label)
                        Spacer(Modifier.height(2.dp))
                        BasicText(code, style = codeStyle)
                        Spacer(Modifier.height(8.dp))
                        BalancedText(address, addressStyle)
                    }
                },
            ) { measurables, constraints ->
                val block = measurables.first().measure(Constraints())
                val baseline = (layout.artTop * density).roundToInt() + (Fonts.CAP_HEIGHT * NAME_SP.sp.toPx()).roundToInt()
                layout(constraints.maxWidth, constraints.maxHeight) {
                    block.place((layout.columnLeft * density).roundToInt(), baseline - block.firstBaseline())
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

private const val APP_NAME = "Klangbild"

/** The app's name over the text (sp): its cap height stands on the card's top edge. */
private const val NAME_SP = 44f
