package io.github.hazymorning.klangbild.art

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import io.github.hazymorning.klangbild.render.imageBitmapOf
import io.github.hazymorning.klangbild.render.plainPaint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally painted album covers. No real artwork may live in the repository, yet the
 * screenshot tests need covers that look like the real thing, including
 * the hard edge cases (almost black, almost white, monochrome, neon, low resolution,
 * visually busy). Deterministic for a given style and size.
 */
enum class SyntheticCover(val nativeSize: Int = 640) {
    NIGHT_DRIVE,          // neon synthwave sunset: vivid, neon edge case
    BAUHAUS,             // geometric shapes on paper: light cover
    NOCTURNE,            // crescent moon on black: almost black edge case
    MIST,           // misty mountains in grayscale: monochrome edge case
    SALT_WATER,          // ocean blues
    TERRACOTTA,          // warm desert sun
    MOSS,                // green hills in fog
    CONFETTI,            // busy, high-frequency confetti
    PASTEL(300),        // soft pastel, deliberately low resolution
    WHITE_SPACE,            // almost white with a single line
    CHAMBER_MUSIC(1200),   // color field painting, high resolution
    AURORA(1200),          // northern lights over a spruce forest: the README's song
}

object SyntheticCovers {

    fun render(cover: SyntheticCover, size: Int = cover.nativeSize): ImageBitmap {
        val noise = Noise(cover.ordinal * 7919 + 13)
        val px = when (cover) {
            SyntheticCover.MIST -> mist(size, noise)
            SyntheticCover.MOSS -> moss(size, noise)
            SyntheticCover.CHAMBER_MUSIC -> chamberMusic(size, noise)
            SyntheticCover.AURORA -> aurora(size, noise)
            else -> {
                val bmp = ImageBitmap(size, size)
                val c = Canvas(bmp)
                val s = size.toFloat()
                when (cover) {
                    SyntheticCover.NIGHT_DRIVE -> nightDrive(c, s)
                    SyntheticCover.BAUHAUS -> bauhaus(c, s)
                    SyntheticCover.NOCTURNE -> nocturne(c, s)
                    SyntheticCover.SALT_WATER -> saltWater(c, s, noise)
                    SyntheticCover.TERRACOTTA -> terracotta(c, s, noise)
                    SyntheticCover.CONFETTI -> confetti(c, s)
                    SyntheticCover.PASTEL -> pastel(c, s)
                    else -> whiteSpace(c, s)
                }
                IntArray(size * size).also { bmp.readPixels(it) }
            }
        }
        val grain = when (cover) {
            SyntheticCover.MIST -> 10f
            SyntheticCover.BAUHAUS, SyntheticCover.WHITE_SPACE -> 5f
            SyntheticCover.CHAMBER_MUSIC, SyntheticCover.TERRACOTTA -> 7f
            SyntheticCover.NOCTURNE -> 3f
            else -> 4f
        }
        addGrain(px, grain, cover.ordinal + 1)
        return imageBitmapOf(px, size, size)
    }

    private fun paint(color: Long) = plainPaint().apply { this.color = Color(color.toInt()) }

    /** A paint filling with [shader]. */
    private fun fill(shader: androidx.compose.ui.graphics.Shader) = plainPaint().apply { this.shader = shader }

    /** Android's linear gradient from (x0, y0) to (x1, y1): [colors] spread evenly where there are no [stops]. */
    private fun linear(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray? = null) =
        LinearGradientShader(Offset(x0, y0), Offset(x1, y1), colors.map { Color(it) }, stops?.toList(), TileMode.Clamp)

    private fun nightDrive(c: Canvas, s: Float) {
        val horizon = 0.64f * s
        c.drawRect(
            0f, 0f, s, horizon,
            fill(
                linear(
                    0f, 0f, 0f, horizon,
                    intArrayOf(0xFF0E0322.toInt(), 0xFF2B0A4A.toInt(), 0xFF8A1B6E.toInt(), 0xFFFF4F8B.toInt()),
                    floatArrayOf(0f, 0.45f, 0.82f, 1f),
                ),
            ),
        )
        // Sun with horizontal cuts
        val cx = 0.5f * s
        val cy = 0.52f * s
        val r = 0.23f * s
        c.save()
        c.clipRect(0f, 0f, s, horizon)
        val sunPaint = fill(linear(0f, cy - r, 0f, cy + r, intArrayOf(0xFFFFE066.toInt(), 0xFFFF3D7F.toInt())))
        c.drawCircle(Offset(cx, cy), r, sunPaint)
        val cut = fill(linear(0f, 0f, 0f, horizon, intArrayOf(0xFF2B0A4A.toInt(), 0xFF8A1B6E.toInt(), 0xFFFF4F8B.toInt()), floatArrayOf(0.45f, 0.82f, 1f)))
        var y = cy + 0.02f * s
        var h = 0.006f * s
        while (y < cy + r) {
            c.drawRect(cx - r - 2, y, cx + r + 2, y + h, cut)
            y += h + 0.03f * s
            h *= 1.45f
        }
        c.restore()
        // Ground
        c.drawRect(0f, horizon, s, s, fill(linear(0f, horizon, 0f, s, intArrayOf(0xFF1A0631.toInt(), 0xFF05010C.toInt()))))
        val grid = plainPaint().apply {
            color = Color(0xFF00E5FF.toInt())
            strokeWidth = 0.0035f * s
            style = PaintingStyle.Stroke
        }
        // horizontal lines, perspective spacing
        for (i in 1..12) {
            val t = (i / 12f).pow(2.2f)
            val gy = horizon + t * (s - horizon)
            grid.alpha = (90 + 165 * t).toInt().coerceAtMost(255) / 255f
            c.drawLine(Offset(0f, gy), Offset(s, gy), grid)
        }
        grid.alpha = 200 / 255f
        for (i in -14..14) {
            val bx = cx + i * 0.11f * s
            c.drawLine(Offset(cx + i * 0.012f * s, horizon), Offset(bx, s), grid)
        }
        // Horizon glow
        c.drawRect(
            0f, horizon - 0.04f * s, s, horizon + 0.04f * s,
            fill(linear(0f, horizon - 0.04f * s, 0f, horizon + 0.04f * s, intArrayOf(0x00FF4F8B, 0x88FF4F8B.toInt(), 0x00FF4F8B))),
        )
    }

    private fun bauhaus(c: Canvas, s: Float) {
        background(c, s, 0xFFEEE7D8)
        c.drawCircle(Offset(0.38f * s, 0.40f * s), 0.24f * s, paint(0xFFD8512E))
        c.drawArc(0.46f * s, 0.52f * s, 0.94f * s, 1.0f * s, 180f, 180f, true, paint(0xFF1D1D1F))
        c.drawArc(0.62f * s, -0.18f * s, 1.12f * s, 0.32f * s, 90f, 90f, true, paint(0xFF2E5BA6))
        val line = paint(0xFF1D1D1F).apply { strokeWidth = 0.008f * s }
        c.drawLine(Offset(0.08f * s, 0.84f * s), Offset(0.62f * s, 0.84f * s), line)
        c.drawLine(Offset(0.08f * s, 0.88f * s), Offset(0.40f * s, 0.88f * s), line)
        c.drawRect(0.10f * s, 0.10f * s, 0.18f * s, 0.18f * s, paint(0xFFE8B931))
    }

    private fun nocturne(c: Canvas, s: Float) {
        background(c, s, 0xFF09090C)
        // faint horizon haze
        c.drawRect(0f, 0.55f * s, s, s, fill(linear(0f, 0.55f * s, 0f, s, intArrayOf(0x00181822, 0x55141420))))
        val moon = Path().apply {
            op(circle(0.66f * s, 0.30f * s, 0.075f * s), circle(0.69f * s, 0.285f * s, 0.07f * s), PathOperation.Difference)
        }
        c.drawPath(moon, paint(0xFFE9DDBA))
        val rnd = Random(7)
        val star = plainPaint()
        repeat(26) {
            val x = rnd.nextFloat() * s
            val y = rnd.nextFloat() * 0.6f * s
            star.color = Color(0xFFDCD6C8.toInt())
            star.alpha = (40 + rnd.nextInt(120)) / 255f
            c.drawCircle(Offset(x, y), (0.0015f + rnd.nextFloat() * 0.002f) * s, star)
        }
        // thin title-like rule
        c.drawRect(0.1f * s, 0.86f * s, 0.32f * s, 0.862f * s, paint(0x66DCD6C8))
    }

    private fun mist(size: Int, noise: Noise): IntArray {
        val w = size
        val h = size
        val px = IntArray(w * h)
        val layers = 6
        for (y in 0 until h) {
            for (x in 0 until w) {
                val u = x / w.toFloat()
                val v = y / h.toFloat()
                var lum = 0.82f - 0.18f * v // sky
                for (l in 0 until layers) {
                    val depth = l / (layers - 1f)
                    val base = 0.38f + depth * 0.42f
                    val ridge = base - 0.16f * (noise.line(u * (2.2f + l * 0.9f) + l * 3.7f, l * 1.3f) - 0.5f) * 2f *
                        (1f - depth * 0.35f) - 0.05f * sin(u * PI.toFloat() * (1.3f + l * 0.4f) + l)
                    if (v > ridge) {
                        val tone = 0.70f - depth * 0.58f
                        val fog = exp(-(v - ridge) * (6f + depth * 10f)) * (0.35f - depth * 0.25f)
                        lum = tone + fog
                    }
                }
                val g = (lum.coerceIn(0f, 1f) * 255f).toInt()
                px[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return px
    }

    private fun saltWater(c: Canvas, s: Float, noise: Noise) {
        c.drawRect(
            0f, 0f, s, s,
            fill(
                linear(
                    0f, 0f, 0f, s,
                    intArrayOf(0xFF0B2A4A.toInt(), 0xFF0F4C6E.toInt(), 0xFF0A6E7A.toInt(), 0xFF07303F.toInt()),
                    floatArrayOf(0f, 0.38f, 0.62f, 1f),
                ),
            ),
        )
        // low sun reflection
        c.drawCircle(Offset(0.30f * s, 0.24f * s), 0.06f * s, paint(0xCCF3E9D2))
        val colors = longArrayOf(0xFF0E5A73, 0xFF0B4660, 0xFF0D7A86, 0xFF093A52, 0xFF0B5C6B, 0xFF062A3B)
        for (i in colors.indices) {
            val top = (0.42f + i * 0.09f) * s
            val path = Path()
            path.moveTo(0f, s)
            var x = 0f
            path.lineTo(0f, top)
            while (x <= s) {
                val u = x / s
                val yy = top + 0.03f * s * sin(u * 2f * PI.toFloat() * (1.2f + i * 0.35f) + i * 1.7f) +
                    0.025f * s * (noise.line(u * 3f + i, i * 2f) - 0.5f)
                path.lineTo(x, yy)
                if (x >= s) break
                x = minOf(s, x + s / 120f)
            }
            path.lineTo(s, s)
            path.close()
            c.drawPath(path, paint(colors[i]))
            val foam = paint(0x55E8F4F2).apply {
                style = PaintingStyle.Stroke
                strokeWidth = 0.0025f * s
            }
            c.drawPath(path, foam)
        }
    }

    private fun terracotta(c: Canvas, s: Float, noise: Noise) {
        c.drawRect(
            0f, 0f, s, s,
            fill(linear(0f, 0f, 0f, s, intArrayOf(0xFFF0B98D.toInt(), 0xFFE08A5E.toInt(), 0xFFB9502F.toInt()), floatArrayOf(0f, 0.5f, 1f))),
        )
        c.drawCircle(Offset(0.58f * s, 0.42f * s), 0.2f * s, paint(0xFFF7E6C8))
        val dunes = longArrayOf(0xFFB4492B, 0xFF93361F, 0xFF6E2716, 0xFF4A190F)
        for (i in dunes.indices) {
            val top = (0.56f + i * 0.1f) * s
            val path = Path()
            path.moveTo(0f, s)
            path.lineTo(0f, top)
            var x = 0f
            while (x <= s) {
                val u = x / s
                val yy = top - 0.07f * s * sin(u * PI.toFloat() * (1f + 0.4f * i) + i * 2.1f) -
                    0.03f * s * noise.line(u * 1.5f + i * 5f, i.toFloat())
                path.lineTo(x, yy)
                if (x >= s) break
                x = minOf(s, x + s / 100f)
            }
            path.lineTo(s, s)
            path.close()
            c.drawPath(path, paint(dunes[i]))
        }
    }

    private fun moss(size: Int, noise: Noise): IntArray {
        val w = size
        val h = size
        val px = IntArray(w * h)
        val sky = floatArrayOf(0.80f, 0.84f, 0.78f)
        val layerColors = arrayOf(
            floatArrayOf(0.55f, 0.64f, 0.52f),
            floatArrayOf(0.36f, 0.48f, 0.35f),
            floatArrayOf(0.22f, 0.36f, 0.24f),
            floatArrayOf(0.13f, 0.24f, 0.15f),
            floatArrayOf(0.07f, 0.14f, 0.09f),
        )
        for (y in 0 until h) for (x in 0 until w) {
            val u = x / w.toFloat()
            val v = y / h.toFloat()
            var col = floatArrayOf(sky[0] - 0.1f * v, sky[1] - 0.08f * v, sky[2] - 0.1f * v)
            for (l in layerColors.indices) {
                val depth = l / (layerColors.size - 1f)
                val ridge = 0.40f + depth * 0.45f - 0.12f * (noise.line(u * (1.6f + l * 0.7f) + l * 9f, l * 2f) - 0.5f) * 2f
                if (v > ridge) {
                    val fog = exp(-(v - ridge) * (5f + 12f * depth)) * (0.4f - 0.3f * depth)
                    val lc = layerColors[l]
                    col = floatArrayOf(lc[0] + fog * 0.5f, lc[1] + fog * 0.5f, lc[2] + fog * 0.45f)
                }
            }
            val r = (col[0].coerceIn(0f, 1f) * 255).toInt()
            val g = (col[1].coerceIn(0f, 1f) * 255).toInt()
            val b = (col[2].coerceIn(0f, 1f) * 255).toInt()
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return px
    }

    private fun confetti(c: Canvas, s: Float) {
        background(c, s, 0xFF2B2F6B)
        val rnd = Random(42)
        val palette = longArrayOf(0xFFFF5D73, 0xFFFFC145, 0xFF3DDC97, 0xFF4CC9F0, 0xFFF15BB5, 0xFFFFFFFF, 0xFFFF8C42)
        val p = plainPaint()
        repeat(900) {
            p.color = Color(palette[rnd.nextInt(palette.size)].toInt())
            val x = rnd.nextFloat() * s
            val y = rnd.nextFloat() * s
            val r = (0.006f + rnd.nextFloat() * 0.018f) * s
            c.save()
            c.translate(x, y)
            c.rotate(rnd.nextFloat() * 360f)
            c.translate(-x, -y)
            when (rnd.nextInt(3)) {
                0 -> c.drawCircle(Offset(x, y), r * 0.6f, p)
                1 -> c.drawRect(x - r, y - r * 0.35f, x + r, y + r * 0.35f, p)
                else -> c.drawRect(x - r * 0.5f, y - r * 0.5f, x + r * 0.5f, y + r * 0.5f, p)
            }
            c.restore()
        }
    }

    private fun pastel(c: Canvas, s: Float) {
        c.drawRect(0f, 0f, s, s, fill(linear(0f, 0f, s, s, intArrayOf(0xFFF6C6C8.toInt(), 0xFFF8DCC4.toInt(), 0xFFD9CCF0.toInt()))))
        c.drawCircle(
            Offset(0.5f * s, 0.5f * s),
            0.24f * s,
            fill(RadialGradientShader(Offset(0.46f * s, 0.44f * s), 0.3f * s, listOf(Color(0xFFFFFBF4.toInt()), Color(0xFFF3E1E9.toInt())), null, TileMode.Clamp)),
        )
    }

    private fun whiteSpace(c: Canvas, s: Float) {
        background(c, s, 0xFFF6F5F1)
        val line = paint(0xFF202020).apply {
            style = PaintingStyle.Stroke
            strokeWidth = 0.004f * s
            strokeCap = StrokeCap.Round
        }
        val path = Path()
        path.moveTo(0.3f * s, 0.55f * s)
        var x = 0.3f
        while (x <= 0.7f) {
            val y = 0.55f - 0.05f * sin((x - 0.3f) / 0.4f * 3f * PI.toFloat()) * exp(-abs(x - 0.5f) * 3f)
            path.lineTo(x * s, y * s)
            x += 0.004f
        }
        c.drawPath(path, line)
        c.drawRect(0.44f * s, 0.86f * s, 0.56f * s, 0.864f * s, paint(0xFF9A9A96))
    }

    private fun chamberMusic(size: Int, noise: Noise): IntArray {
        val w = size
        val h = size
        val px = IntArray(w * h)
        val ground = floatArrayOf(0.30f, 0.17f, 0.10f)
        val upper = floatArrayOf(0.78f, 0.52f, 0.20f)
        val lower = floatArrayOf(0.42f, 0.12f, 0.08f)
        fun block(u: Float, v: Float, x0: Float, y0: Float, x1: Float, y1: Float, n: Float): Float {
            val feather = 0.025f + 0.02f * n
            val fx = smooth(x0 - feather, x0 + feather, u) * (1f - smooth(x1 - feather, x1 + feather, u))
            val fy = smooth(y0 - feather, y0 + feather, v) * (1f - smooth(y1 - feather, y1 + feather, v))
            return fx * fy
        }
        for (y in 0 until h) for (x in 0 until w) {
            val u = x / w.toFloat()
            val v = y / h.toFloat()
            val n = noise.fbm(u * 6f, v * 6f, 4)
            val brush = noise.fbm(u * 40f, v * 3f, 3) - 0.5f
            val a = block(u, v, 0.1f, 0.08f, 0.9f, 0.5f, n) * (0.92f + 0.12f * brush)
            val b = block(u, v, 0.1f, 0.56f, 0.9f, 0.92f, n) * (0.9f + 0.14f * brush)
            val col = FloatArray(3) { i ->
                var cc = ground[i] * (0.9f + 0.2f * n)
                cc = cc + (upper[i] - cc) * a
                cc = cc + (lower[i] - cc) * b
                cc
            }
            val r = (col[0].coerceIn(0f, 1f) * 255).toInt()
            val g = (col[1].coerceIn(0f, 1f) * 255).toInt()
            val bl = (col[2].coerceIn(0f, 1f) * 255).toInt()
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return px
    }

    /**
     * Northern lights over a spruce forest: one ribbon swinging across the night sky, bright and
     * mint green at its lower edge, its rays fading up through teal to violet, the air around it
     * lit; stars; a treeline of spruces of many heights against the sky's last blue.
     */
    private fun aurora(size: Int, noise: Noise): IntArray {
        val w = size
        val h = size
        val px = IntArray(w * h)
        val sky = arrayOf(rgb(0x01030A), rgb(0x081230), rgb(0x123056))
        val mint = rgb(0xA6FFD6)
        val green = rgb(0x3EE597)
        val teal = rgb(0x25B2B8)
        val violet = rgb(0x7A55EC)
        val rose = rgb(0xD257B2)
        val haze = rgb(0x134552)
        val wood = rgb(0x02050C)
        class Spruce(val x: Float, val top: Float, val height: Float, val half: Float, val tiers: Int)
        val trees = ArrayList<Spruce>()
        val r = Random(3)
        var tx = 0f
        while (tx < 1.05f) {
            var tall = 0.06f + 0.09f * r.nextFloat().pow(1.6f)
            if (r.nextFloat() < 0.12f) tall *= 1.4f
            val wide = tall * (0.32f + 0.08f * r.nextFloat())
            trees += Spruce(tx, 0.93f - 0.02f * sin(2.2f * tx + 0.5f) - tall, tall, wide / 2f, 6 + (tall * 30f).toInt())
            tx += wide * (0.45f + 0.5f * r.nextFloat())
        }
        val stars = Random(17)
        val col = FloatArray(3)
        val c = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            val u = x / w.toFloat()
            val v = y / h.toFloat()
            val t = (v / 0.86f).coerceIn(0f, 1f)
            if (t < 0.5f) mix(sky[0], sky[1], t / 0.5f, col) else mix(sky[1], sky[2], (t - 0.5f) / 0.5f, col)
            val star = (stars.nextFloat().pow(1100) * 1.8f + stars.nextFloat().pow(4000) * 3f) * smooth(0.85f, 0.3f, v)
            col[0] += star * 0.85f
            col[1] += star * 0.92f
            col[2] += star
            // The ribbon's lower edge swings across the sky; its rays rise from it.
            val edgeY = 0.46f + 0.15f * sin(2.7f * u - 0.4f) + 0.035f * sin(8.1f * u + 1.2f) + 0.010f * sin(23f * u)
            val dy = edgeY - v
            val along = ((noise.fbm(u * 6f, 3.7f + v, 4) - 0.22f) * 2.6f).coerceIn(0.25f, 1.4f)
            val rays = (0.3f + 1.9f * (noise.fbm(u * 30f, 11f + v, 3) - 0.38f)).coerceIn(0.05f, 1.7f)
            val reach = 0.19f + 0.12f * noise.fbm(u * 3f, 21f + v, 3)
            val ragged = 0.004f + 0.006f * noise.fbm(u * 60f, 31f + v, 2)
            val edge = exp(-(dy / ragged) * (dy / ragged))
            val body = smooth(-0.002f, 0.012f, dy) * exp(-max(dy, 0f) / reach) * rays * 1.7f
            val below = if (dy < 0f) exp(dy / 0.03f) * 0.22f else 0f
            val light = (body + 0.7f * edge + below) * along
            val up = (dy / 0.30f).coerceIn(0f, 1f)
            when {
                up < 0.07f -> mix(mint, green, up / 0.07f, c)
                up < 0.42f -> mix(green, teal, (up - 0.07f) / 0.35f, c)
                up < 0.78f -> mix(teal, violet, (up - 0.42f) / 0.36f, c)
                else -> mix(violet, rose, (up - 0.78f) / 0.22f, c)
            }
            val air = exp(-abs(dy) / 0.14f) * 0.32f * along
            for (i in 0..2) col[i] += c[i] * light + haze[i] * air
            // The forest against the sky.
            val ground = 0.93f - 0.025f * sin(2.2f * u + 0.5f) - 0.012f * (noise.fbm(u * 8f, 41f + v, 3) - 0.5f)
            var dark = v > ground
            if (!dark && v > 0.6f) {
                for (tree in trees) {
                    val off = abs(u - tree.x)
                    if (off > tree.half) continue
                    val down = (v - tree.top) / tree.height
                    if (down < 0f) continue
                    val tier = (down * tree.tiers) % 1f
                    if (off < tree.half * down.coerceAtMost(1f) * (0.72f + 0.28f * tier) || (down > 0.92f && off < 0.004f)) {
                        dark = true
                        break
                    }
                }
            }
            if (dark) wood.copyInto(col)
            val red = (col[0].coerceIn(0f, 1f) * 255).toInt()
            val gr = (col[1].coerceIn(0f, 1f) * 255).toInt()
            val bl = (col[2].coerceIn(0f, 1f) * 255).toInt()
            px[y * w + x] = (0xFF shl 24) or (red shl 16) or (gr shl 8) or bl
        }
        return px
    }

    private fun rgb(hex: Int) = floatArrayOf(((hex shr 16) and 0xFF) / 255f, ((hex shr 8) and 0xFF) / 255f, (hex and 0xFF) / 255f)

    private fun mix(a: FloatArray, b: FloatArray, t: Float, out: FloatArray) {
        for (i in 0..2) out[i] = a[i] + (b[i] - a[i]) * t
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun addGrain(px: IntArray, amount: Float, seed: Int) {
        val rnd = Random(seed)
        for (i in px.indices) {
            val p = px[i]
            val n = ((rnd.nextFloat() + rnd.nextFloat() - 1f) * amount).toInt()
            val r = (((p shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) + n).coerceIn(0, 255)
            val b = ((p and 0xFF) + n).coerceIn(0, 255)
            px[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    /** [color] over the whole cover. */
    private fun background(c: Canvas, s: Float, color: Long) = c.drawRect(0f, 0f, s, s, paint(color))

    /** A circle as Android's Path.addCircle makes it: an oval, clockwise. */
    private fun circle(cx: Float, cy: Float, r: Float) = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r), Path.Direction.Clockwise) }
}
