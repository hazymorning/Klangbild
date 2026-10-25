package io.github.hazymorning.klangbild.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.hazymorning.klangbild.color.Appearance
import kotlin.math.roundToInt

/**
 * One card of the cover flow as the GPU draws it: the print with its softly rounded corners
 * and, below it, its mirror image fading into the floor. It is one bitmap, so a card is a single
 * textured quad. A transparent border of [PAD] pixels lets the texture filter smooth the
 * card's edges however it is turned.
 *
 * Bitmaps are drawn without mipmaps, so a card turned far to the side would shimmer while
 * it moves. [half] is the same card at half the resolution, drawn instead once a card is
 * squeezed to less than about two thirds of its width, the two mixed while it turns between.
 */
class FlowCard(
    val full: ImageBitmap,
    val half: ImageBitmap,
    /** The print's mean lightness (Oklab L), so a bright card beside a dark one can step back. */
    val lightness: Float,
    /** The wall the card was made for: on the dark one it has its mirror image, and it is lit as there. */
    val appearance: Appearance,
) {
    /** Width and height of the card itself in [full] (pixels), without the border. */
    val sidePx: Int get() = full.width - 2 * PAD

    /** Height of card, gap and reflection in [full] (pixels), without the border. */
    val heightPx: Int get() = full.height - 2 * PAD

    companion object {
        const val PAD = 2
    }
}

object FlowCards {
    /** Opacity of the mirror image where it meets the card. Only on the dark gloss: paper is matte. */
    const val REFLECTION = 0.3f

    /**
     * The card for [cover] (the print at its exact size, [cover] square), with corners of
     * [cornerPx] and [geometry]'s gap and reflection height; [lightness] is the print's mean
     * lightness. On paper there is no mirror image. There is never a frame, a line or a shadow around the
     * card: only the print.
     */
    fun build(
        cover: ImageBitmap,
        cornerPx: Float,
        geometry: FlowGeometry,
        density: Float,
        appearance: Appearance,
        lightness: Float = 0.5f,
    ): FlowCard {
        val side = cover.width
        val gap = (geometry.gap * density).roundToInt()
        val reflection = (geometry.reflection * density).roundToInt()
        val pad = FlowCard.PAD
        val out = ImageBitmap(side + 2 * pad, side + gap + reflection + 2 * pad)
        val c = Canvas(out)
        val print = plainPaint().apply { shader = ImageShader(cover, TileMode.Clamp, TileMode.Clamp) }
        fun card(top: Float, flipped: Boolean) {
            c.save()
            c.translate(pad.toFloat(), if (flipped) top + side else top)
            if (flipped) c.scale(1f, -1f) // mirrored about the card's bottom edge
            c.drawRoundRect(0f, 0f, side.toFloat(), side.toFloat(), cornerPx, cornerPx, print)
            c.restore()
        }
        card(pad.toFloat(), flipped = false)
        if (appearance == Appearance.DARK) {
            // The mirror image: the card upside down below a hairline gap, fading out on the way down.
            val top = (pad + side + gap).toFloat()
            c.saveLayer(Rect(0f, top, out.width.toFloat(), top + reflection), plainPaint())
            card(top, flipped = true)
            val fade = plainPaint().apply {
                blendMode = BlendMode.DstIn
                shader = LinearGradientShader(
                    Offset(0f, top),
                    Offset(0f, top + reflection),
                    listOf(alpha(REFLECTION), alpha(REFLECTION * 0.35f), alpha(REFLECTION * 0.08f), Color.Transparent),
                    listOf(0f, 0.35f, 0.7f, 1f),
                    TileMode.Clamp,
                )
            }
            c.drawRect(0f, top, out.width.toFloat(), top + reflection, fade)
            c.restore()
        }
        out.prepareToDraw()
        val half = halved(out)
        half.prepareToDraw()
        return FlowCard(out, half, lightness, appearance)
    }

    /** Black at [a]. */
    private fun alpha(a: Float) = Color((a.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)

    /** [image] at half its size, filtered. */
    private fun halved(image: ImageBitmap): ImageBitmap {
        val half = ImageBitmap(image.width / 2, image.height / 2)
        val c = Canvas(half)
        c.scale(half.width / image.width.toFloat(), half.height / image.height.toFloat())
        c.drawImageRect(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset.Zero, IntSize(image.width, image.height), plainPaint())
        return half
    }
}
