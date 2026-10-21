package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.SceneBuilder
import io.github.hazymorning.klangbild.render.ScreenSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val building = HashMap<String, Job>()

    @Volatile var frame = Frame(ScreenSpec.TV_1080, flow = false)
        set(value) {
            if (value != field) synchronized(cache) { cache.clear() }
            field = value
        }

    /** How many scenes are kept: the cover flow needs a whole strip of them. */
    private val capacity get() = if (frame.flow) 10 else 3

    /** The scene for [track] if it is ready and lit as [look]. */
    fun ready(track: TrackInfo, look: Look): ArtworkScene? =
        synchronized(cache) { cache[track.sceneKey] }?.takeIf { look.fits(it) }

    /** Whether [track]'s scene is on its way. */
    fun pending(track: TrackInfo): Boolean = synchronized(cache) { track.sceneKey in building }

    /**
     * Starts making [track]'s scene for [look] unless it is ready or on its way: re-lit if an
     * older one exists, otherwise built from the cover. [done] runs with the result.
     */
    fun prepare(track: TrackInfo, look: Look, done: (ArtworkScene) -> Unit = {}) {
        val key = track.sceneKey
        val forFrame = frame
        synchronized(cache) {
            if (cache[key]?.let(look::fits) == true || key in building) return
            val old = cache[key]
            building[key] = scope.launch { make(track, key, old, look, forFrame, done) }
        }
    }

    /** Lets go of every scene (nothing is shown any more, or memory is short). */
    fun clear() {
        synchronized(cache) {
            building.values.forEach { it.cancel() }
            building.clear()
            cache.clear()
        }
    }

    /** Stops building scenes that are no longer wanted (skipped past quickly), by [TrackInfo.sceneKey]. */
    fun retain(keys: Set<String>) {
        synchronized(cache) {
            val stale = building.filterKeys { it !in keys }
            stale.values.forEach { it.cancel() }
            building.keys.removeAll(stale.keys)
        }
    }

    private suspend fun make(track: TrackInfo, key: String, old: ArtworkScene?, look: Look, forFrame: Frame, done: (ArtworkScene) -> Unit) {
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
        synchronized(cache) {
            building.remove(key)
            if (scene == null || frame != forFrame) return
            cache.remove(key)
            cache[key] = scene
            while (cache.size > capacity) cache.remove(cache.keys.first())
        }
        done(scene!!)
    }

    private suspend fun build(track: TrackInfo, look: Look, frame: Frame, withCover: Boolean = true): ArtworkScene {
        // Wait at most a few seconds for the cover; then the Chladni figure steps in.
        val bitmap = if (!withCover) null else withTimeoutOrNull(COVER_TIMEOUT_MS) { runCatching { artwork.load(track) }.getOrNull() }
        return withContext(dispatcher) {
            SceneBuilder.build(
                key = track.key,
                source = bitmap,
                artSeed = track.artSeed,
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
