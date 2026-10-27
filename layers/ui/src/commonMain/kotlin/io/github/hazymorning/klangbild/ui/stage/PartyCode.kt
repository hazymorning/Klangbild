package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.QrCode
import io.github.hazymorning.klangbild.ui.LocalStill
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.SetupArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A party's code as the stage shows it: its pictures, one for each wall on stage, and how far
 * the code has come up (0 to 1).
 */
class PartyCode<T : Any>(private val cards: Map<Appearance, T>, val shown: () -> Float) {
    /** The picture for [wall], or the other wall's until that one is made. */
    fun on(wall: Appearance): T? = cards[wall] ?: cards.values.firstOrNull()
}

/**
 * The code of the party playing here ([url], null: none shows). [make] draws it off the main
 * thread for each wall on stage ([walls]; a cross-fade to the other wall has the code on both
 * sides) at a [size]. It comes up once its picture is there and goes in a quiet cross-fade;
 * until the picture of a new link is made, the last one stays.
 */
@Composable
fun <T : Any> rememberPartyCode(url: String?, walls: Set<Appearance>, size: Any, make: (String, Appearance) -> T?): PartyCode<T> {
    if (LocalStill.current) {
        // A still has the code at once.
        val cards = remember(url, walls, size) {
            if (url == null) emptyMap() else walls.mapNotNull { w -> make(url, w)?.let { w to it } }.toMap()
        }
        return PartyCode(cards) { if (cards.isEmpty()) 0f else 1f }
    }
    var made by remember(size) { mutableStateOf(emptyMap<Appearance, Pair<String, T>>()) }
    LaunchedEffect(url, walls, size) {
        if (url == null) {
            // Let go once it has faded out.
            delay(Motion.SOFT_SWAP_MS + 200L)
            made = emptyMap()
            return@LaunchedEffect
        }
        for (wall in walls) {
            if (made[wall]?.first == url) continue
            val card = withContext(Dispatchers.Default) { make(url, wall) } ?: continue
            made = made + (wall to (url to card))
        }
        made = made.filterKeys { it in walls }
    }
    val cards = made.mapValues { it.value.second }
    val shown by animateFloatAsState(
        if (url != null && cards.isNotEmpty()) 1f else 0f,
        tween(Motion.SOFT_SWAP_MS, easing = Motion.Dimmer),
        label = "party-code",
    )
    return PartyCode(cards) { shown }
}

/**
 * The QR code of [url] as a printed card [sizePx] square, like the setup's: dark modules on whole
 * pixels on ivory (a whiter paper on the light wall). Null for a link no QR code can hold.
 */
fun partyCard(url: String, sizePx: Int, wall: Appearance): ImageBitmap? {
    val matrix = try {
        QrCode.matrix(url)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return SetupArt.card(matrix, sizePx, wall)
}

/**
 * The party's code ([card]) where the cover of [scene] stands, drawn 1:1 on whole pixels with the
 * cover's corners and its light, dimmed with it in a pause ([dim]): guests scan it from the
 * couch to add songs. The song's lines stay beside it; the wall's light and the lights' colors
 * stay the song's.
 */
@Composable
fun PartyCodeLayer(card: ImageBitmap?, scene: ArtworkScene, alpha: () -> Float, dim: () -> Float) {
    if (card == null) return
    val brush = remember(card) { ShaderBrush(ImageShader(card, TileMode.Clamp, TileMode.Clamp)) }
    val filters = remember { CoverFilters() }
    val rect = scene.layout.artRect
    Canvas(Modifier.fillMaxSize()) {
        // The fade goes with the draw call: no layer of the screen's size.
        val a = alpha().coerceIn(0f, 1f)
        if (a <= 0f) return@Canvas
        val filter = filters.at(dim(), scene.appearance)
        val corner = CornerRadius(scene.corner * density)
        // The card's pixels start at its corner, which stands on a whole pixel.
        translate((rect.left * density).roundToInt().toFloat(), (rect.top * density).roundToInt().toFloat()) {
            drawRoundRect(brush, Offset.Zero, Size(card.width.toFloat(), card.height.toFloat()), corner, alpha = a, colorFilter = filter)
        }
    }
}
