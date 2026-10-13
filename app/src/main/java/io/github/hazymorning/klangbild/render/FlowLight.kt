package io.github.hazymorning.klangbild.render

import io.github.hazymorning.klangbild.color.Lch
import io.github.hazymorning.klangbild.color.Oklab
import io.github.hazymorning.klangbild.math.smoothstep
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * The wall behind the cover flow in the dark: a soft oval of light above the middle card, in
 * the color of its top edge, as if the picture lit the wall above it. It is at most ≈ L* 8
 * and follows the edge's lightness, so a dark edge gives a faint glow. It fades out before the
 * floor (where the mirror images stand on black) and the screen's edges, so no edge of it shows.
 * Toward the text column it is faded where it is drawn ([shade]): the column moves when cards
 * are missing ([FlowGeometry.balance]), and it stands on black wherever it stands.
 *
 * One dithered bitmap per scene at half the dp resolution (the light is soft), drawn as one
 * rectangle like [WallGlow].
 */
object FlowLight {
    const val MAP_W = 480
    const val MAP_H = 270

    /** Brightest point (relative luminance, ≈ L* 8) behind a light edge. A black edge gives [DARK_SHARE] of it. */
    const val PEAK = 0.0088f
    const val DARK_SHARE = 0.35f

    /** Spread of the oval (dp) and how far its center sits above the middle card's top edge. */
    const val SIGMA_X = 180f
    const val SIGMA_Y = 90f
    const val ABOVE = 20f

    /**
     * The color (ARGB) along the top edge of the print, from its [thumb] of [size] × [size]
     * pixels. For a round plate (transparent in its corners) it is the upper half of the disc.
     */
    fun topEdge(thumb: IntArray, size: Int): Int {
        fun mean(rows: Int): Int? {
            var r = 0f
            var g = 0f
            var b = 0f
            var n = 0
            for (y in 0 until rows) for (x in 0 until size) {
                val p = thumb[y * size + x]
                if ((p ushr 24) < 128) continue
                r += Oklab.srgbToLinear(p shr 16)
                g += Oklab.srgbToLinear(p shr 8)
                b += Oklab.srgbToLinear(p)
                n++
            }
            if (n == 0) return null
            fun ch(v: Float) = (Oklab.linearToSrgb(v / n) * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
        return mean((size / 16).coerceAtLeast(1)) ?: mean(size / 2) ?: 0xFF000000.toInt()
    }

    fun field(edge: Int, layout: StageGrid): IntArray {
        val screen = layout.screen
        val lab = Oklab.fromArgb(edge)
        val peak = PEAK * (DARK_SHARE + (1f - DARK_SHARE) * smoothstep(0.05f, 0.35f, lab.l))
        // The edge's own hue, a little more colorful (it is light, not paint). A gray edge gives lamp light.
        val color = if (lab.chroma < 0.02f) LampLight.LAMP else Lch(0.62f, min(lab.chroma * 1.2f, 0.12f), lab.hue)
        val tint = LampLight.lightTint(color, peak)
        val tr = Oklab.srgbToLinear(tint shr 16)
        val tg = Oklab.srgbToLinear(tint shr 8)
        val tb = Oklab.srgbToLinear(tint)
        val img = LinearImage(MAP_W, MAP_H)
        val sx = screen.widthDp / MAP_W
        val sy = screen.heightDp / MAP_H
        val cx = layout.artRect.centerX
        val cy = layout.artTop - ABOVE
        val floor = layout.artTop + layout.art
        for (y in 0 until MAP_H) {
            val py = (y + 0.5f) * sy
            // Gone by the floor line: the floor is dark glass.
            val wall = smoothstep(floor, layout.artTop + 0.35f * layout.art, py)
            for (x in 0 until MAP_W) {
                val px = (x + 0.5f) * sx
                val u = (px - cx) / SIGMA_X
                val v = (py - cy) / SIGMA_Y
                val fade = smoothstep(WallGlow.EDGE_BLACK_DP, WallGlow.EDGE_BLACK_DP + 120f, min(px, screen.widthDp - px)) *
                    smoothstep(WallGlow.EDGE_BLACK_DP, WallGlow.EDGE_BLACK_DP + 60f, min(py, screen.heightDp - py))
                val value = exp(-0.5f * (u * u + v * v)) * fade * wall
                val o = y * MAP_W + x
                img.r[o] = tr * value
                img.g[o] = tg * value
                img.b[o] = tb * value
            }
        }
        return ImageOps.toArgbDithered(img, seed = 19, ditherLsb = 1.1f, blackBelow = 0.0004f)
    }

    /** The light is gone this far before the text column, and whole this far before it (dp). */
    const val GONE_BEFORE_COLUMN = 30f
    const val WHOLE_BEFORE_COLUMN = 220f

    /**
     * The opacity of black laid over the sRGB map at [t] of the way from [WHOLE_BEFORE_COLUMN]
     * to [GONE_BEFORE_COLUMN] before the column, so that a smoothstep's share of the light is
     * left in linear light. At the light's low levels sRGB is close to linear, so the share is
     * taken to the power [SHADE_POWER] (within a level of the exact curve).
     */
    fun shade(t: Float): Float {
        val left = 1f - smoothstep(0f, 1f, t)
        return 1f - left.pow(SHADE_POWER)
    }

    private const val SHADE_POWER = 0.85f
}
