package io.github.hazymorning.klangbild.data.ma

import io.github.hazymorning.klangbild.domain.PlayerSnapshot
import io.github.hazymorning.klangbild.domain.PlayerStatus
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Where the song that plays stands, as Music Assistant's queue tells it: Home Assistant rounds
 * the position down to whole seconds, Music Assistant does not. Its answer is put on the local
 * clock through Music Assistant's own (its `time` command), and kept while the song plays. Its
 * players report every few seconds, many of them in whole seconds, so each answer can be a
 * little late; [PositionReports] keeps the best of them. One question at a time.
 */
internal class MaPosition(
    private val client: MaClient,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    /** The key of the song that plays: what is said about another one is dropped. */
    private val song: () -> String?,
    /** Music Assistant's queue as it stands (`player_queues/get`) while it plays the song of this key; null when it can't be told. */
    private val queue: suspend (String) -> JsonObject?,
    /** Where the song stands has moved. */
    private val changed: () -> Unit,
) {
    /** Where Music Assistant's queue stood in the song [key], on the local clock ([QueuePosition.atMs]). */
    private class Said(val key: String, val position: QueuePosition)

    @Volatile private var said: Said? = null

    /** The local wall clock minus Music Assistant's, in milliseconds, and when it was measured; null until known. */
    @Volatile private var clock: Long? = null
    private var clockAt = 0L

    /** The one question under way. */
    private var job: Job? = null
    private val lock = SynchronizedObject()

    /** Asks where the song stands, unless it is being asked already. */
    fun ask(): Unit = synchronized(lock) {
        if (job?.isActive == true) return
        job = scope.launch { read() }
    }

    /** Asks at once where a new song stands, dropping the question about the one before. */
    fun askAnew(): Unit = synchronized(lock) {
        job?.cancel()
        job = scope.launch { read() }
    }

    /** Another speaker shows: the question under way and what was said belong to the one before. */
    fun forget() {
        synchronized(lock) { job?.cancel() }
        said = null
    }

    /** Stops asking (the connection ends). */
    fun stop(): Unit = synchronized(lock) { job?.cancel() }

    /**
     * [p], which plays the song [key], at Music Assistant's position while it agrees with Home
     * Assistant's within [AGREE_MS] (Home Assistant's whole seconds, moved on only after about a
     * second); a jump Home Assistant has heard of and Music Assistant has not yet told wins.
     * [skewMs] is the local clock minus Home Assistant's, the one the position is published on.
     */
    fun appliedTo(p: PlayerSnapshot, key: String, skewMs: Long): PlayerSnapshot {
        if (p.status != PlayerStatus.PLAYING) {
            // A pause ends what Music Assistant last said; its next answer says where it stands.
            said = null
            return p
        }
        val m = said?.takeIf { it.key == key } ?: return p
        // The moment Music Assistant's position was valid, on Home Assistant's clock.
        val at = m.position.atMs - skewMs
        val reported = p.positionMs
        val reportedAt = p.positionAtEpochMs
        if (reported != null && reportedAt != null) {
            val then = reported + ((at - reportedAt) * p.speed.toDouble()).roundToLong()
            if (abs(then - m.position.elapsedMs) > AGREE_MS) return p
        }
        return p.copy(positionMs = m.position.elapsedMs, positionAtEpochMs = at)
    }

    /** Where Music Assistant's queue stands in the song that plays, kept while it plays and passed on when it moved. */
    private suspend fun read() {
        val key = song() ?: return
        val answer = queue(key) ?: return
        val position = MaQueue.position(answer)?.let { at -> clockOf()?.let { offset -> at.copy(atMs = at.atMs + offset) } } ?: return
        if (song() != key) return
        val moved = position != said?.takeIf { it.key == key }?.position
        said = Said(key, position).takeIf { position.playing }
        if (moved) changed()
    }

    /**
     * The local wall clock minus Music Assistant's, measured with its `time` command (the best of a
     * few round trips) and again after [CLOCK_MS]; null while it cannot be told.
     */
    private suspend fun clockOf(): Long? {
        val known = clock
        if (known != null && now() - clockAt < CLOCK_MS) return known
        var best: Long? = null
        var bestTrip = Long.MAX_VALUE
        repeat(CLOCK_TRIPS) {
            val sent = now()
            val server = try {
                client.serverTime()
            } catch (e: CancellationException) {
                throw e
            } catch (_: MaUnknownCommandException) {
                return null
            } catch (_: Exception) {
                return known
            }
            val back = now()
            if (back - sent < bestTrip) {
                bestTrip = back - sent
                best = (sent + back) / 2 - (server * 1_000).roundToLong()
            }
        }
        clock = best
        clockAt = now()
        return best
    }

    companion object {
        /** Music Assistant's position counts while it is this close to Home Assistant's. */
        const val AGREE_MS = 1_500L

        /** Music Assistant's clock is measured again after this long. */
        const val CLOCK_MS = 10 * 60_000L
        private const val CLOCK_TRIPS = 3
    }
}
