package io.github.hazymorning.klangbild.data.ma

import io.github.hazymorning.klangbild.domain.Lrc
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.math.RecentMap
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Something new is known about a song that plays or comes next. */
    changed: () -> Unit,
) {
    /** What is known of a song's words: synced [lyrics], or null for none. A failed call has [failedAt]. */
    class Known(val lyrics: Lyrics?, internal val failedAt: Long? = null)

    private val known = RecentMap<String, Known>(KEEP)
    private val lock = SynchronizedObject()

    /** Music Assistant knows the call (false after a version without it said so). */
    private var supported = true

    private val calls = OneAtATime(scope, lock, QueueSong::uri, ::ask, ::keep, changed)

    /** What is known of the words of the song at [uri]; null while nothing is. */
    fun known(uri: String): Known? = synchronized(lock) { known[uri] }

    /** The songs whose words are wanted now: the one that plays, then the one up next. */
    fun want(songs: List<QueueSong>): Unit = synchronized(lock) {
        val t = now()
        for (song in songs) {
            // A failed call is tried again after a while, should the song come again.
            val failedAt = known[song.uri]?.failedAt
            if (failedAt != null && t - failedAt >= RETRY_MS) known.remove(song.uri)
            if (song.uri in known) continue
            if (song.lyrics != null || song.lrcLyrics != null || !supported) {
                known[song.uri] = Known(Lrc.of(song.lyrics, song.lrcLyrics))
            }
        }
        calls.want(songs.filter { it.uri !in known })
    }

    /** Stops asking (the connection ends). */
    fun stop(): Unit = calls.stop()

    /** The words Music Assistant has for [song]: none on a version without the call, and none for a while after a failed call. */
    private suspend fun ask(song: QueueSong): Known = try {
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

    /** Keeps the answer about [song]; every answer is news. */
    private fun keep(song: QueueSong, answer: Known): Boolean {
        known[song.uri] = answer
        return true
    }

    companion object {
        /** The songs whose words are kept: far more than plays in an evening's queue around the one on stage. */
        const val KEEP = 64

        /** A call that failed is made again after this long, should its song come again. */
        const val RETRY_MS = 10 * 60_000L
    }
}
