package io.github.hazymorning.klangbild.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import io.github.hazymorning.klangbild.color.Appearance
import kotlin.math.roundToInt

/**
 * One card of the cover flow as the GPU draws it: the print with its softly rounded corners
 * and, below it, its mirror image fading into the floor. It is one bitmap, so a card is a single
 * textured quad. A transparent border of [PAD] pixels lets the texture filter smooth the
 * card's edges however it is turned.
 *
 * Android draws bitmaps without mipmaps, so a card turned far to the side would shimmer while
 * it moves. [half] is the same card at half the resolution, drawn instead once a card is
 * squeezed to less than about two thirds of its width, the two mixed while it turns between.
 */
class FlowCard(
    val full: Bitmap,
    val half: Bitmap,
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
     * [cornerPx] and [geometry]'s gap and reflection height. [round] is set for a generated
     * plate (already transparent outside its disc); [lightness] is the print's mean lightness.
     * On paper there is no mirror image. There is never a frame, a line or a shadow around the
     * card: only the print.
     */
    fun build(
        cover: Bitmap,
        round: Boolean,
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
        val out = createBitmap(side + 2 * pad, side + gap + reflection + 2 * pad)
        val c = Canvas(out)
        val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val print = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val radius = if (round) 0f else cornerPx

        fun card(top: Float, flipped: Boolean) {
            val m = Matrix()
            if (flipped) m.setScale(1f, -1f) // mirrored about the card's bottom edge
            m.postTranslate(pad.toFloat(), if (flipped) top + side else top)
            shader.setLocalMatrix(m)
            val rect = RectF(pad.toFloat(), top, (pad + side).toFloat(), top + side)
            if (round) c.drawRect(rect, print) else c.drawRoundRect(rect, radius, radius, print)
        }
        card(pad.toFloat(), flipped = false)
        if (appearance == Appearance.DARK) {
            // The mirror image: the card upside down below a hairline gap, fading out on the way down.
            val top = (pad + side + gap).toFloat()
            val layer = c.saveLayer(0f, top, out.width.toFloat(), top + reflection, null)
            card(top, flipped = true)
            val fade = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                this.shader = LinearGradient(
                    0f, top, 0f, top + reflection,
                    intArrayOf(alpha(REFLECTION), alpha(REFLECTION * 0.35f), alpha(REFLECTION * 0.08f), 0),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            c.drawRect(0f, top, out.width.toFloat(), top + reflection, fade)
            c.restoreToCount(layer)
        }
        out.prepareToDraw()
        val half = out.scale(out.width / 2, out.height / 2)
        half.prepareToDraw()
        return FlowCard(out, half, lightness, appearance)
    }

    private fun alpha(a: Float) = (a.coerceIn(0f, 1f) * 255f).roundToInt() shl 24
}
