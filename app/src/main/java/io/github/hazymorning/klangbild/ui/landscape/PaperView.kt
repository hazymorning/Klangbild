package io.github.hazymorning.klangbild.ui.landscape

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.withTranslation
import io.github.hazymorning.klangbild.landscape.CameraPose
import io.github.hazymorning.klangbild.landscape.CloudPlace
import io.github.hazymorning.klangbild.landscape.Depth
import io.github.hazymorning.klangbild.landscape.Inks
import io.github.hazymorning.klangbild.landscape.MistField
import io.github.hazymorning.klangbild.landscape.Noise
import io.github.hazymorning.klangbild.landscape.PaperBake
import io.github.hazymorning.klangbild.landscape.PaperBaked
import io.github.hazymorning.klangbild.landscape.PaperLook
import io.github.hazymorning.klangbild.landscape.PaperMotif
import io.github.hazymorning.klangbild.landscape.Sheet
import io.github.hazymorning.klangbild.landscape.Shapes
import io.github.hazymorning.klangbild.landscape.SheetId
import io.github.hazymorning.klangbild.landscape.SkyPlaces
import io.github.hazymorning.klangbild.landscape.LandscapeCamera
import io.github.hazymorning.klangbild.landscape.Weather
import io.github.hazymorning.klangbild.landscape.Weatherfall
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The baked sheets as images the screen can draw (made off the main thread); the baked pixels are let go once copied. */
class PaperImages(val baked: PaperBaked) {
    private fun img(s: Sheet): ImageBitmap = Bitmap.createBitmap(s.pixels, s.width, s.height, Bitmap.Config.ARGB_8888).asImageBitmap().also { s.release() }

    val sheets: Map<SheetId, ImageBitmap> = baked.sheets.mapValues { img(it.value) }
    val shadows: Map<SheetId, ImageBitmap> = baked.shadows.mapValues { img(it.value) }
    val standees: List<List<ImageBitmap>> = baked.standees.map { st -> st.parts.map { img(it.second) } }
    val standeeShadows: List<List<ImageBitmap>> = baked.standees.map { st -> st.shadows.map { img(it) } }
    val frontStandees: List<List<ImageBitmap>> = baked.frontStandees.map { st -> st.parts.map { img(it.second) } }
    val frontShadows: List<List<ImageBitmap>> = baked.frontStandees.map { st -> st.shadows.map { img(it) } }
    val clouds = baked.clouds.map { img(it) }
    val cloudShadows = baked.cloudShadows.map { img(it) }
    val flames = baked.flames.map { img(it.sheet) }
    val sun = img(baked.sun)
    val moon = img(baked.moon)
    val discShadow = img(baked.discShadow)
    val glow = img(baked.glow)
}

/**
 * What moves, at one moment: the clouds, the fire, the stars' flicker, the cut-outs in the wind,
 * the camera, what falls. [time] is the wall clock's seconds, [cloudTime] the clouds' own clock
 * (faster in the wind), [drift] how far the wind has blown so far (its integral over time).
 */
class PaperMotion(
    val time: Double,
    val clouds: List<CloudPlace>,
    val camera: CameraPose = LandscapeCamera.REST,
    val weather: Weather = Weather.FAIR,
    val cloudTime: Double = time,
    val drift: Double = time * Weather.BREEZE,
)

/**
 * Draws the diorama in layers, each moved by the camera as far as its depth asks
 * ([LandscapeCamera]). The sky (with the sun and the moon), the far mountains, the clearing and the
 * near banks change only with the light (every ten seconds or so), so each is drawn into a
 * layer of its own and kept between frames, and the camera only moves the finished layer. The
 * stars and the clouds, the cut-outs swaying in the breeze, the fire and its light are drawn
 * every frame. Everything that changes is a color matrix, a position or a small transform, so
 * nothing is cut anew. [look] and [places] should only be read anew when the light has moved on.
 */
@Composable
fun PaperView(images: PaperImages, look: () -> PaperLook, places: () -> SkyPlaces, motion: () -> PaperMotion, modifier: Modifier = Modifier) {
    val filters = remember(images) { FilterCache() }
    val stars = remember(images) { StarBatches() }
    val fall = remember(images) { FallBatches() }
    val b = images.baked
    val all = 0 to b.canvasHeight
    val sky = remember(images) { 0 to b.sheets.getValue(SheetId.SKY).let { it.top + it.height } }
    val far = remember(images) { b.band(Depth.FAR) }
    val mid = remember(images) { b.band(Depth.MID) }
    val front = remember(images) { b.band(Depth.FRONT) }
    Box(modifier) {
        Canvas(Modifier.canvas(b, sky).camera(Depth.SKY, motion, b, sky, cached = true)) { rows(sky) { drawBack(images, look(), places(), filters) } }
        Canvas(Modifier.canvas(b, all).camera(Depth.SKY, motion, b, all, cached = false)) { drawMoving(images, look(), places(), motion(), filters, stars) }
        Canvas(Modifier.canvas(b, far).camera(Depth.FAR, motion, b, far, cached = true)) { rows(far) { drawLand(images, look(), filters, Depth.FAR) } }
        Canvas(Modifier.canvas(b, all).camera(Depth.FAR, motion, b, all, cached = false)) { drawWeather(images, look(), motion(), filters, fall, Depth.FAR) }
        Canvas(Modifier.canvas(b, mid).camera(Depth.MID, motion, b, mid, cached = true)) { rows(mid) { drawLand(images, look(), filters, Depth.MID) } }
        Canvas(Modifier.canvas(b, all).camera(Depth.MID, motion, b, all, cached = false)) { drawStandeesAndFlames(images, look(), motion(), filters) }
        Canvas(Modifier.canvas(b, all).camera(Depth.MID, motion, b, all, cached = false)) { drawWeather(images, look(), motion(), filters, fall, Depth.MID) }
        Canvas(Modifier.canvas(b, front).camera(Depth.FRONT, motion, b, front, cached = true)) { rows(front) { drawLand(images, look(), filters, Depth.FRONT) } }
        Canvas(Modifier.canvas(b, all).camera(Depth.FRONT, motion, b, all, cached = false)) { drawFrontFirs(images, look(), motion(), filters) }
        Canvas(Modifier.canvas(b, all).camera(Depth.MID, motion, b, all, cached = false)) { drawLights(images, look(), motion(), filters) }
        Canvas(Modifier.canvas(b, all).camera(Depth.FRONT, motion, b, all, cached = false)) { drawWeather(images, look(), motion(), filters, fall, Depth.FRONT) }
    }
}

/**
 * A layer's canvas: the baked canvas's rows [rows] (pixels), placed where they stand. The
 * canvas reaches [PaperMotif.OVERSCAN] beyond the screen on every side, so neither the camera
 * nor the screen's drift ever shows an edge, and a layer kept as an image is only as tall as
 * the paper it holds.
 */
private fun Modifier.canvas(b: PaperBaked, rows: Pair<Int, Int>): Modifier = layout { measurable, constraints ->
    val o = (PaperMotif.OVERSCAN * b.density).roundToInt()
    val p = measurable.measure(Constraints.fixed(b.canvasWidth, rows.second - rows.first))
    layout(constraints.maxWidth, constraints.maxHeight) { p.place(-o, rows.first - o) }
}

/** Draws in the baked canvas's coordinates on a canvas that starts at row [rows]. */
private inline fun DrawScope.rows(rows: Pair<Int, Int>, block: DrawScope.() -> Unit) {
    translate(0f, -rows.first.toFloat()) { block() }
}

/**
 * Moves a layer with the camera, as far as its [depth] asks. It is read every frame, but only the
 * layer's transform changes: a [cached] layer keeps what it drew (until the light moves on).
 * The layer holds the baked canvas's [rows]; it turns about the camera's point on the screen.
 */
private fun Modifier.camera(depth: Depth, motion: () -> PaperMotion, b: PaperBaked, rows: Pair<Int, Int>, cached: Boolean): Modifier = graphicsLayer {
    val pose = motion().camera
    val k = pose.scale(depth)
    scaleX = k
    scaleY = k
    translationX = pose.dx(depth) * density
    translationY = pose.dy(depth) * density
    transformOrigin = TransformOrigin(b.px(LandscapeCamera.PIVOT_X) / b.canvasWidth, (b.px(LandscapeCamera.PIVOT_Y) - rows.first) / (rows.second - rows.first))
    if (cached) compositingStrategy = CompositingStrategy.Offscreen
}

/**
 * The stars as a dozen batches of points (by size and by which of four slow flickers they
 * follow), made anew only when they have turned (every few seconds), drawn with a call each.
 * Behind the moon's disc there are none.
 */
private class StarBatches {
    class Batch(val points: List<Offset>, val radius: Float, val brightness: Float, val group: Int)

    private var source: SkyPlaces? = null
    private var batches: List<Batch> = emptyList()

    fun of(p: SkyPlaces, b: PaperBaked, d: Float): List<Batch> {
        if (p === source) return batches
        source = p
        val groups = HashMap<Long, MutableList<Offset>>()
        val meta = HashMap<Long, Pair<Float, Float>>()
        for (i in p.stars.indices) {
            val s = p.stars[i]
            if (s.y > PaperMotif.skyline(s.x) - 2f) continue
            if (p.moonUp && hypot(s.x - p.moonX, s.y - p.moonY) < PaperBake.MOON_RADIUS + 3f) continue
            val key = (s.size.toRawBits().toLong() shl 8) or (i % FLICKERS).toLong()
            groups.getOrPut(key) { ArrayList() } += Offset(b.px(s.x), b.px(s.y))
            meta[key] = s.size * d to s.brightness
        }
        batches = groups.map { (key, points) -> Batch(points, meta.getValue(key).first, meta.getValue(key).second, (key and 0xFF).toInt()) }
        return batches
    }
}

private const val FLICKERS = 4

/** Color filters for inks, made once per new set of colors. */
private class FilterCache {
    private val cache = HashMap<Inks, ColorFilter>()
    private val tints = HashMap<Int, ColorFilter>()
    private val matrix = FloatArray(20)

    fun of(inks: Inks): ColorFilter {
        if (cache.size > 96) cache.clear()
        return cache.getOrPut(inks) { ColorFilter.colorMatrix(ColorMatrix(inks.matrix(matrix).copyOf())) }
    }

    fun tint(argb: Int): ColorFilter {
        if (tints.size > 64) tints.clear()
        return tints.getOrPut(argb) { ColorFilter.tint(Color(argb), BlendMode.Modulate) }
    }
}

/** Draws [img] (the baked [s]) at its place, moved by ([dx], [dy]) pixels. */
private fun DrawScope.sheet(img: ImageBitmap, s: Sheet, filter: ColorFilter?, alpha: Float = 1f, dx: Float = 0f, dy: Float = 0f) {
    if (s.scale == 1 && dx == 0f && dy == 0f) {
        drawImage(img, topLeft = Offset(s.left.toFloat(), s.top.toFloat()), colorFilter = filter, alpha = alpha)
    } else {
        drawImage(
            img,
            dstOffset = IntOffset((s.left + dx).roundToInt(), (s.top + dy).roundToInt()),
            dstSize = IntSize(s.width * s.scale, s.height * s.scale),
            colorFilter = filter,
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }
}

/** The sky's wash, the glows of the horizon and the sun, the sun's paper disc, the moon. */
private fun DrawScope.drawBack(images: PaperImages, l: PaperLook, p: SkyPlaces, f: FilterCache) {
    val b = images.baked
    val d = b.density
    fun dp(v: Float) = b.px(v)
    sheet(images.sheets.getValue(SheetId.SKY), b.sheets.getValue(SheetId.SKY), f.of(l.inks(SheetId.SKY)))

    // Glows: along the horizon under the sun, and around the sun itself.
    if (l.horizonGlowAlpha > 0.01f) {
        val w = 980f * d
        val h = 230f * d
        drawImage(
            images.glow, dstOffset = IntOffset((dp(p.sunX) - w / 2).roundToInt(), (dp(HORIZON_GLOW_Y) - h / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()), colorFilter = f.tint(l.horizonGlow), alpha = l.horizonGlowAlpha, filterQuality = FilterQuality.Low,
        )
    }
    if (l.glowAlpha * l.discAlpha > 0.01f && p.sunY < PaperMotif.FAR_FOOT) {
        val r = 150f * d
        drawImage(
            images.glow, dstOffset = IntOffset((dp(p.sunX) - r).roundToInt(), (dp(p.sunY) - r).roundToInt()),
            dstSize = IntSize((2 * r).roundToInt(), (2 * r).roundToInt()), colorFilter = f.tint(l.glow), alpha = l.glowAlpha * l.discAlpha, filterQuality = FilterQuality.Low,
        )
    }
    if (l.discAlpha > 0.01f && p.sunY < PaperMotif.FAR_FOOT) disc(images, images.sun, b.sun, dp(p.sunX), dp(p.sunY), Inks.flat(l.sunDisc), l.discAlpha, l, f)
    if (l.moonAlpha > 0.01f && p.moonFraction > 0.01f && p.moonY < PaperMotif.FAR_FOOT) drawMoon(images, l, p, f)
}

/** A paper disc glued on the sky, [alpha] strong: its soft shadow, then the disc in [inks]. */
private fun DrawScope.disc(images: PaperImages, img: ImageBitmap, s: Sheet, cx: Float, cy: Float, inks: Inks, alpha: Float, l: PaperLook, f: FilterCache) {
    val d = images.baked.density
    val sh = images.baked.discShadow
    if (l.shadowAlpha > 0.01f) {
        translate(cx, cy) {
            sheet(images.discShadow, sh, f.tint(l.shadow), alpha * l.shadowAlpha * 0.8f, l.shadowSide * 1.6f * d, 1.6f * d)
        }
    }
    translate(cx, cy) { sheet(img, s, f.of(inks), alpha) }
}

/**
 * The moon. At night the whole disc is a little lighter than the sky, and the lit part shows its
 * phase, its bright side toward the sun. By day only its lit part shows, faint, as the day moon is.
 */
private fun DrawScope.drawMoon(images: PaperImages, l: PaperLook, p: SkyPlaces, f: FilterCache) {
    val b = images.baked
    val cx = b.px(p.moonX)
    val cy = b.px(p.moonY)
    if (l.moonDarkAlpha > 0.01f) disc(images, images.moon, b.moon, cx, cy, Inks.flat(l.moonDark), l.moonDarkAlpha, l, f)
    val r = PaperBake.MOON_RADIUS * b.density + 1f
    val frac = p.moonFraction.coerceIn(0f, 1f)
    // Turned so the lit limb faces the sun (its place on the sky, even below the horizon).
    val angle = Math.toDegrees(atan2((p.sunY - p.moonY).toDouble(), (p.sunX - p.moonX).toDouble())).toFloat()
    val path = Path().apply {
        // The bright limb: a half circle on the right; the terminator: half an ellipse.
        val k = 1f - 2f * frac
        moveTo(0f, -r)
        arcTo(Rect(-r, -r, r, r), -90f, 180f, false)
        val ex = max(0.0001f, kotlin.math.abs(k) * r)
        arcTo(Rect(-ex, -r, ex, r), 90f, if (k > 0f) -180f else 180f, false)
        close()
    }
    withTransform({
        translate(cx, cy)
        rotate(angle, pivot = Offset.Zero)
    }) {
        clipPath(path) {
            rotate(-angle, pivot = Offset.Zero) { sheet(images.moon, b.moon, f.of(Inks.flat(l.moonLit)), l.moonAlpha) }
        }
    }
}

/** The stars' slow flicker and the drifting tissue clouds with their shadows on the sky. */
private fun DrawScope.drawMoving(images: PaperImages, l: PaperLook, p: SkyPlaces, m: PaperMotion, f: FilterCache, stars: StarBatches) {
    val b = images.baked
    val d = b.density
    if (l.stars > 0.01f) {
        for (batch in stars.of(p, b, d)) {
            val flicker = 0.78f + 0.22f * Noise.wander(m.time, 2.6 + batch.group * 0.7, 300 + batch.group).toFloat()
            drawPoints(
                batch.points, PointMode.Points, STAR, strokeWidth = 2f * batch.radius, cap = StrokeCap.Round,
                alpha = (l.stars * batch.brightness * flicker).coerceIn(0f, 1f),
            )
        }
    }
    val cloudFilter = f.of(l.cloud)
    val deckFilter = f.of(l.deck)
    val shadowFilter = f.tint(l.shadow)
    val depth = PaperBake.CLOUD_DEPTH
    for (c in m.clouds) {
        // The ceiling's heaps hang upside down: their humps toward the ground, their shadow still below them.
        val flip = if (c.deck) -1f else 1f
        withTransform({
            translate(b.px(c.x), b.px(c.y))
            scale(c.scale, c.scale * flip, pivot = Offset.Zero)
        }) {
            if (l.shadowAlpha > 0.01f) {
                sheet(
                    images.cloudShadows[c.design], b.cloudShadows[c.design], shadowFilter, c.alpha * l.shadowAlpha * 0.5f,
                    l.shadowSide * (1f + 3.8f * depth) * l.shadowLength * d, flip * (1.4f + 1.6f * depth) * d,
                )
            }
            sheet(images.clouds[c.design], b.clouds[c.design], if (c.deck) deckFilter else cloudFilter, c.alpha)
        }
    }
}

/** One layer of the land, back to front: each sheet's soft shadow on what lies behind it, then the sheet. */
private fun DrawScope.drawLand(images: PaperImages, l: PaperLook, f: FilterCache, depth: Depth) {
    val b = images.baked
    val d = b.density
    val shadowFilter = f.tint(l.shadow)
    for ((id, s) in b.sheets) {
        if (id == SheetId.SKY || id.layer != depth) continue
        val alpha = if (id == SheetId.LEAVES) b.leafAlpha else 1f
        if (alpha <= 0.01f) continue
        val shadow = b.shadows[id]
        if (shadow != null && l.shadowAlpha > 0.003f) {
            sheet(
                images.shadows.getValue(id), shadow, shadowFilter, l.shadowAlpha * alpha,
                l.shadowSide * (1f + 3.8f * id.depth) * l.shadowLength * d, (1.4f + 1.6f * id.depth) * d,
            )
        }
        sheet(images.sheets.getValue(id), s, f.of(l.inks(id)), alpha)
    }
}

/**
 * The cut-outs on the meadow, each swaying about its foot with its shadow. Each leans into the
 * wind and rocks on its own, all of them stirred by the same gusts, never in a loop. Then come
 * the campfire's tongues of tissue, each stretching and leaning on its own and all bending with
 * the wind, and then the smoke rising from them.
 */
private fun DrawScope.drawStandeesAndFlames(images: PaperImages, l: PaperLook, m: PaperMotion, f: FilterCache) {
    val b = images.baked
    val d = b.density
    val t = m.time
    val wind = m.weather.wind
    val shadowFilter = f.tint(l.shadow)
    val depth = SheetId.STANDEES.depth
    for (i in b.standees.indices) {
        val st = b.standees[i]
        val sway = swayOf(t, st.seed, wind)
        withTransform({
            translate(st.footX, st.footY)
            rotate(sway, pivot = Offset.Zero)
        }) {
            if (l.shadowAlpha > 0.003f) {
                for (k in st.shadows.indices) {
                    val sh = st.shadows[k]
                    val alpha = if (st.parts[k].first == SheetId.CROWNS) b.leafAlpha else 1f
                    sheet(images.standeeShadows[i][k], sh, shadowFilter, l.shadowAlpha * alpha, l.shadowSide * (1f + 3.8f * depth) * l.shadowLength * d, (1.4f + 1.6f * depth) * d)
                }
            }
            for (k in st.parts.indices) {
                val part = st.parts[k]
                val (id, sheet) = part
                val alpha = if (id == SheetId.CROWNS) b.leafAlpha else 1f
                if (alpha > 0.01f) sheet(images.standees[i][k], sheet, f.of(l.inks(id)), alpha)
            }
        }
    }
    val filter = f.of(l.flame)
    for (i in b.flames.indices) {
        val flame = b.flames[i]
        val k = flame.seed
        val stretch = 1f + 0.11f * Noise.wander(t, 0.8 + 0.2 * k, 500 + k).toFloat() + 0.05f * Noise.wander(t, 0.31, 520 + k).toFloat()
        // The wind blows to the left: the tongues bend with it.
        val lean = 4f * Noise.wander(t, 1.1 + 0.3 * k, 540 + k).toFloat() - FLAME_WIND_DEG * wind
        withTransform({
            translate(flame.footX, flame.footY)
            rotate(lean, pivot = Offset.Zero)
            scale(1f - 0.3f * (stretch - 1f), stretch, pivot = Offset.Zero)
        }) {
            sheet(images.flames[i], flame.sheet, filter)
        }
    }
    drawSmoke(images, l, m, f)
}

/**
 * The tall firs that frame the view, standing on the near banks. They sway like the meadow's
 * cut-outs, but slower and less because they are tall, with their soft shadows.
 */
private fun DrawScope.drawFrontFirs(images: PaperImages, l: PaperLook, m: PaperMotion, f: FilterCache) {
    val b = images.baked
    val d = b.density
    val t = m.time
    val wind = m.weather.wind
    val shadowFilter = f.tint(l.shadow)
    for (i in b.frontStandees.indices) {
        val st = b.frontStandees[i]
        val sway = FRONT_SWAY * swayOf(t * 0.7, st.seed, wind)
        withTransform({
            translate(st.footX, st.footY)
            rotate(sway, pivot = Offset.Zero)
        }) {
            for (k in st.parts.indices) {
                val part = st.parts[k]
                val (id, sheet) = part
                if (l.shadowAlpha > 0.003f) {
                    sheet(images.frontShadows[i][k], st.shadows[k], shadowFilter, l.shadowAlpha, l.shadowSide * (1f + 3.8f * id.depth) * l.shadowLength * d, (1.4f + 1.6f * id.depth) * d)
                }
                sheet(images.frontStandees[i][k], sheet, f.of(l.inks(id)))
            }
        }
    }
}

/** How far a cut-out leans at [t] (degrees): into the wind, rocking on its own, stirred by gusts the meadow shares. */
private fun swayOf(t: Double, seed: Int, wind: Float): Float {
    val amp = SWAY_DEG + SWAY_WIND_DEG * wind
    val own = (1.6 * Noise.wander(t, 4.6 + 0.7 * seed, 700 + seed)).toFloat().coerceIn(-1f, 1f)
    val gust = (0.5 + 0.8 * Noise.wander(t, 9.0, 777)).toFloat().coerceIn(0f, 1f)
    val flutter = sin(2 * PI * t / (1.7 + 0.21 * seed) + seed).toFloat()
    return -LEAN_DEG * wind + amp * (0.7f * own + 0.3f * flutter * gust)
}

/**
 * The campfire's smoke: soft puffs rising from the flames, widening and thinning as they go,
 * drifting off with the wind: light gray by day, a faint warm gray at night.
 */
private fun DrawScope.drawSmoke(images: PaperImages, l: PaperLook, m: PaperMotion, f: FilterCache) {
    if (l.smokeAlpha <= 0.005f) return
    val b = images.baked
    val d = b.density
    val t = m.time
    val wind = m.weather.wind
    val tint = f.tint(l.smoke)
    for (j in 0 until SMOKE_PUFFS) {
        val period = 6.0 + 2.2 * unit(j, 91)
        val phase = period * unit(j, 92)
        val age = ((t + phase) % period).toFloat()
        val life = age / period.toFloat()
        val rise = 24f * age.toDouble().pow(0.85).toFloat()
        val x = b.fireX / d + 4f * sin(age * 0.9f + j) - wind * (8f + 16f * age) * age * 0.5f
        val y = b.fireY / d - 30f - rise
        val r = 6f + 7.5f * age
        val a = l.smokeAlpha * Shapes.smoothstep(0f, 0.12f, life) * (1f - life).pow(1.4f)
        if (a <= 0.004f) continue
        val w = 2f * r * d / 0.395f
        drawImage(
            images.glow, dstOffset = IntOffset((x * d - w / 2).roundToInt(), (y * d - w / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), w.roundToInt()), colorFilter = tint, alpha = a.coerceIn(0f, 1f), filterQuality = FilterQuality.Low,
        )
    }
}

/** A well-mixed hash of an item's number, 0..1. */
private fun unit(i: Int, seed: Int): Float {
    var x = i.toLong() * -0x61c8864680b583ebL + seed * 0x9E3779B97F4A7C15uL.toLong()
    x = (x xor (x ushr 33)) * -0xae502812aa7333L
    x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
    x = x xor (x ushr 33)
    return ((x ushr 40) and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
}

/**
 * The light of the camp, added over everything near it: the fire's light on the clearing, rising
 * and falling with its flames, and at night the lantern's light in the tent's door.
 */
private fun DrawScope.drawLights(images: PaperImages, l: PaperLook, m: PaperMotion, f: FilterCache) {
    val b = images.baked
    val d = b.density
    val t = m.time
    if (l.fire > 0.005f) {
        val reach = l.fireReach * d
        // The glow's Gaussian falls to e^-½ at [reach]; the sprite's own at 0.395 of its radius.
        val w = 2f * reach / 0.395f
        val h = w * 0.68f
        val breath = 0.88f + 0.2f * Noise.wander(t, 0.6, 600).toFloat()
        drawImage(
            images.glow, dstOffset = IntOffset((b.fireX - w / 2).roundToInt(), (b.fireY - 10f * d - h / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()), colorFilter = f.tint(l.fireGlow), alpha = (l.fire * breath).coerceIn(0f, 1f),
            blendMode = BlendMode.Plus, filterQuality = FilterQuality.Low,
        )
    }
    if (l.lantern > 0.01f) {
        val w = LANTERN_DP * d
        val h = w * 0.85f
        drawImage(
            images.glow, dstOffset = IntOffset((b.doorX - w / 2).roundToInt(), (b.doorY - h / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()), colorFilter = f.tint(LANTERN),
            alpha = (0.85f * l.lantern * (0.9f + 0.16f * Noise.wander(t, 0.45, 610).toFloat())).coerceIn(0f, 1f),
            blendMode = BlendMode.Plus, filterQuality = FilterQuality.Low,
        )
    }
    drawSparks(m, b)
    if (l.fireflies > 0.01f) drawFireflies(images, l, m, f)
}

/**
 * Sparks rising from the fire: a dozen glowing grains at most. Each flies up a little way
 * (slowing, wavering, carried off by the wind) and goes out; a new one follows at its own
 * pace, never in step with the others.
 */
private fun DrawScope.drawSparks(m: PaperMotion, b: PaperBaked) {
    val d = b.density
    val t = m.time
    val wind = m.weather.wind
    for (j in 0 until SPARKS) {
        val period = 1.9 + 1.8 * unit(j, 81)
        val shifted = t + period * unit(j, 82)
        val cycle = floor(shifted / period).toInt()
        val age = (shifted - cycle * period).toFloat()
        val life = 0.9f + 1.0f * unit(j * 977 + cycle, 83)
        if (age > life) continue
        val v0 = 30f + 26f * unit(j * 977 + cycle, 84)
        val rise = v0 * age - 7f * age * age
        val x = b.fireX / d + (unit(j * 977 + cycle, 85) - 0.5f) * 12f + 3.5f * sin(age * (5f + 3f * unit(j, 86)) + j) - wind * 34f * age
        val y = b.fireY / d - 16f - rise
        val fade = Shapes.smoothstep(0f, 0.08f, age) * (1f - age / life).pow(1.6f)
        if (fade <= 0.01f) continue
        val color = if (age / life < 0.4f) SPARK_HOT else SPARK_COOL
        drawCircle(color, radius = (0.75f + 0.55f * unit(j, 87)) * d, center = Offset(x * d, y * d), alpha = fade, blendMode = BlendMode.Plus)
    }
}

/**
 * Fireflies on a summer night: a dozen points of greenish light wandering slowly over the
 * meadow and along the forest's edge, each lighting up now and then with a soft halo.
 */
private fun DrawScope.drawFireflies(images: PaperImages, l: PaperLook, m: PaperMotion, f: FilterCache) {
    val b = images.baked
    val d = b.density
    val t = m.time
    val tint = f.tint(FIREFLY)
    for (j in 0 until FIREFLIES) {
        val bx = 130f + 720f * unit(j, 71)
        val by = 392f + 58f * unit(j, 72)
        val x = bx + 26f * Noise.wander(t, 8.0 + 3.0 * unit(j, 73), 900 + j).toFloat()
        val y = by + 12f * Noise.wander(t, 6.5 + 2.0 * unit(j, 74), 950 + j).toFloat()
        val pulse = Shapes.smoothstep(0.45f, 0.95f, sin(2 * PI * t / (3.2 + 2.4 * unit(j, 75)) + 6.2832 * unit(j, 76)).toFloat())
        val a = l.fireflies * pulse
        if (a <= 0.01f) continue
        val w = FIREFLY_HALO_DP * d
        drawImage(
            images.glow, dstOffset = IntOffset((b.px(x) - w / 2).roundToInt(), (b.px(y) - w / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt(), w.roundToInt()), colorFilter = tint, alpha = (0.55f * a).coerceIn(0f, 1f),
            blendMode = BlendMode.Plus, filterQuality = FilterQuality.Low,
        )
        drawCircle(Color(FIREFLY), radius = 0.9f * d, center = Offset(b.px(x), b.px(y)), alpha = a.coerceIn(0f, 1f), blendMode = BlendMode.Plus)
    }
}

/**
 * The weather at one depth, moving with its layer: the valley's mist lying there (tissue bands,
 * drawn wide and faint), then the snowflakes and the rain's threads falling in front of it.
 */
private fun DrawScope.drawWeather(images: PaperImages, l: PaperLook, m: PaperMotion, f: FilterCache, fall: FallBatches, depth: Depth) {
    val b = images.baked
    val w = m.weather
    if (l.mistAlpha > 0.01f && depth != Depth.FRONT) {
        val filter = f.of(Inks.flat(l.mist))
        for (c in MistField.places(m.cloudTime, l.mistAlpha, depth, fall.mist)) {
            withTransform({
                translate(b.px(c.x), b.px(c.y))
                scale(c.scale * c.stretch, c.scale, pivot = Offset.Zero)
            }) {
                sheet(images.clouds[c.design], b.clouds[c.design], filter, c.alpha)
            }
        }
    }
    if (w.snow > 0.003f) {
        for (band in Weatherfall.SNOW) if (band.depth == depth) {
            val n = Weatherfall.place(band, m.time, m.drift, w.wind, w.snow, snow = true, fall.items)
            drawIntoCanvas { fall.flakes(it.nativeCanvas, n, b, l.snowInk, l.shadow, l.shadowAlpha) }
        }
    }
    if (w.rain > 0.003f) {
        for (band in Weatherfall.RAIN) if (band.depth == depth) {
            val n = Weatherfall.place(band, m.time, m.drift, w.wind, w.rain, snow = false, fall.items)
            drawIntoCanvas { fall.threads(it.nativeCanvas, n, b, l.rainInk, band) }
        }
    }
}

/**
 * Draws what falls in a few calls: the flakes as round points and the drops as lines, sorted
 * into a handful of sizes and strengths, one call per batch. Buffers are kept between frames.
 */
private class FallBatches {
    val items = FloatArray(Weatherfall.STRIDE * 200)
    val mist = ArrayList<CloudPlace>()
    private val sizes = 3
    private val strengths = 3
    private val buffers = Array(sizes * strengths) { FloatArray(400 * 2) }
    private val lines = Array(strengths) { FloatArray(200 * 4) }
    private val counts = IntArray(sizes * strengths)
    private val lineCounts = IntArray(strengths)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    /** The flakes: discs of white paper, each with a faint shadow below it (seen against a pale sky), by size and strength. */
    fun flakes(canvas: android.graphics.Canvas, n: Int, b: PaperBaked, ink: Int, shadow: Int, shadowAlpha: Float) {
        if (n == 0) return
        counts.fill(0)
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (i in 0 until n) {
            val s = items[i * Weatherfall.STRIDE + 2]
            lo = minOf(lo, s)
            hi = maxOf(hi, s)
        }
        for (i in 0 until n) {
            val o = i * Weatherfall.STRIDE
            val size = if (hi > lo) (((items[o + 2] - lo) / (hi - lo)) * sizes).toInt().coerceIn(0, sizes - 1) else 0
            val strength = (items[o + 3] * strengths).toInt().coerceIn(0, strengths - 1)
            val k = size * strengths + strength
            val buf = buffers[k]
            val c = counts[k]
            if (c * 2 + 1 >= buf.size) continue
            buf[c * 2] = b.px(items[o])
            buf[c * 2 + 1] = b.px(items[o + 1])
            counts[k] = c + 1
        }
        paint.style = android.graphics.Paint.Style.STROKE
        val drop = FLAKE_SHADOW_DP * b.density
        for (k in buffers.indices) {
            if (counts[k] == 0) continue
            val size = k / strengths
            val strength = k % strengths
            val r = lo + (hi - lo) * (size + 0.5f) / sizes
            val a = (strength + 1f) / strengths
            paint.strokeWidth = 2f * r * b.density
            paint.color = shadow
            paint.alpha = (255 * a * (0.18f + 0.4f * shadowAlpha)).toInt().coerceIn(0, 255)
            canvas.withTranslation(drop * 0.4f, drop) { drawPoints(buffers[k], 0, counts[k] * 2, paint) }
            paint.color = ink
            paint.alpha = (255 * a).toInt().coerceIn(0, 255)
            canvas.drawPoints(buffers[k], 0, counts[k] * 2, paint)
        }
    }

    /** The rain: fine threads, slanting with the wind, by strength. */
    fun threads(canvas: android.graphics.Canvas, n: Int, b: PaperBaked, ink: Int, band: Weatherfall.Band) {
        if (n == 0) return
        val lc = lineCounts
        lc.fill(0)
        for (i in 0 until n) {
            val o = i * Weatherfall.STRIDE
            val strength = (items[o + 3] * strengths).toInt().coerceIn(0, strengths - 1)
            val buf = lines[strength]
            val c = lc[strength]
            if (c * 4 + 3 >= buf.size) continue
            val len = items[o + 2]
            val slant = items[o + 4]
            // The head where it is, the tail behind it along the way it came.
            buf[c * 4] = b.px(items[o] - slant * len)
            buf[c * 4 + 1] = b.px(items[o + 1] - len)
            buf[c * 4 + 2] = b.px(items[o])
            buf[c * 4 + 3] = b.px(items[o + 1])
            lc[strength] = c + 1
        }
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = (0.7f + 0.03f * band.sizeMax) * b.density
        for (k in 0 until strengths) {
            if (lc[k] == 0) continue
            paint.color = ink
            paint.alpha = (255 * RAIN_ALPHA * (k + 1f) / strengths).toInt().coerceIn(0, 255)
            canvas.drawLines(lines[k], 0, lc[k] * 4, paint)
        }
    }
}

/** How strong the rain's threads are at most. They read as a light veil, not a solid curtain. */
private const val RAIN_ALPHA = 0.42f

/** How far below a flake its shadow falls (dp). */
private const val FLAKE_SHADOW_DP = 0.9f

/** How far the cut-outs rock (degrees): in a calm, and how much more in a gale; how far they lean into a gale. */
private const val SWAY_DEG = 1.1f
private const val SWAY_WIND_DEG = 3f
private const val LEAN_DEG = 2.5f

/** The tall firs sway this share of the meadow's cut-outs: a tall tree moves less at its foot. */
private const val FRONT_SWAY = 0.42f

/** How far the flames bend in a gale (degrees). */
private const val FLAME_WIND_DEG = 12f

/** The smoke's puffs, the sparks and the fireflies at once, at most. */
private const val SMOKE_PUFFS = 7
private const val SPARKS = 12
private const val FIREFLIES = 12

private val SPARK_HOT = Color(0xFFFFD27A)
private val SPARK_COOL = Color(0xFFFF8A3C)

/** The fireflies' light and the width of its halo (dp). */
private const val FIREFLY = 0xFFD9F27E.toInt()
private const val FIREFLY_HALO_DP = 14f

/** The lantern's light in the tent's door: its color and how wide it falls (dp). */
private const val LANTERN_DP = 40f
private const val LANTERN = 0xFFFFB45A.toInt()

private val STAR = Color(0xFFF3EEDF)

/** Where the horizon's glow lies (dp): along the far range's foot. */
private const val HORIZON_GLOW_Y = 300f
