package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.SceneBuilder
import io.github.hazymorning.klangbild.render.ScreenSpec
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * How scenes are lit: the wall, how much color a light wall takes (the light level, which also
 * sets how colorful the cover's colors are), and whether the cover's colors are the wall
 * ([coverField]).
 */
data class Look(val appearance: Appearance, val wallTint: Float, val coverField: Boolean = false) {
    fun fits(scene: ArtworkScene) =
        scene.appearance == appearance &&
            scene.coverField == coverField &&
            (appearance == Appearance.DARK && !coverField || scene.wallTint == wallTint)
}

/** What scenes are built for: the screen, and Cover Flow's grid or the cover's own. Another frame means new scenes. */
data class Frame(val screen: ScreenSpec, val flow: Boolean)

/**
 * Prepares the stage's scenes (cover, palette, light; see [SceneBuilder]) off the main thread,
 * one at a time, and keeps the most recent ones: a scene for a track that comes back (the next
 * song, a step back, the cards of the cover flow) is there at once. A scene whose light no
 * longer fits is re-lit from what it has, without loading the cover again. Scenes are kept by
 * [TrackInfo.sceneKey]: a station's song gets a new scene when its own picture arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Scenes(
    private val scope: CoroutineScope,
    private val artwork: ArtworkLoader,
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val cache = LinkedHashMap<String, ArtworkScene>()
    private val lock = SynchronizedObject()
    private val building = HashMap<String, Job>()

    /** What scenes are built for now ([show]). */
    @Volatile var frame = Frame(ScreenSpec.TV_1080, flow = false)
        private set

    /** The scene of the song on stage in the frame left last, with its key, waiting for a way back. */
    private var aside: Triple<Frame, String, ArtworkScene>? = null

    /**
     * Builds scenes for [value] from now on. Another frame lets go of every scene, and of those
     * still being made for the frame left: a switch to Cover Flow and straight back never waits
     * for a scene nobody will show, and its own scene starts at once. Only the scene of [keep]
     * (the song on stage, by its [TrackInfo.sceneKey]) is set aside, so a way straight back finds
     * it at once.
     */
    fun show(value: Frame, keep: String? = null) {
        synchronized(lock) {
            if (value == frame) return
            building.values.forEach { it.cancel() }
            building.clear()
            val back = aside?.takeIf { it.first == value }
            aside = keep?.let { key -> cache[key]?.let { Triple(frame, key, it) } }
            cache.clear()
            back?.let { (_, key, scene) -> cache[key] = scene }
            frame = value
        }
    }

    /** How many scenes are kept: the cover flow needs a whole strip of them. */
    private val capacity get() = if (frame.flow) 10 else 3

    /** The scene for [track] if it is ready and lit as [look]. */
    fun ready(track: TrackInfo, look: Look): ArtworkScene? =
        synchronized(lock) { cache[track.sceneKey] }?.takeIf { look.fits(it) }

    /** Whether [track]'s scene is on its way. */
    fun pending(track: TrackInfo): Boolean = synchronized(lock) { track.sceneKey in building }

    /**
     * Starts making [track]'s scene for [look] unless it is ready or on its way: re-lit if an
     * older one exists, otherwise built from the cover. [done] runs with the result.
     */
    fun prepare(track: TrackInfo, look: Look, done: (ArtworkScene) -> Unit = {}) {
        val key = track.sceneKey
        synchronized(lock) {
            if (cache[key]?.let(look::fits) == true || key in building) return
            val old = cache[key]
            val forFrame = frame
            building[key] = scope.launch { make(track, key, old, look, forFrame, done) }
        }
    }

    /** Lets go of every scene (nothing is shown any more, or memory is short). */
    fun clear() {
        synchronized(lock) {
            building.values.forEach { it.cancel() }
            building.clear()
            cache.clear()
            aside = null
        }
    }

    /** Stops building scenes that are no longer wanted (skipped past quickly), by [TrackInfo.sceneKey]. */
    fun retain(keys: Set<String>) {
        synchronized(lock) {
            val stale = building.filterKeys { it !in keys }
            stale.values.forEach { it.cancel() }
            building.keys.removeAll(stale.keys)
        }
    }

    private suspend fun make(track: TrackInfo, key: String, old: ArtworkScene?, look: Look, forFrame: Frame, done: (ArtworkScene) -> Unit) {
        val me = currentCoroutineContext()[Job]
        val scene = try {
            if (old != null) {
                withContext(dispatcher) { SceneBuilder.relight(old, look.appearance, look.wallTint, look.coverField) }
            } else {
                build(track, look, forFrame)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A broken image, out of memory and the like: the generated figure always works.
            runCatching { withContext(dispatcher) { build(track, look, forFrame, withCover = false) } }.getOrNull()
        }
        synchronized(lock) {
            // Only its own place: the same song's scene for another frame may be on its way by now.
            if (building[key] === me) building.remove(key)
            if (scene == null || frame != forFrame) return
            cache.remove(key)
            cache[key] = scene
            while (cache.size > capacity) cache.remove(cache.keys.first())
        }
        done(scene!!)
    }

    private suspend fun build(track: TrackInfo, look: Look, frame: Frame, withCover: Boolean = true): ArtworkScene {
        // Wait at most a few seconds for the cover; then the generated one steps in.
        val bitmap = if (!withCover) null else withTimeoutOrNull(COVER_TIMEOUT_MS) { runCatching { artwork.load(track) }.getOrNull() }
        return withContext(dispatcher) {
            SceneBuilder.build(
                key = track.key,
                source = bitmap,
                artFoil = track.artFoil,
                artView = track.artView,
                screen = frame.screen,
                appearance = look.appearance,
                wallTint = look.wallTint,
                flow = frame.flow,
                coverField = look.coverField,
            )
        }
    }

    companion object {
        const val COVER_TIMEOUT_MS = 6_000L
    }
}
