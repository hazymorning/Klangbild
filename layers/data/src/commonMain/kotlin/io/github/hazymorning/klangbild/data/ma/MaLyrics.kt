package io.github.hazymorning.klangbild.data.ma

import io.github.hazymorning.klangbild.domain.Lrc
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.math.RecentMap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The words of the song that plays and of the one up next, from Music Assistant. A queue item
 * that brings its words along is read at once. For any other song Music Assistant is asked
 * ([MaClient.lyrics]), one song at a time: the one that plays first, then the one up next, so
 * that its words are there when it starts. That call may take a while (Music Assistant looks
 * words up online at most once in 30 seconds) and holds nothing else up. What is known is kept
 * by the song's URI. A call for a song that neither plays nor comes next any more is canceled,
 * and its answer, should it come all the same, is dropped. On a version without the call, a song
 * that brings no words has none.
 */
class MaLyrics(
    private val client: MaClient,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Something new is known about a song that plays or comes next. */
    private val changed: () -> Unit,
) {
    /** What is known of a song's words: synced [lyrics], or null for none. A failed call has [failedAt]. */
    class Known(val lyrics: Lyrics?, internal val failedAt: Long? = null)

    private val known = RecentMap<String, Known>(KEEP)
    private val lock = SynchronizedObject()

    /** The songs still to ask about, the one that plays first. */
    private var wanted: List<QueueSong> = emptyList()

    /** The call that is out, and the song it is for. */
    private var job: Job? = null
    private var asking: String? = null

    /** Music Assistant knows the call (false after a version without it said so). */
    private var supported = true

    /** What is known of the words of the song at [uri]; null while nothing is. */
    fun known(uri: String): Known? = synchronized(lock) { known[uri] }

    /** The songs whose words are wanted now: the one that plays, then the one up next. */
    fun want(songs: List<QueueSong>) {
        var found = false
        synchronized(lock) {
            val t = now()
            for (song in songs) {
                // A failed call is tried again after a while, should the song come again.
                val failedAt = known[song.uri]?.failedAt
                if (failedAt != null && t - failedAt >= RETRY_MS) known.remove(song.uri)
                if (song.uri in known) continue
                if (song.lyrics != null || song.lrcLyrics != null || !supported) {
                    known[song.uri] = Known(Lrc.of(song.lyrics, song.lrcLyrics))
                    found = true
                }
            }
            wanted = songs.filter { it.uri !in known }
            if (asking != null && wanted.none { it.uri == asking }) {
                job?.cancel()
                job = null
                asking = null
            }
            askNext()
        }
        if (found) changed()
    }

    /** Stops asking (the connection ends). */
    fun stop(): Unit = synchronized(lock) {
        job?.cancel()
        job = null
        asking = null
        wanted = emptyList()
    }

    /**
     * Asks about the first song still wanted, unless a call is out (called under the lock). The
     * call is known as [job] before it starts, so even an answer that comes at once finds it.
     */
    private fun askNext() {
        if (job != null) return
        val song = wanted.firstOrNull() ?: return
        asking = song.uri
        val call = scope.launch(start = CoroutineStart.LAZY) {
            val answer = try {
                val (plain, lrc) = client.lyrics(song.item)
                Known(Lrc.of(plain, lrc))
            } catch (e: CancellationException) {
                throw e
            } catch (_: MaUnknownCommandException) {
                synchronized(lock) { supported = false }
                Known(null)
            } catch (_: Exception) {
                Known(null, failedAt = now())
            }
            val me = coroutineContext.job
            val kept = synchronized(lock) {
                // Canceled after the answer came (its song is no longer wanted, or asking stopped): dropped.
                if (job !== me) return@launch
                job = null
                asking = null
                val still = wanted.any { it.uri == song.uri }
                if (still) known[song.uri] = answer
                if (!supported) wanted.forEach { known.getOrPut(it.uri) { Known(null) } }
                wanted = wanted.filter { it.uri !in known }
                askNext()
                still || !supported
            }
            if (kept) changed()
        }
        job = call
        call.start()
    }

    companion object {
        /** The songs whose words are kept: far more than plays in an evening's queue around the one on stage. */
        const val KEEP = 64

        /** A call that failed is made again after this long, should its song come again. */
        const val RETRY_MS = 10 * 60_000L
    }
}
