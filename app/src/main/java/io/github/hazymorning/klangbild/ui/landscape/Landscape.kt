package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.hazymorning.klangbild.ui.LocalStageTick
import io.github.hazymorning.klangbild.ui.LocalStill
import io.github.hazymorning.klangbild.ui.QuietColors
import io.github.hazymorning.klangbild.ui.StageTick
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.TICK_MS
import io.github.hazymorning.klangbild.ui.rememberOffMain
import io.github.hazymorning.klangbild.landscape.CloudField
import io.github.hazymorning.klangbild.landscape.CloudPlace
import io.github.hazymorning.klangbild.landscape.LabelSide
import io.github.hazymorning.klangbild.landscape.PaperBake
import io.github.hazymorning.klangbild.landscape.PaperLight
import io.github.hazymorning.klangbild.landscape.PaperLook
import io.github.hazymorning.klangbild.landscape.PaperMotif
import io.github.hazymorning.klangbild.landscape.Place
import io.github.hazymorning.klangbild.landscape.SeasonShapes
import io.github.hazymorning.klangbild.landscape.SkyMap
import io.github.hazymorning.klangbild.landscape.SkyPlaces
import io.github.hazymorning.klangbild.landscape.SkyState
import io.github.hazymorning.klangbild.landscape.LandscapeCamera
import io.github.hazymorning.klangbild.landscape.LandscapeClock
import io.github.hazymorning.klangbild.landscape.Weather
import io.github.hazymorning.klangbild.landscape.World
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** What the landscape needs from the world outside: where it is, the weather, its clock. */
@Stable
class LandscapeFeed(
    val place: () -> Place,
    /** The weather the picture shows now (gliding toward the weather outside), or null for a fair sky. */
    val weather: () -> Weather?,
    val clock: LandscapeClock,
    val zone: () -> ZoneId = ZoneId::systemDefault,
    /** The wall clock (ms) for what moves by itself: the clouds, the fire, the stars. */
    val wall: () -> Long = System::currentTimeMillis,
)

/**
 * The landscape: the paper diorama, its light following [feed]'s clock, its clouds and its
 * campfire moving with the wall clock. The picture is baked once for the screen, the season's
 * shapes and the bank cut wide for the [label]. When the season changes the shapes (snow,
 * leaves, blossom, every few days), a new bake fades in over [SEASON_FADE_MS]. [content] lies on
 * top (the music's label) and reads the colors of the moment.
 */
@Composable
fun Landscape(
    feed: LandscapeFeed,
    modifier: Modifier = Modifier,
    label: LabelSide = LabelSide.LEFT,
    content: @Composable BoxScope.(look: () -> PaperLook) -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val density = LocalDensity.current.density
        val still = LocalStill.current
        val state = remember(feed, label) { LandscapeFrameState(feed, label, still) }
        val frame = remember { mutableLongStateOf(0L) }
        // The light moves on only every few seconds: what is drawn from it alone waits for that.
        val light = remember { mutableIntStateOf(0) }
        var shapes by remember { mutableStateOf(state.shapesNow()) }
        if (!still) {
            LaunchedEffect(state) {
                while (true) {
                    withFrameNanos { ns ->
                        if (state.advance()) light.intValue++
                        frame.longValue = ns
                    }
                    // The season's shapes are looked at now and then; they change over days.
                    if (state.frames % SHAPES_EVERY == 0L) state.shapesNow().let { if (it != shapes) shapes = it }
                    // Everything here moves slowly, so twenty pictures a second are smooth. The
                    // time-lapse, with its fast-moving light, gets the stage's thirty.
                    delay(if (feed.clock.timelapse) TICK_MS else LANDSCAPE_TICK_MS)
                }
            }
        } else {
            state.advance()
        }
        val images = rememberOffMain(w, h, density, shapes, label) {
            if (w > 0 && h > 0) PaperCache.get(w, h, density, shapes, label) else null
        }
        // A new season's bake fades in over the old one.
        var shown by remember { mutableStateOf<PaperImages?>(null) }
        var leaving by remember { mutableStateOf<PaperImages?>(null) }
        val fade = remember { Animatable(1f) }
        // The whole landscape (picture and label) comes up out of the dark once baked.
        val appear = remember { Animatable(if (still) 1f else 0f) }
        LaunchedEffect(images) {
            val next = images ?: return@LaunchedEffect
            val old = shown
            if (still) return@LaunchedEffect
            if (old == null) {
                shown = next
                appear.animateTo(1f, tween(FIRST_FADE_MS, easing = Motion.Dimmer))
                return@LaunchedEffect
            }
            leaving = old
            shown = next
            fade.snapTo(0f)
            fade.animateTo(1f, tween(if (feed.clock.timelapse) TIMELAPSE_SEASON_FADE_MS else SEASON_FADE_MS, easing = Motion.Sine))
            leaving = null
        }
        val look: () -> PaperLook = { light.intValue; state.look }
        val places: () -> SkyPlaces = { light.intValue; state.places }
        val motion: () -> PaperMotion = { frame.longValue; state.motion }
        // A still shows the bake at once, as it shows everything else (no effect has to run first).
        val current = if (still) images else shown
        val outgoing = if (still) null else leaving
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }) {
            outgoing?.let { PaperView(it, look, places, motion, Modifier.fillMaxSize()) }
            current?.let { s ->
                PaperView(s, look, places, motion, Modifier.fillMaxSize().graphicsLayer { alpha = if (outgoing != null) fade.value else 1f })
            }
            // The label stands still because it is not part of the scenery; the camera keeps its bank under it.
            // Its bar is redrawn in the scenery's frames: one loop, not two out of step.
            val tick = remember(frame) { StageTick(frame) }
            if (current != null) {
                CompositionLocalProvider(LocalStageTick provides tick) {
                    Box(Modifier.fillMaxSize()) { content(look) }
                }
            }
        }
        if (!still) LandscapeWaiting(ready = current != null, progress = { PaperCache.progress })
    }
}

/**
 * The sign that the landscape is on its way while its first picture is baked (a few seconds,
 * more at 4K): the far range's own skyline, drawn as one thin line in the screen's middle, the
 * way the progress bar is drawn (round ends, the track at 16 % in the rest screen's quiet ink).
 * The line is traced from the left as the paper is cut ([progress], 0 to 1), gliding and never
 * going back, its head breathing softly; when the picture is ready it is drawn to the end and
 * gone while the landscape comes up. It comes only when the picture keeps one waiting, so a
 * picture baked ahead comes up without it.
 */
@Composable
internal fun LandscapeWaiting(ready: Boolean, progress: () -> Float = { 0f }) {
    val still = LocalStill.current
    val alpha = remember { Animatable(if (still) 1f else 0f) }
    var gone by remember { mutableStateOf(ready) }
    // How much of the line is drawn: it glides toward the bake's progress and never goes back.
    val drawn = remember { mutableFloatStateOf(if (still) progress() else 0f) }
    if (!still) {
        LaunchedEffect(ready) {
            if (ready) {
                alpha.animateTo(0f, tween(WAIT_OUT_MS, easing = Motion.Sine))
                gone = true
            } else {
                gone = false
                delay(WAIT_AFTER_MS)
                alpha.animateTo(1f, tween(WAIT_IN_MS, easing = Motion.Sine))
            }
        }
    }
    if (gone) return
    if (!still) {
        val isReady by rememberUpdatedState(ready)
        val baked by rememberUpdatedState(progress)
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { ns ->
                    val dt = if (last == 0L) 0f else (ns - last) / 1e9f
                    last = ns
                    val target = if (isReady) 1f else baked()
                    val d = drawn.floatValue
                    if (target > d) drawn.floatValue = d + (target - d) * (1f - exp(-dt / (if (isReady) WAIT_FINISH_S else WAIT_GLIDE_S)))
                }
            }
        }
    }
    val breath: () -> Float = if (still) {
        { 1f }
    } else {
        val b = rememberInfiniteTransition(label = "waiting")
            .animateFloat(1f, WAIT_BREATH_LOW, infiniteRepeatable(tween(WAIT_BREATH_MS, easing = Motion.Dimmer), RepeatMode.Reverse), label = "breath")
        ({ b.value })
    }
    val ink = remember { QuietColors.of().secondary }
    val skyline = remember { WaitingSkyline() }
    Canvas(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
        val w = WAIT_WIDTH_DP.dp.toPx()
        val path = skyline.path(w, center)
        val line = Stroke(WAIT_LINE_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(path, ink.copy(alpha = 0.16f), style = line)
        val share = drawn.floatValue.coerceIn(0f, 1f)
        if (share <= 0f) return@Canvas
        val measure = PathMeasure().apply { setPath(path, false) }
        val traced = Path()
        measure.getSegment(0f, measure.length * share, traced, true)
        drawPath(traced, ink, style = line)
        // The head of the line breathes, a soft light where the paper is being cut.
        val head = measure.getPosition(measure.length * share)
        val r = WAIT_GLOW_DP.dp.toPx()
        drawCircle(
            Brush.radialGradient(listOf(ink.copy(alpha = 0.32f * breath()), ink.copy(alpha = 0f)), head, r),
            r,
            head,
        )
    }
}

/**
 * The far range's skyline ([PaperMotif.skyline]) from foot to foot, as a line [width] wide
 * centered on [center]: the mountains the landscape then shows.
 */
private class WaitingSkyline {
    private val points: List<Pair<Float, Float>> = (0..SAMPLES).map { i ->
        val x = FROM + (TO - FROM) * i / SAMPLES
        x to PaperMotif.skyline(x)
    }
    private val top = points.minOf { it.second }
    private var cached: Triple<Float, Offset, Path>? = null

    fun path(width: Float, center: Offset): Path {
        cached?.takeIf { it.first == width && it.second == center }?.let { return it.third }
        val scale = width / (TO - FROM)
        val height = (PaperMotif.FAR_FOOT - top) * scale
        val p = Path()
        points.forEachIndexed { i, (x, y) ->
            val px = center.x - width / 2f + (x - FROM) * scale
            val py = center.y + height / 2f - (PaperMotif.FAR_FOOT - y) * scale
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        cached = Triple(width, center, p)
        return p
    }

    private companion object {
        /** From the first mountain's foot to the last one's (dp in the motif), finely enough for a smooth line. */
        const val FROM = -80f
        const val TO = 1060f
        const val SAMPLES = 228
    }
}

/**
 * Bakes the landscape's picture ahead, while it is only on its way (the music paused, the
 * delay running), so that it can come out of the dark at once. It bakes in the background, at
 * low priority, a while after the app has settled, and again when the season has moved on.
 */
@Composable
fun LandscapeAhead(feed: LandscapeFeed, label: LabelSide) {
    if (LocalStill.current) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val density = LocalDensity.current.density
        var shapes by remember { mutableStateOf<SeasonShapes?>(null) }
        LaunchedEffect(feed) {
            val state = LandscapeFrameState(feed)
            while (true) {
                shapes = state.shapesNow()
                delay(AHEAD_RECHECK_MS)
            }
        }
        val s = shapes ?: return@BoxWithConstraints
        LaunchedEffect(w, h, density, s, label) {
            if (w <= 0 || h <= 0) return@LaunchedEffect
            delay(AHEAD_DELAY_MS)
            withContext(BAKER) { PaperCache.get(w, h, density, s, label) }
        }
    }
}

/** One background thread of low priority for baking ahead: the stage keeps its frames. */
private val BAKER = Executors.newSingleThreadExecutor { r ->
    Thread({
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }, "landscape-bake")
}.asCoroutineDispatcher()

/** Baking ahead waits this long after the app or the setting has settled, and looks at the season this often. */
private const val AHEAD_DELAY_MS = 8_000L
private const val AHEAD_RECHECK_MS = 3_600_000L

/**
 * The last bake, kept while the landscape shows or is on its way (most often the same screen
 * and season). [releaseBakedLandscape] lets it go when the landscape is no longer coming and when
 * the app goes to the background.
 */
private object PaperCache {
    private class Entry(val key: List<Any>, val images: PaperImages)

    @Volatile private var last: Entry? = null

    /** Counts the clears: a bake that was under way during one is not kept. */
    @Volatile private var generation = 0
    private val baking = Any()

    /** How far the bake under way has come (0 to 1): the paper is cut, then laid out as pictures. */
    @Volatile var progress = 0f
        private set

    fun get(w: Int, h: Int, density: Float, shapes: SeasonShapes, label: LabelSide): PaperImages {
        val key = listOf(w, h, density, shapes, label)
        last?.takeIf { it.key == key }?.let { return it.images }
        synchronized(baking) {
            last?.takeIf { it.key == key }?.let { return it.images }
            val started = generation
            progress = 0f
            val baked = PaperBake.bake(w, h, density, shapes, label) { progress = it * CUT_SHARE }
            val images = PaperImages(baked)
            progress = 1f
            if (started == generation) last = Entry(key, images)
            return images
        }
    }

    /** The share of a bake the cutting takes; the rest is laying it out as pictures. */
    private const val CUT_SHARE = 0.9f

    /** Forgets the last bake at once, without waiting for one under way. */
    fun clear() {
        generation++
        last = null
    }
}

/** Lets the landscape's last picture go (tens of megabytes at 4K): it is baked again when it comes. */
fun releaseBakedLandscape() = PaperCache.clear()

/** How often (frames) the season's shapes are checked: every few seconds. */
private const val SHAPES_EVERY = 60L

/** The picture's tick (ms): about twenty frames a second. */
const val LANDSCAPE_TICK_MS = 50L

/** The first picture fades in this long once it is baked. */
private const val FIRST_FADE_MS = 1_200

/**
 * The sign that the picture is on its way comes after this long (the screen switch keeps the
 * landscape dark for its first 900 ms anyway), fades in and out this long; its line glides
 * toward the bake's progress with this time constant (s), quicker to its end once the picture
 * is ready, and its head breathes once in [WAIT_BREATH_MS] (as the stage does while it buffers).
 */
private const val WAIT_AFTER_MS = 1_000L
private const val WAIT_IN_MS = 600
private const val WAIT_OUT_MS = 450
private const val WAIT_GLIDE_S = 0.35f
private const val WAIT_FINISH_S = 0.08f
private const val WAIT_BREATH_MS = 1_600
private const val WAIT_BREATH_LOW = 0.45f

/** The skyline: its width and line (dp), and the light around its head (radius, dp). */
private const val WAIT_WIDTH_DP = 200f
private const val WAIT_LINE_DP = 2.5f
private const val WAIT_GLOW_DP = 9f

/** A new season's shapes fade in this slowly (a day's change, seen by nobody), and quicker in the time-lapse. */
private const val SEASON_FADE_MS = 20_000
private const val TIMELAPSE_SEASON_FADE_MS = 1_500

/**
 * What the picture shows at this frame, worked out once per frame (or less): the colors, the
 * sun's and moon's places, the clouds. The colors are only worked out anew when the sky has
 * moved on by [LOOK_EVERY_MS] of its own time (each step far below a visible level), except
 * in the time-lapse, where every frame counts.
 */
private class LandscapeFrameState(private val feed: LandscapeFeed, private val label: LabelSide = LabelSide.NONE, private val still: Boolean = false) {
    var frames = 0L
        private set
    var look: PaperLook = PaperLight.look(SkyState(30f, 180f))
        private set
    var places: SkyPlaces = SkyMap.places(SkyState(30f, 180f))
        private set
    var motion: PaperMotion = PaperMotion(0.0, emptyList())
        private set

    private var lookSky = Long.MIN_VALUE
    private var lookWeather: Weather? = null
    private var cloudTime = 0.0
    private var drift = 0.0
    private var lastWall = 0L
    private val clouds = ArrayList<CloudPlace>()

    fun shapesNow(): SeasonShapes {
        val sky = feed.clock.sky()
        val place = feed.place()
        return quantised(World.season(sky, feed.zone(), place.latitude).second)
    }

    /** Moves on to this frame; true when the light (colors, sun, moon) was worked out anew. */
    fun advance(): Boolean {
        frames++
        val wall = feed.wall()
        val sky = feed.clock.sky()
        val w = feed.weather() ?: Weather.FAIR
        val dt = if (lastWall == 0L) -1.0 else (wall - lastWall).coerceAtLeast(0L) / 1000.0
        lastWall = wall
        val seconds = (wall - MOTION_EPOCH_MS) / 1000.0
        // The clouds' own clock runs faster in the wind; a gust speeds them up, it never moves them.
        cloudTime = if (dt < 0.0) seconds * CloudField.speed(w.wind) else cloudTime + dt * CloudField.speed(w.wind)
        drift = if (dt < 0.0) seconds * w.wind else drift + dt * w.wind
        val moving = feed.clock.timelapse
        val relit = moving || abs(sky - lookSky) >= LOOK_EVERY_MS || lookWeather?.let { it.distance(w) > Weather.LIGHT_STEP } != false
        if (relit) {
            lookSky = sky
            lookWeather = w
            val (s, heavens) = World.sky(sky, feed.place(), feed.zone(), w)
            look = PaperLight.look(s)
            places = SkyMap.places(s, heavens.siderealDeg, heavens.latitude)
        }
        // A still shows the camera at rest: the whole picture.
        val camera = if (still) LandscapeCamera.REST else LandscapeCamera.at(seconds, label)
        motion = PaperMotion(seconds, ArrayList(CloudField.places(cloudTime, CloudField.density(w.cover), clouds)), camera, w, cloudTime, drift)
        return relit
    }

    /** The shapes to bake. Blossom only colors the crowns (the light handles it), so it never asks for a new bake. */
    private fun quantised(s: SeasonShapes) = SeasonShapes(q(s.snow), q(s.peaks), q(s.leaves), flowers = q(s.flowers), fallen = q(s.fallen))

    /** Steps of 1/16: a new bake only when a shape has visibly changed. */
    private fun q(v: Float) = (v * 16f).roundToInt() / 16f

    companion object {
        /** The colors are worked out anew after this much of the sky's own time. */
        const val LOOK_EVERY_MS = 10_000L

        /** Origin of the clock for what moves by itself: January 1, 2026 (keeps the numbers small). */
        const val MOTION_EPOCH_MS = 1_767_225_600_000L
    }
}
