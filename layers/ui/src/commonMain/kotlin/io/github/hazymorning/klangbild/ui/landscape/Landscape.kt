package io.github.hazymorning.klangbild.ui.landscape

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import io.github.hazymorning.klangbild.landscape.BakeProgress
import io.github.hazymorning.klangbild.landscape.CloudField
import io.github.hazymorning.klangbild.landscape.CloudPlace
import io.github.hazymorning.klangbild.landscape.LabelSide
import io.github.hazymorning.klangbild.landscape.LandscapeCamera
import io.github.hazymorning.klangbild.landscape.LandscapeClock
import io.github.hazymorning.klangbild.landscape.PaperBake
import io.github.hazymorning.klangbild.landscape.PaperLight
import io.github.hazymorning.klangbild.landscape.PaperLook
import io.github.hazymorning.klangbild.landscape.Place
import io.github.hazymorning.klangbild.landscape.SeasonShapes
import io.github.hazymorning.klangbild.landscape.SkyMap
import io.github.hazymorning.klangbild.landscape.SkyPlaces
import io.github.hazymorning.klangbild.landscape.SkyState
import io.github.hazymorning.klangbild.landscape.Weather
import io.github.hazymorning.klangbild.landscape.World
import io.github.hazymorning.klangbild.ui.LocalStageTick
import io.github.hazymorning.klangbild.ui.LocalStill
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.StageTick
import io.github.hazymorning.klangbild.ui.TICK_MS
import io.github.hazymorning.klangbild.ui.rememberOffMain
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock

/** What the landscape needs from the world outside: where it is, the weather, its clock. */
@Stable
class LandscapeFeed(
    val place: () -> Place,
    /** The weather the picture shows now (gliding toward the weather outside), or null for a fair sky. */
    val weather: () -> Weather?,
    val clock: LandscapeClock,
    val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    /** The wall clock (ms) for what moves by itself: the clouds, the fire, the stars. */
    val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
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
        val images = rememberOffMain(w, h, density, shapes, label, on = SHOWN) {
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
private val BAKER = threadOfItsOwn("landscape-bake", background = true)

/**
 * A thread of its own for the picture the screen waits for. A bake takes seconds, far longer on a
 * slow device, and neither it nor a wait for one under way may hold the app's shared threads: on
 * two cores that stalled everything else, the settings' writes included (45 s in the Android 9
 * emulator).
 */
private val SHOWN = threadOfItsOwn("landscape-shown", background = false)

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
    private val baking = SynchronizedObject()

    /** How far the bake under way has come: the paper is cut, then laid out as pictures. */
    @Volatile var progress = BakeProgress.NONE
        private set

    fun get(w: Int, h: Int, density: Float, shapes: SeasonShapes, label: LabelSide): PaperImages {
        val key = listOf(w, h, density, shapes, label)
        last?.takeIf { it.key == key }?.let { return it.images }
        synchronized(baking) {
            last?.takeIf { it.key == key }?.let { return it.images }
            val started = generation
            progress = BakeProgress.NONE
            val baked = PaperBake.bake(w, h, density, shapes, label) { progress = it.scaled(CUT_SHARE) }
            progress = BakeProgress(CUT_SHARE, 1f)
            val images = PaperImages(baked)
            progress = BakeProgress.DONE
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
