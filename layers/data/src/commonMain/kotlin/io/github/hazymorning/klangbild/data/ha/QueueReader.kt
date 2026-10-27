package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.ha.TrackMatch.sameStation
import io.github.hazymorning.klangbild.data.ha.TrackMatch.sameTitle
import io.github.hazymorning.klangbild.data.ma.MaAuthException
import io.github.hazymorning.klangbild.data.ma.MaClient
import io.github.hazymorning.klangbild.data.ma.MaLyrics
import io.github.hazymorning.klangbild.data.ma.MaQueue
import io.github.hazymorning.klangbild.data.ma.MaUnknownCommandException
import io.github.hazymorning.klangbild.data.ma.QueuePosition
import io.github.hazymorning.klangbild.data.ma.QueueWindow
import io.github.hazymorning.klangbild.domain.EntityState
import io.github.hazymorning.klangbild.domain.MaLink
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerSnapshot
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.QueueInfo
import io.github.hazymorning.klangbild.domain.SongLyrics
import io.github.hazymorning.klangbild.domain.TrackInfo
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.roundToLong

/** How often [QueueReader] reads the queue and how long it waits; shorter in tests. */
data class QueueTiming(
    /** How often the queue is read again while music plays. */
    val refreshMs: Long = 60_000L,
    /** How long a new track may wait for Music Assistant's queue to describe it. */
    val detailsWaitMs: Long = 2_000L,
    /**
     * How long after a new track's first read the queue is read once more. Music Assistant's
     * players report about once a second while they play; this leaves a margin.
     */
    val settleMs: Long = 2_500L,
    /** How often Music Assistant itself is asked for its queue while a track plays. */
    val maQueueMs: Long = 15_000L,
    /** Home Assistant reports a track change in several updates; the queue is read once they have settled. */
    val burstMs: Long = 350L,
)

/**
 * The queue around the song that plays, for a Music Assistant player: Home Assistant's
 * `get_queue` (what comes next, full-size artwork, the artists one by one) and, with Music
 * Assistant itself set up ([ma]), the whole queue around the current item and the words of the
 * song that plays ([MaLyrics]). One read at a time: a new song cancels the read under way, so
 * quick skips leave nothing running. A Music Assistant song waits for its queue to describe it
 * ([awaitsDetails]), and the song is enriched with what the queue knows ([enrich]), its position
 * too: Music Assistant keeps it to a fraction of a second, Home Assistant in whole seconds.
 *
 * It shares [HaSource]'s [lock]; what it learns it hands on by [publish].
 */
internal class QueueReader(
    private val scope: CoroutineScope,
    private val connection: HaConnection,
    /** The speaker whose queue is read: the one shown (none while "This TV" is not found yet). */
    private val entity: () -> String?,
    private val baseUrl: String,
    private val ma: MaClient?,
    private val now: () -> Long,
    /** The player as Home Assistant last reported it. */
    private val latest: () -> PlayerSnapshot?,
    /** The player as last published (while a new song waits, still the one before). */
    private val shown: () -> PlayerSnapshot?,
    private val lock: SynchronizedObject,
    private val timing: QueueTiming,
    /** Publishes the source's state anew. */
    private val publish: () -> Unit,
) {
    private var queuePoller: Job? = null
    private var maPoller: Job? = null

    /** The one queue read under way: a newer track cancels it (quick skips leave nothing running). */
    private var queueJob: Job? = null
    @Volatile private var queue: QueueInfo? = null
    @Volatile private var window: QueueWindow? = null
    @Volatile private var queueForKey: String? = null

    /** The song that plays ([TrackMatch.key]). */
    @Volatile private var songKey: String? = null

    /** How the link to Music Assistant itself stands, as its last answer showed. */
    @Volatile var maLink: MaLink? = null
        private set

    /**
     * Home Assistant answered the last `get_queue` (null: not asked yet). A player without a
     * queue is not asked again until its track changes, and its songs do not wait for one.
     */
    @Volatile private var queueWorks: Boolean? = null

    /** The song that waits for the queue to describe it, since when, and what publishes it when the wait is over. */
    private var awaited: String? = null
    private var awaitedSince = 0L
    private var awaitEnd: Job? = null

    /** A station's pictures, ranked and steady while a song plays (only touched while publishing). */
    private val stationPictures = StationPictures()

    /** The words of the song that plays and of the one up next, from Music Assistant itself. */
    private val lyrics: MaLyrics? = ma?.let { MaLyrics(it, scope, now, publish) }

    /** What was last known of the words of the song that plays (only touched while publishing). */
    private var lastLyrics: SongLyrics? = null

    /**
     * What comes next as Music Assistant itself has it, for the song [key]: [next] (null: nothing
     * follows) and what it was told from ([basis]: the current item's place, the next item's id,
     * how many items).
     */
    private class MaNext(val key: String, val next: TrackInfo?, val basis: Triple<Int?, String?, Int?>)

    @Volatile private var maNext: MaNext? = null

    /** Where Music Assistant's queue stood in the song [key], on the local clock ([QueuePosition.atMs]). */
    private class MaPosition(val key: String, val position: QueuePosition)

    @Volatile private var maPosition: MaPosition? = null

    /** The local wall clock minus Music Assistant's, in milliseconds, and when it was measured; null until known. */
    @Volatile private var maClock: Long? = null
    private var maClockAt = 0L

    /** Music Assistant knows no `time` command: its clock is not asked again. */
    @Volatile private var maClockUnknown = false

    /** Music Assistant's queue was last read at this time, for what it was then (id, place, next). */
    private var windowReadAt = 0L
    private var windowFor: Triple<String?, Int?, String?>? = null

    fun start() {
        queuePoller = scope.launch {
            ma?.let {
                maLink = it.check()
                publish()
            }
            // Light periodic refresh: the queue can change without the track changing. It is asked
            // only of a Music Assistant player that has a queue, and only while it plays.
            while (isActive) {
                delay(timing.refreshMs)
                val p = shown()
                if (p?.status == PlayerStatus.PLAYING && p.isMusicAssistant && queueWorks == true) startQueueRead(force = true)
            }
        }
        // Home Assistant hears nothing of a queue changed while the same song plays (a song added
        // to play next, the order changed): Music Assistant itself is asked, if it is set up.
        maPoller = ma?.let { client ->
            scope.launch {
                while (isActive) {
                    delay(timing.maQueueMs)
                    val p = shown()
                    if (p?.status == PlayerStatus.PLAYING && p.isMusicAssistant && queueWorks == true) startMaCheck(client)
                }
            }
        }
    }

    fun stop() {
        queuePoller?.cancel()
        maPoller?.cancel()
        queueJob?.cancel()
        awaitEnd?.cancel()
        lyrics?.stop()
    }

    /**
     * Another speaker shows (this TV's own, or back to the one chosen): what was read belongs to
     * the one before, and its read under way is canceled (called under the [lock]).
     */
    fun retarget() {
        queueJob?.cancel()
        awaitEnd?.cancel()
        queue = null
        window = null
        queueForKey = null
        songKey = null
        maNext = null
        queueWorks = null
        awaited = null
        windowFor = null
        maPosition = null
    }

    /** The song [key] plays on [player]: a new one starts its queue's read (called under the [lock]). */
    fun onTrack(key: String, player: PlayerSnapshot) {
        if (key == songKey) return
        // What Music Assistant itself said comes next belonged to the song before (the same
        // song again later has a queue of its own).
        maNext = null
        songKey = key
        if (player.isMusicAssistant) startQueueRead(force = false)
    }

    /** Whether the queue has been read for the song [key]. */
    fun knows(key: String?): Boolean = key != null && queueForKey == key

    /** The queue as read for the song [key], if it has been. */
    fun queueFor(key: String?): QueueInfo? = queue?.takeIf { knows(key) }

    /** Music Assistant's queue around the song [key], if it has been read. */
    fun windowFor(key: String?): QueueWindow? = window?.takeIf { knows(key) }

    /**
     * Whether the song [key] on [p] waits for Music Assistant's queue: Home Assistant reports a
     * new song first with a small picture (512 px) and without the album's year, and the queue
     * tells the rest a moment later. A song the queue has described or announced as next does not
     * wait, nor does a player whose queue does not answer; none waits longer than [QueueTiming.detailsWaitMs].
     */
    fun awaitsDetails(p: PlayerSnapshot, key: String, t: Long): Boolean {
        val track = p.track
        if (track == null || !p.isMusicAssistant || queueWorks == false || queueForKey == key || announced(track) != null) {
            awaited = null
            return false
        }
        if (awaited != key) {
            awaited = key
            awaitedSince = t
            awaitEnd?.cancel()
            awaitEnd = scope.launch {
                delay(timing.detailsWaitMs)
                publish()
            }
        }
        return t - awaitedSince < timing.detailsWaitMs
    }

    /**
     * What Music Assistant knows of the words of [track], the song that plays: found by its URI,
     * which Home Assistant names as what plays, else by the queue's current song when that is
     * [track]. Null while nothing is known; without a URI (the link is down) what was known stays.
     */
    fun lyricsOf(track: TrackInfo, entity: EntityState?, w: QueueWindow?): SongLyrics? {
        val loader = lyrics ?: return null
        if (track.kind != MediaKind.MUSIC) return null
        val uri = entity?.string("media_content_id")?.takeIf { it.contains("://") }
            ?: w?.currentSong?.uri?.takeIf { w.current?.let { c -> sameTitle(c.title, track.title) } == true }
        // Words still sung after the song has ended were timed for another recording: none.
        val length = track.durationMs
        val known = if (uri == null) {
            lastLyrics?.takeIf { it.key == track.key }
        } else {
            loader.known(uri)?.let { SongLyrics(track.key, it.lyrics?.takeIf { l -> length == null || l.fit(length) }) }
        }
        if (known != null) lastLyrics = known
        return known
    }

    /**
     * What comes next: Home Assistant's word ([q]), unless Music Assistant itself has since said
     * otherwise for the song [key] (the queue changed while it plays). The same song keeps Home
     * Assistant's description, the one it brings when it starts.
     */
    fun nextOf(q: QueueInfo?, key: String?): TrackInfo? {
        val ha = q?.next
        val mine = maNext?.takeIf { q != null && it.key == key } ?: return ha
        val next = mine.next ?: return null
        return if (ha != null && ha.kind == next.kind && sameTitle(ha.title, next.title)) ha else next
    }

    /**
     * The song Music Assistant's queue announced as next, when [track] is it: described before
     * its own read (music only; a book's or an episode's chapters come with that read).
     */
    private fun announced(track: TrackInfo): TrackInfo? =
        queue?.next?.takeIf { it.kind == MediaKind.MUSIC && track.kind == MediaKind.MUSIC && sameTitle(it.title, track.title) }

    /**
     * Prefer the queue's richer data when it describes the same track (or announced it as next,
     * before its own read); Music Assistant's own queue adds a book's authors, an episode's show,
     * the chapters, the speed and the position to a fraction of a second. [skewMs] is the local
     * clock minus Home Assistant's, the one the position is published on.
     */
    fun enrich(p: PlayerSnapshot, skewMs: Long): PlayerSnapshot = position(details(p), skewMs)

    /**
     * [p] at Music Assistant's position while it plays the song [p] plays and agrees with Home
     * Assistant's within [AGREE_MS] (Home Assistant's whole seconds, moved on only after about a
     * second); a jump Home Assistant has heard of and Music Assistant has not yet told wins.
     */
    private fun position(p: PlayerSnapshot, skewMs: Long): PlayerSnapshot {
        val track = p.track ?: return p
        if (p.status != PlayerStatus.PLAYING) {
            // A pause ends what Music Assistant last said; its next read says where it stands.
            maPosition = null
            return p
        }
        val m = maPosition?.takeIf { it.key == TrackMatch.key(track) } ?: return p
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

    private fun details(p: PlayerSnapshot): PlayerSnapshot {
        val track = p.track ?: return p
        if (track.kind == MediaKind.RADIO) return enrichStation(p, track)
        val known = queueForKey == TrackMatch.key(track)
        val current = (if (known) queue?.current else announced(track)) ?: return p
        if (!sameTitle(current.title, track.title)) return p
        val w = window?.takeIf { w -> known && w.current?.let { sameTitle(it.title, track.title) } == true }
        val mine = w?.current
        // Only Music Assistant's own queue knows the album's year.
        return p.copy(
            track = track.copy(
                artists = current.artists.ifEmpty { mine?.artists.orEmpty() }.ifEmpty { track.artists },
                album = track.album ?: current.album,
                year = current.year ?: mine?.year ?: track.year,
                artworkUrl = current.artworkUrl ?: track.artworkUrl,
                artworkFallbackUrl = track.artworkUrl ?: track.artworkFallbackUrl,
                source = current.source ?: track.source,
                kind = listOf(track.kind, mine?.kind, current.kind).firstOrNull { it != null && it != MediaKind.MUSIC } ?: MediaKind.MUSIC,
                chapters = mine?.chapters.orEmpty(),
                favorite = mine?.favorite == true,
            ),
            speed = w?.speed ?: p.speed,
        )
    }

    /**
     * A station: the queue names the station, and a name in the album's place that is not the
     * station's is the song's album. The picture is [StationPictures]' choice from what is
     * offered: the song's picture from Music Assistant's own queue, Home Assistant's picture
     * (Music Assistant's choice of the moment) and the station's from the queue.
     */
    private fun enrichStation(p: PlayerSnapshot, track: TrackInfo): PlayerSnapshot {
        val known = queueForKey == TrackMatch.key(track)
        val mine = window?.current?.takeIf { known && it.kind == MediaKind.RADIO && sameTitle(it.title, track.title) }
        val item = queue?.current?.takeIf { known && it.kind == MediaKind.RADIO }
        val station = item?.source ?: mine?.source ?: track.source
        val album = track.album ?: mine?.album ?: track.source?.takeIf { known && station != null && !it.equals(station, ignoreCase = true) }
        val picture = stationPictures.choose(
            TrackMatch.key(track),
            listOfNotNull(
                mine?.takeIf { it.songPicture }?.artworkUrl?.let { StationPictures.Offer(it, mine.artworkFallbackUrl, StationPictures.Rank.SONG, certain = true) },
                track.artworkUrl?.let { url ->
                    // Home Assistant serves the station's picture through its own proxy; an address of its own is the song's.
                    val rank = if (HaUrls.isPlayerProxy(url)) StationPictures.Rank.UNKNOWN else StationPictures.Rank.SONG
                    StationPictures.Offer(url, track.artworkFallbackUrl, rank)
                },
                item?.artworkUrl?.let { StationPictures.Offer(it, item.artworkFallbackUrl, StationPictures.Rank.STATION, certain = true) },
            ),
        )
        return p.copy(track = track.copy(album = album, artworkUrl = picture?.url, artworkFallbackUrl = picture?.fallback, source = station))
    }

    /** Asks Music Assistant what comes next, unless a queue read is under way (a new song cancels the question). */
    private fun startMaCheck(client: MaClient) {
        synchronized(lock) {
            if (queueJob?.isActive == true) return
            queueJob = scope.launch { checkMaQueue(client) }
        }
    }

    /**
     * What Music Assistant itself says comes next, while the song Home Assistant described plays,
     * and the songs to come with it, read anew each time: a song moved further down keeps the
     * next one and the count, and Music Assistant tells of no change otherwise. Only what has
     * changed is published.
     */
    private suspend fun checkMaQueue(client: MaClient) {
        val key = songKey ?: return
        val q = queue?.takeIf { queueForKey == key } ?: return
        val id = q.queueId ?: latest()?.activeQueue ?: return
        val answer = try {
            client.queue(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: MaUnknownCommandException) {
            // A version that cannot say: it is not asked again.
            maPoller?.cancel()
            null
        } catch (_: Exception) {
            null
        } ?: return
        val now = MaQueue.now(answer, client.baseUrl)
        val current = now.current ?: return
        val shown = latest()?.track ?: return
        // Music Assistant has moved on to another item: that song's own read follows.
        if (!sameTitle(current.title, shown.title) && !sameStation(current, shown)) return
        val basis = Triple(now.index, now.nextId, now.items)
        val same = maNext?.let { it.key == key && it.basis == basis } == true
        val read = now.index?.let { windowAt(client, id, it) }?.takeIf { matches(it, current) }
        val position = MaQueue.position(answer)?.let { at -> clockOf(client)?.let { offset -> at.copy(atMs = at.atMs + offset) } }
        if (songKey != key) return
        val moved = position != null && position != maPosition?.takeIf { it.key == key }?.position
        if (position != null) maPosition = MaPosition(key, position).takeIf { position.playing }
        if (same && (read == null || read == window)) {
            // Only where the song stands has moved on.
            if (moved) publish()
            return
        }
        maNext = MaNext(key, now.next, basis)
        if (read != null) {
            window = read
            lyrics?.want(listOfNotNull(read.currentSong, read.nextSong))
        }
        publish()
    }

    /**
     * The local wall clock minus Music Assistant's, measured with its `time` command (the best of a
     * few round trips) and again after [MA_CLOCK_MS]; null while it cannot be told.
     */
    private suspend fun clockOf(client: MaClient): Long? {
        if (maClockUnknown) return null
        val known = maClock
        if (known != null && now() - maClockAt < MA_CLOCK_MS) return known
        var best: Long? = null
        var bestTrip = Long.MAX_VALUE
        repeat(CLOCK_TRIPS) {
            val sent = now()
            val server = try {
                client.serverTime()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: MaUnknownCommandException) {
                maClockUnknown = true
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
        maClock = best
        maClockAt = now()
        return best
    }

    /** Starts reading the queue anew; a read under way for an older track is canceled, a periodic one waits its turn. */
    private fun startQueueRead(force: Boolean) {
        synchronized(lock) {
            if (force && queueJob?.isActive == true) return
            queueJob?.cancel()
            queueJob = scope.launch { refreshQueue(force) }
        }
    }

    private suspend fun refreshQueue(force: Boolean) {
        val key = songKey ?: return
        if (!force && queueForKey == key) return
        delay(timing.burstMs)
        if (songKey != key) return
        val target = entity() ?: return
        val response = try {
            connection.callService("music_assistant", "get_queue", target, returnResponse = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        queueWorks = response != null
        // No answer (a source without a queue, like a streaming app playing on the speaker directly):
        // nothing is known to follow. A periodic refresh that fails keeps what is known.
        val again = force && queueForKey == key
        val parsed = response?.let { PlayerParser.queue(it, target, baseUrl) }
        if (parsed == null && again) return
        // Music Assistant's queue is read again only when something moved, or for spoken word,
        // whose speed can change at any time.
        val read = parsed?.let { q -> if (!again || windowStale(q)) readWindow(q) else window } ?: window.takeIf { again }
        if (songKey != key) return
        queue = parsed
        window = read
        queueForKey = key
        // The words of the song that plays and of the one up next, as they come; never waited for.
        read?.let { w -> lyrics?.want(listOfNotNull(w.currentSong, w.nextSong)) }
        publish()
        if (again || parsed == null) return
        // Music Assistant names the next item anew only when the player reports after the new one
        // has started: a queue replaced as it starts still names the old queue's next for a moment.
        delay(timing.settleMs)
        if (songKey != key) return
        refreshQueue(force = true)
        // A book's or an episode's chapters may follow a moment after it starts (Music Assistant 2.11).
        val current = window?.current
        if (current != null && current.kind.spoken && current.chapters.isEmpty()) {
            delay(CHAPTERS_AGAIN_MS)
            if (songKey == key) refreshQueue(force = true)
        }
    }

    /** Whether Music Assistant's queue is worth reading again: it moved, it is old, or it is spoken word. */
    private fun windowStale(q: QueueInfo): Boolean =
        Triple(q.queueId, q.currentIndex, q.next?.key) != windowFor ||
            now() - windowReadAt > WINDOW_MAX_AGE_MS ||
            q.current?.kind?.spoken == true ||
            window?.current?.kind?.spoken == true

    /**
     * The whole queue around the current item, from Music Assistant itself (if set up), but only
     * if it is the same item Home Assistant reports (the queue may have moved in between).
     */
    private suspend fun readWindow(q: QueueInfo): QueueWindow? {
        val client = ma ?: return null
        val id = q.queueId ?: latest()?.activeQueue ?: return null
        val index = q.currentIndex ?: return null
        val w = windowAt(client, id, index) ?: return null
        windowFor = Triple(q.queueId, q.currentIndex, q.next?.key)
        return w.takeIf { matches(it, q.current) }
    }

    /** Whether [w] is the queue around [current] (the queue may have moved on in between). */
    private fun matches(w: QueueWindow, current: TrackInfo?): Boolean {
        val a = w.current
        return a != null && current != null && (sameTitle(a.title, current.title) || sameStation(a, current))
    }

    /** Queue [id] around [index] as Music Assistant has it; null when it cannot be read. */
    private suspend fun windowAt(client: MaClient, id: String, index: Int): QueueWindow? {
        val (offset, limit) = MaQueue.range(index)
        return try {
            MaQueue.window(client.items(id, offset, limit), offset, index, client.baseUrl).also {
                maLink = MaLink.CONNECTED
                windowReadAt = now()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: MaAuthException) {
            maLink = MaLink.AUTH_FAILED
            null
        } catch (_: Exception) {
            maLink = MaLink.UNREACHABLE
            null
        }
    }

    companion object {
        /** Music Assistant's queue is read again after this long even when nothing seems to have moved. */
        const val WINDOW_MAX_AGE_MS = 5 * 60_000L

        /** A book without chapters is asked once more after this long. */
        const val CHAPTERS_AGAIN_MS = 10_000L

        /** Music Assistant's position counts while it is this close to Home Assistant's. */
        const val AGREE_MS = 1_500L

        /** Music Assistant's clock is measured again after this long. */
        const val MA_CLOCK_MS = 10 * 60_000L
        private const val CLOCK_TRIPS = 3
    }
}
