package io.github.hazymorning.klangbild.ui

import androidx.compose.ui.graphics.ImageBitmap
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.render.imageBitmapOf
import kotlin.math.max

/**
 * A QR code as a printed card, drawn 1:1 where a cover stands: dark modules on whole pixels, on
 * ivory paper (a whiter one on the light background) with a generous quiet zone. The setup
 * screen shows its address this way, and the stage the code of a party playing here.
 */
object QrCard {
    /** [qr]'s modules (true is dark) on a card [sizePx] square for [appearance]; without [qr], the paper alone. */
    fun of(qr: Array<BooleanArray>?, sizePx: Int, appearance: Appearance): ImageBitmap {
        val px = IntArray(sizePx * sizePx)
        px.fill(if (appearance == Appearance.DARK) PAPER_ON_DARK else PAPER_ON_LIGHT)
        if (qr != null) {
            val n = qr.size
            val module = max(1, (sizePx * CODE_SHARE / n).toInt())
            val offset = (sizePx - module * n) / 2
            for (y in 0 until n) for (x in 0 until n) {
                if (qr[y][x]) {
                    val l = (offset + x * module).coerceIn(0, sizePx)
                    val r = (offset + x * module + module).coerceIn(0, sizePx)
                    for (row in (offset + y * module).coerceIn(0, sizePx) until (offset + y * module + module).coerceIn(0, sizePx)) {
                        px.fill(INK, row * sizePx + l, row * sizePx + r)
                    }
                }
            }
        }
        return imageBitmapOf(px, sizePx, sizePx)
    }

    private const val PAPER_ON_DARK = 0xFFEAE2D4.toInt()
    private const val PAPER_ON_LIGHT = 0xFFF7F4EE.toInt()
    private const val INK = 0xFF1C1814.toInt()

    /** How much of the card's side the code takes; the rest is its quiet zone. */
    private const val CODE_SHARE = 0.80f
}
