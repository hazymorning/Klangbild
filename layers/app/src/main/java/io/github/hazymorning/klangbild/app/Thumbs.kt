package io.github.hazymorning.klangbild.app

import android.graphics.Bitmap
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.SceneBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The small covers of the queue's list, made off the main thread at their exact size (Lanczos,
 * as the cover) and kept for the songs to come: the rows further down are made ahead, so a list
 * that moves up has them at once. A song without a cover gets its generated figure, as the stage
 * does. They are kept by picture, so the songs of an album share one. One is loaded at a time,
 * so the stage's own pictures never wait behind them; a cover that took too long stands in as
 * its figure for a while and is then asked for again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Thumbs(
    private val scope: CoroutineScope,
    private val artwork: ArtworkLoader,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val lane = Mutex()

    /** Figures standing in for a cover that took too long, and since when. */
    private val standIns = HashMap<String, Long>()

    private val cache = object : LinkedHashMap<String, Bitmap>(KEEP + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            if (size <= KEEP) return false
            eldest?.let { standIns.remove(it.key) }
            return true
        }
    }
    private val building = HashMap<String, Job>()

    /** The side length of a small cover (px). A new length (another screen) drops every one made so far. */
    @Volatile var px = 0
        set(value) {
            if (value != field) clear()
            field = value
        }

    /** [track]'s small cover, if it is made. */
    fun ready(track: TrackInfo): Bitmap? = synchronized(cache) { cache[keyOf(track)] }

    /**
     * Starts making [track]'s small cover unless it is made or on its way (a figure that stands
     * in is tried again after [RETRY_MS]); [done] runs once it is.
     */
    fun prepare(track: TrackInfo, done: () -> Unit) {
        val side = px
        if (side <= 0) return
        val key = keyOf(track)
        synchronized(cache) {
            if (key in building) return
            if (key in cache && standIns[key]?.let { now() - it >= RETRY_MS } != true) return
            building[key] = scope.launch { make(track, key, side, done) }
        }
    }

    /** Lets go of the small covers still on their way for any song but [tracks]. */
    fun retain(tracks: Collection<TrackInfo>) {
        val keep = tracks.mapTo(HashSet()) { keyOf(it) }
        synchronized(cache) {
            val gone = building.keys.filter { it !in keep }
            for (key in gone) building.remove(key)?.cancel()
        }
    }

    /** Lets go of every small cover (nothing shows them any more, or memory is short). */
    fun clear() {
        synchronized(cache) {
            building.values.forEach { it.cancel() }
            building.clear()
            cache.clear()
            standIns.clear()
        }
    }

    private class Loaded(val cover: Bitmap?)

    private suspend fun make(track: TrackInfo, key: String, side: Int, done: () -> Unit) {
        val me = currentCoroutineContext()[Job]
        var late = false
        val bitmap = try {
            // A few seconds for the cover once its turn has come; then the generated figure steps in.
            val loaded = lane.withLock {
                withTimeoutOrNull(Scenes.COVER_TIMEOUT_MS) { Loaded(runCatching { artwork.load(track) }.getOrNull()) }
            }
            late = loaded == null
            withContext(dispatcher) { SceneBuilder.rowCover(loaded?.cover, track.artSeed, side) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        synchronized(cache) {
            if (building[key] === me) building.remove(key)
            if (bitmap == null || side != px) return
            // A figure never replaces a cover that came before, nor the same figure standing in.
            if (late && key in cache) {
                if (key in standIns) standIns[key] = now()
                return
            }
            cache[key] = bitmap
            if (late) standIns[key] = now() else standIns.remove(key)
        }
        done()
    }

    companion object {
        /** Small covers kept: the list's rows and a few more. */
        const val KEEP = 16

        /** A cover that took too long is asked for again after this long (ms). */
        const val RETRY_MS = 60_000L

        /** Which picture [track] shows: its address, else its generated figure's seed. */
        fun keyOf(track: TrackInfo): String = track.artworkId ?: "seed|${track.artSeed}"
    }
}
