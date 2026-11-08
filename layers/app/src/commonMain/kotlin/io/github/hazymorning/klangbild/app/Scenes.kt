package io.github.hazymorning.klangbild.app

import androidx.compose.ui.graphics.ImageBitmap
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.data.Log
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.SceneBuilder
import io.github.hazymorning.klangbild.render.ScreenSpec
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Clock

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
 *
 * A track whose picture did not come in time (or at all, or had no address yet) gets the
 * generated cover, which only stands in: the picture is asked for again as soon as the track
 * names another address for it, and otherwise after [RETRY_MS]. Once it comes, its scene takes
 * the stand-in's place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Scenes(
    private val scope: CoroutineScope,
    private val artwork: ArtworkLoader,
    private val log: Log,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val cache = LinkedHashMap<String, ArtworkScene>()
    private val lock = SynchronizedObject()
    private val building = HashMap<String, Job>()

    /** A generated cover standing in for a picture: the picture's address when it was made ([TrackInfo.artworkId]), and when. */
    private class StandIn(val pictureId: String?, val at: Long)

    /** The stand-ins among the scenes, by [TrackInfo.sceneKey]. */
    private val standIns = HashMap<String, StandIn>()

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
            standIns.keys.retainAll(setOfNotNull(back?.second, aside?.second))
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
     * older one exists, otherwise built from the cover. A stand-in asks for the picture again
     * when it is due ([pictureDue]). [done] runs with the result.
     */
    fun prepare(track: TrackInfo, look: Look, done: (ArtworkScene) -> Unit = {}) {
        val key = track.sceneKey
        synchronized(lock) {
            if (key in building) return
            val old = cache[key]
            val again = old != null && pictureDue(key, track)
            if (old != null && look.fits(old) && !again) return
            val forFrame = frame
            building[key] = scope.launch { make(track, key, old.takeUnless { again }, look, forFrame, done) }
        }
    }

    /**
     * Whether the generated cover standing in for [track]'s picture asks for it again: the track
     * names another address than the stand-in was made for, or [RETRY_MS] have passed. Called
     * under the lock.
     */
    private fun pictureDue(key: String, track: TrackInfo): Boolean {
        val wanted = track.artworkId ?: return false
        val standIn = standIns[key] ?: return false
        return standIn.pictureId != wanted || now() - standIn.at >= RETRY_MS
    }

    /** Lets go of every scene (nothing is shown any more, or memory is short). */
    fun clear() {
        synchronized(lock) {
            building.values.forEach { it.cancel() }
            building.clear()
            cache.clear()
            standIns.clear()
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
                // Wait at most a few seconds for the cover; then the generated one steps in.
                val cover = withTimeoutOrNull(COVER_TIMEOUT_MS) { artwork.load(track) }
                if (cover == null && keepStandIn(key, track, look, me)) return
                build(track, look, forFrame, cover)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A broken image, out of memory and the like: the generated figure always works.
            log.warn("Scene: the generated cover stands in (${e::class.simpleName})")
            runCatching { build(track, look, forFrame, cover = null) }.getOrNull()
        }
        synchronized(lock) {
            // Only its own place: the same song's scene for another frame may be on its way by now.
            if (building[key] === me) building.remove(key)
            if (scene == null || frame != forFrame) return
            // Re-lit, a scene stays what it was; built anew, it is the picture or stands in for it.
            if (old == null) {
                if (scene.coverGenerated) standIns[key] = StandIn(track.artworkId, now()) else standIns.remove(key)
            }
            cache.remove(key)
            cache[key] = scene
            while (cache.size > capacity) cache.keys.first().let { cache.remove(it); standIns.remove(it) }
        }
        done(scene!!)
    }

    /**
     * Whether the stand-in for [track] stays as it is, since its picture still did not come: it
     * stays while it is lit as [look], and tries again after [RETRY_MS]. Nothing is built then.
     */
    private fun keepStandIn(key: String, track: TrackInfo, look: Look, me: Job?): Boolean = synchronized(lock) {
        if (key !in standIns || cache[key]?.let(look::fits) != true) return false
        if (building[key] === me) building.remove(key)
        standIns[key] = StandIn(track.artworkId, now())
        true
    }

    private suspend fun build(track: TrackInfo, look: Look, frame: Frame, cover: ImageBitmap?): ArtworkScene = withContext(dispatcher) {
        SceneBuilder.build(
            key = track.key,
            source = cover,
            artFoil = track.artFoil,
            artView = track.artView,
            screen = frame.screen,
            appearance = look.appearance,
            wallTint = look.wallTint,
            flow = frame.flow,
            coverField = look.coverField,
        )
    }

    companion object {
        const val COVER_TIMEOUT_MS = 6_000L

        /** A generated cover standing in for a picture asks for it again after this long. */
        const val RETRY_MS = 15_000L
    }
}
