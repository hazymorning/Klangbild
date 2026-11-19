package io.github.hazymorning.klangbild.data.ma

import io.github.hazymorning.klangbild.domain.ArtistBio
import io.github.hazymorning.klangbild.json.string
import io.github.hazymorning.klangbild.math.RecentMap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** The first artist of a song, as Music Assistant names it in the song: where to ask about them. */
data class ArtistRef(val itemId: String, val provider: String) {
    /** The artist across songs. */
    val key: String get() = "$provider/$itemId"

    companion object {
        /** The first artist of [song], or null when it names none. */
        fun of(song: QueueSong): ArtistRef? {
            val artist = (song.item["artists"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val id = artist.string("item_id")?.takeIf { it.isNotBlank() } ?: return null
            val provider = artist.string("provider")?.takeIf { it.isNotBlank() } ?: return null
            return ArtistRef(id, provider)
        }
    }
}

/**
 * What Music Assistant keeps about the artists of the song that plays and of the one up next:
 * the opening of their Wikipedia article, which its Wikipedia provider writes in Music
 * Assistant's language (`metadata.description`). It is asked about one artist at a time
 * ([MaClient.artist]), the one of the song that plays first, and what is known is kept by the
 * artist. Reading an artist from the library also has Music Assistant look for a text it still
 * lacks, so an artist without one is asked again a while later, a few times. An artist Music
 * Assistant only has from a streaming service brings what that service says, often nothing.
 */
class MaBios(
    private val client: MaClient,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Something new is known about an artist of a song that plays or comes next. */
    private val changed: () -> Unit,
) {
    /**
     * What is known about an artist: their [bio], or null for none. Asked [asks] times, the
     * last at [askedAt]; a call that [failed] is made again much later.
     */
    class Known(val bio: ArtistBio?, internal val askedAt: Long, internal val asks: Int, internal val failed: Boolean = false)

    private val known = RecentMap<String, Known>(KEEP)
    private val lock = SynchronizedObject()

    /** The artists to ask about, the one of the song that plays first. */
    private var wanted: List<ArtistRef> = emptyList()

    /** The call that is out, and the artist it is for. */
    private var job: Job? = null
    private var asking: String? = null

    /** What is known about the artist of [song]; null while nothing is. */
    fun known(song: QueueSong): Known? = ArtistRef.of(song)?.let { a -> synchronized(lock) { known[a.key] } }

    /** The songs whose artists are wanted now: the one that plays, then the one up next. */
    fun want(songs: List<QueueSong>): Unit = synchronized(lock) {
        val t = now()
        wanted = songs.mapNotNull { ArtistRef.of(it) }.distinctBy { it.key }.filter { a ->
            val k = known[a.key]
            when {
                k == null -> true
                k.failed -> t - k.askedAt >= RETRY_MS
                // Music Assistant may still be looking it up: asked again a little later, a few times.
                k.bio == null -> k.asks < ASKS && t - k.askedAt >= AGAIN_MS
                else -> false
            }
        }
        if (asking != null && wanted.none { it.key == asking }) {
            job?.cancel()
            job = null
            asking = null
        }
        askNext()
    }

    /** Stops asking (the connection ends). */
    fun stop(): Unit = synchronized(lock) {
        job?.cancel()
        job = null
        asking = null
        wanted = emptyList()
    }

    /**
     * Asks about the first artist still wanted, unless a call is out (called under the lock). The
     * call is known as [job] before it starts, so even an answer that comes at once finds it.
     */
    private fun askNext() {
        if (job != null) return
        val artist = wanted.firstOrNull() ?: return
        asking = artist.key
        val before = known[artist.key]
        val call = scope.launch(start = CoroutineStart.LAZY) {
            val asks = (before?.asks ?: 0) + 1
            val answer = try {
                val item = client.artist(artist.itemId, artist.provider)
                val text = (item?.get("metadata") as? JsonObject)?.string("description")
                Known(ArtistBio.of(text), now(), asks)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Known(before?.bio, now(), asks, failed = true)
            }
            val me = coroutineContext.job
            val news = synchronized(lock) {
                // Canceled after the answer came (its artist is no longer wanted, or asking stopped): dropped.
                if (job !== me) return@launch
                job = null
                asking = null
                val still = wanted.any { it.key == artist.key }
                if (still) known[artist.key] = answer
                wanted = wanted.filter { it.key != artist.key }
                askNext()
                still && (before == null || before.bio !== answer.bio)
            }
            if (news) changed()
        }
        job = call
        call.start()
    }

    companion object {
        /** The artists whose bios are kept: far more than play in an evening's queue around the one on stage. */
        const val KEEP = 64

        /** An artist without a text is asked again after this long, should a song of theirs come again. */
        const val AGAIN_MS = 2 * 60_000L

        /** How often an artist without a text is asked, the first time included. */
        const val ASKS = 3

        /** A call that failed is made again after this long. */
        const val RETRY_MS = 10 * 60_000L
    }
}
