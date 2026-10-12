package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.config.ActionKind
import io.github.hazymorning.klangbild.config.QuickAction
import io.github.hazymorning.klangbild.data.LinkState
import io.github.hazymorning.klangbild.data.NowPlayingSource
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.data.SourceState
import io.github.hazymorning.klangbild.data.ma.MaAuthException
import io.github.hazymorning.klangbild.data.ma.MaClient
import io.github.hazymorning.klangbild.data.ma.MaLink
import io.github.hazymorning.klangbild.data.ma.MaLyrics
import io.github.hazymorning.klangbild.data.ma.MaQueue
import io.github.hazymorning.klangbild.data.ma.QueueWindow
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerSnapshot
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.QueueInfo
import io.github.hazymorning.klangbild.domain.SongWords
import io.github.hazymorning.klangbild.domain.SpeakerGroup
import io.github.hazymorning.klangbild.domain.SpeakerLabel
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.lights.LightBridge
import io.github.hazymorning.klangbild.vista.WeatherMood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.text.Normalizer

/**
 * Home Assistant as the source: the configured media_player plus `sun.sun`, enriched with
 * Music Assistant's queue (next track, full-size artwork, individual artist names) and, when
 * Music Assistant itself is set up ([ma]), with the whole queue around the current track and
 * the words of the song that plays ([MaLyrics]). A Music Assistant song is published once that
 * queue has described it, so it never comes first with Home Assistant's small picture and
 * without its year ([awaitsDetails]). Remote commands go out only where the player supports them.
 */
class HaSource(
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val config: HaConfig,
    private val playerEntity: String,
    private val sunEntity: String = "sun.sun",
    private val now: () -> Long = System::currentTimeMillis,
    private val ma: MaClient? = null,
    /** The weather entities to read the sky's cover from, the first that exists counts (none: no weather). */
    private val weatherEntities: List<String> = emptyList(),
    /** How often the queue is read again while music plays (shorter in tests). */
    private val queueRefreshMs: Long = QUEUE_REFRESH_MS,
    /** How long a button's call may go unanswered before it counts as still running (shorter in tests). */
    private val actionAnswerMs: Long = ACTION_ANSWER_MS,
    /** How long a new song may wait for Music Assistant's queue to describe it (shorter in tests). */
    private val detailsWaitMs: Long = DETAILS_WAIT_MS,
    /** How long after a new song's first queue read the queue is read once more (shorter in tests). */
    private val queueSettleMs: Long = QUEUE_SETTLE_MS,
) : NowPlayingSource, LightBridge {

    private val connection = HaConnection(http, scope)
    private val _state = MutableStateFlow(SourceState(link = LinkState.CONNECTING))
    override val state: StateFlow<SourceState> = _state.asStateFlow()

    private var collector: Job? = null
    private var queuePoller: Job? = null

    /** The one queue read under way: a newer track cancels it (quick skips leave nothing running). */
    private var queueJob: Job? = null
    @Volatile private var queue: QueueInfo? = null
    @Volatile private var window: QueueWindow? = null
    @Volatile private var queueForKey: String? = null
    @Volatile private var maLink: MaLink? = null
    @Volatile private var lastPlayerKey: String? = null

    /**
     * Home Assistant answered the last `get_queue` (null: not asked yet). A player without a
     * queue is not asked again until its track changes, and its songs do not wait for one.
     */
    @Volatile private var queueWorks: Boolean? = null

    /** The song that waits for the queue to describe it, since when, and what publishes it when the wait is over. */
    private var awaited: String? = null
    private var awaitedSince = 0L
    private var awaitEnd: Job? = null

    /** A station's pictures, ranked and steady while a song plays (only touched in [publish]). */
    private val stationPictures = StationPictures()

    /** The words of the song that plays and of the one up next, from Music Assistant itself. */
    private val lyrics: MaLyrics? = ma?.let { MaLyrics(it, scope, now) { publish(connection.status.value, connection.entities.value) } }

    /** What was last known of the words of the song that plays (only touched in [publish]). */
    private var lastWords: SongWords? = null

    /** Music Assistant's queue was last read at this time, for what it was then (id, place, next). */
    private var windowReadAt = 0L
    private var windowFor: Triple<String?, Int?, String?>? = null
    private var linkDownSince: Long? = null
    private var lastSnapshot: PlayerSnapshot? = null
    private var receivedAt = 0L
    private var watched: Set<String> = emptySet()

    /** The lights that join in, followed for [LightSync] alongside the group's speakers. */
    @Volatile private var lightIds: Set<String> = emptySet()
    private val _lights = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    override val lights: StateFlow<Map<String, EntityState>> = _lights.asStateFlow()

    override fun follow(ids: Set<String>) {
        if (ids == lightIds) return
        lightIds = ids
        publish(connection.status.value, connection.entities.value)
    }

    /** The entities of one's own: what the quick settings' buttons work and look at, and the values in the status line. */
    @Volatile private var ownIds: Set<String> = emptySet()

    override fun followEntities(ids: Set<String>) {
        if (ids == ownIds) return
        ownIds = ids
        publish(connection.status.value, connection.entities.value)
    }

    /**
     * Does what [action] says: a script runs (with its variables, and the speaker as the
     * variable `player` unless the button names one itself), a scene is activated, an
     * automation runs its actions, a button is pressed, a switch is turned the other way. Home
     * Assistant answers a call once it is done: one still running after [actionAnswerMs] (a long
     * automation) has been taken.
     */
    override suspend fun run(action: QuickAction): Boolean {
        val kind = action.kind ?: return false
        val domain = action.entity.substringBefore('.')
        val service = when (kind) {
            ActionKind.RUN -> if (domain == "script") "turn_on" else "trigger"
            ActionKind.ACTIVATE -> "turn_on"
            ActionKind.PRESS -> "press"
            ActionKind.TOGGLE -> "toggle"
        }
        val data = if (domain == "script") {
            buildJsonObject { put("variables", JsonObject(mapOf("player" to JsonPrimitive(playerEntity)) + action.variables)) }
        } else {
            null
        }
        return try {
            connection.callService(domain, service, action.entity, data = data, timeoutMs = actionAnswerMs)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: HaNoAnswerException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun call(domain: String, service: String, entityId: String?, data: JsonObject?): String? = try {
        connection.callServiceForContext(domain, service, entityId, data)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override fun start() {
        connection.start(config, listOf(playerEntity, sunEntity) + weatherEntities)
        collector = scope.launch {
            combine(connection.status, connection.entities, connection.registry, connection.config, connection.missing) { st, ents, _, _, _ -> st to ents }
                .collect { (st, ents) -> publish(st, ents) }
        }
        queuePoller = scope.launch {
            ma?.let {
                maLink = it.check()
                publish(connection.status.value, connection.entities.value)
            }
            // Light periodic refresh: the queue can change without the track changing. It is asked
            // only of a Music Assistant player that has a queue, and only while it plays.
            while (isActive) {
                delay(queueRefreshMs)
                val p = _state.value.player
                if (p?.status == PlayerStatus.PLAYING && p.isMusicAssistant && queueWorks == true) startQueueRead(force = true)
            }
        }
    }

    override fun stop() {
        collector?.cancel()
        queuePoller?.cancel()
        queueJob?.cancel()
        awaitEnd?.cancel()
        lyrics?.stop()
        connection.stop()
    }

    /** The network came back: do not wait for the backoff timer. */
    fun networkAvailable() = connection.retryNow()

    /** Sends [command] as the player's service, only when the player supports it. */
    override suspend fun send(command: RemoteCommand) {
        val p = lastSnapshot ?: return
        val service = serviceFor(command, p) ?: return
        attempt { connection.callService("media_player", service, playerEntity) }
    }

    /**
     * Adds what plays to Music Assistant's favorites: through the speaker's own button in Home
     * Assistant ("Favorite current song"), else through Music Assistant itself. A station's song
     * is looked up as a song by Music Assistant; it fails when it cannot find one.
     */
    override suspend fun favorite(): Boolean {
        val button = favoriteButton()
        if (button != null) return attempt { connection.callService("button", "press", button) }
        val client = ma ?: return false
        val player = lastSnapshot?.activeQueue ?: return false
        return attempt { client.favoriteNowPlaying(player) }
    }

    /** Runs [block]; whether it worked. Only a cancellation passes. */
    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * The speaker's own button that adds what plays to Music Assistant's favorites ("Favorite
     * current song"), found in the entity registry: same device, Music Assistant's translation
     * key (its entity id depends on the language). Null without one.
     */
    private fun favoriteButton(): String? {
        val registry = connection.registry.value ?: return null
        val device = registry.firstOrNull { it.entityId == playerEntity }?.deviceId ?: return null
        return registry.firstOrNull {
            it.deviceId == device && it.platform == MA_PLATFORM && it.translationKey == FAVORITE_KEY && it.entityId.startsWith("button.")
        }?.entityId
    }

    override suspend fun shuffle(on: Boolean): Boolean {
        if (lastSnapshot?.abilities?.shuffle != true) return false
        val data = buildJsonObject { put("shuffle", on) }
        return attempt { connection.callService("media_player", "shuffle_set", playerEntity, data = data) }
    }

    /** Plays on from [positionMs] in the item's own time (`seek_position` in seconds). */
    override suspend fun seek(positionMs: Long) {
        if (lastSnapshot?.abilities?.seek != true) return
        val data = buildJsonObject { put("seek_position", positionMs.coerceAtLeast(0) / 1000.0) }
        attempt { connection.callService("media_player", "media_seek", playerEntity, data = data) }
    }

    /** Called from the status/entity collector and after queue refreshes, serialized. */
    @Synchronized
    private fun publish(status: HaStatus, entities: Map<String, EntityState>) {
        val t = now()
        val link = when (status) {
            HaStatus.Connected -> LinkState.CONNECTED
            is HaStatus.AuthFailed -> LinkState.AUTH_FAILED
            is HaStatus.Connecting -> if (lastSnapshot == null && linkDownSince == null) LinkState.CONNECTING else LinkState.RECONNECTING
            is HaStatus.Waiting -> LinkState.RECONNECTING
            HaStatus.Stopped -> LinkState.CONNECTING
        }
        linkDownSince = if (link == LinkState.CONNECTED) null else (linkDownSince ?: t)

        val entity = entities[playerEntity]
        var player = entity?.let { PlayerParser.player(it, config.baseUrl) }
        if (player != null && player != lastSnapshot) {
            receivedAt = t
            lastSnapshot = player
        }
        player = player ?: lastSnapshot.takeIf { link != LinkState.CONNECTED }

        val key = player?.track?.let { trackKey(it) }
        if (key != null && key != lastPlayerKey) {
            lastPlayerKey = key
            if (player.isMusicAssistant) startQueueRead(force = false)
        }

        val enriched = player?.let { enrich(it) }
        val sun = entities[sunEntity]?.number("elevation")
        val weather = weatherEntities.firstNotNullOfOrNull { entities[it] }
        // The other speakers of a group are followed too, for their names, and so are the
        // lights; in a larger group also every Music Assistant speaker, to tell "everywhere".
        val others = player?.groupMembers.orEmpty()
        val follow = others.toSet() + lightIds + ownIds + (if (others.size + 1 >= EVERYWHERE_MIN) maSpeakers() else emptySet())
        if (follow != watched) {
            watched = follow
            connection.watch(watched)
        }
        val lightStates = lightIds.mapNotNull { id -> entities[id]?.let { id to it } }.toMap()
        if (lightStates != _lights.value) _lights.value = lightStates
        val queueKnown = key != null && queueForKey == key
        val currentQueue = queue?.takeIf { queueKnown }
        val currentWindow = window?.takeIf { queueKnown }
        val fresh = SourceState(
            link = link,
            player = enriched,
            entityMissing = status == HaStatus.Connected && entity == null && playerEntity in connection.missing.value,
            next = currentQueue?.next,
            sunElevation = sun ?: _state.value.sunElevation,
            receivedAtMs = receivedAt,
            skewMs = connection.clockSkew.offsetMs,
            linkDownSinceMs = linkDownSince,
            group = player?.let { groupOf(it, others.map { id -> entities[id]?.string("friendly_name") }, currentQueue) }
                ?.let { g -> if (isEverywhere(g, entities)) g.copy(everywhere = true) else g },
            queueKnown = queueKnown,
            coming = currentWindow?.coming.orEmpty(),
            played = currentWindow?.played.orEmpty(),
            ma = if (ma == null) null else maLink,
            favorites = player?.isMusicAssistant == true && (favoriteButton() != null || (ma != null && player.activeQueue != null)),
            place = placeOf(connection.config.value) ?: _state.value.place,
            weather = weather?.state?.takeIf { it != "unavailable" && it != "unknown" } ?: _state.value.weather.takeIf { weather == null },
            cloudCoverage = weather?.number("cloud_coverage") ?: _state.value.cloudCoverage.takeIf { weather == null },
            windKmh = weather?.let { w -> w.number("wind_speed")?.let { speed -> WeatherMood.kmh(speed, w.string("wind_speed_unit")) } }
                ?: _state.value.windKmh.takeIf { weather == null },
            entities = ownIds.mapNotNull { id -> entities[id]?.let { id to it } }.toMap(),
            precision = connection.registry.value?.mapNotNull { e -> e.displayPrecision?.takeIf { e.entityId in ownIds }?.let { e.entityId to it } }?.toMap().orEmpty(),
            temperatureUnit = temperatureUnitOf(connection.config.value) ?: _state.value.temperatureUnit,
            words = enriched?.track?.let { wordsOf(it, entity, currentWindow) },
        )
        // Until the new song is described, what was shown stays: the song before it, all of it.
        _state.value = if (player != null && key != null && awaitsDetails(player, key, t)) fresh.showing(_state.value) else fresh
    }

    /**
     * Whether the song [key] on [p] waits for Music Assistant's queue: Home Assistant reports a
     * new song first with a small picture (512 px) and without the album's year, and the queue
     * tells the rest a moment later. A song the queue has described or announced as next does not
     * wait, nor does a player whose queue does not answer; none waits longer than [detailsWaitMs].
     */
    private fun awaitsDetails(p: PlayerSnapshot, key: String, t: Long): Boolean {
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
                delay(detailsWaitMs)
                publish(connection.status.value, connection.entities.value)
            }
        }
        return t - awaitedSince < detailsWaitMs
    }

    /** This state with the player, its queue and its time as [shown] has them. */
    private fun SourceState.showing(shown: SourceState) = copy(
        player = shown.player,
        receivedAtMs = shown.receivedAtMs,
        next = shown.next,
        group = shown.group,
        queueKnown = shown.queueKnown,
        coming = shown.coming,
        played = shown.played,
        favorites = shown.favorites,
        words = shown.words,
    )

    /**
     * What Music Assistant knows of the words of [track], the song that plays: found by its URI,
     * which Home Assistant names as what plays, else by the queue's current song when that is
     * [track]. Null while nothing is known; without a URI (the link is down) what was known stays.
     */
    private fun wordsOf(track: TrackInfo, entity: EntityState?, w: QueueWindow?): SongWords? {
        val loader = lyrics ?: return null
        if (track.kind != MediaKind.MUSIC) return null
        val uri = entity?.string("media_content_id")?.takeIf { it.contains("://") }
            ?: w?.currentSong?.uri?.takeIf { w.current?.let { c -> sameTitle(c.title, track.title) } == true }
        val words = if (uri == null) lastWords?.takeIf { it.key == track.key } else loader.known(uri)?.let { SongWords(track.key, it.lyrics) }
        if (words != null) lastWords = words
        return words
    }

    /**
     * The song Music Assistant's queue announced as next, when [track] is it: described before
     * its own read (music only; a book's or an episode's chapters come with that read).
     */
    private fun announced(track: TrackInfo): TrackInfo? =
        queue?.next?.takeIf { it.kind == MediaKind.MUSIC && track.kind == MediaKind.MUSIC && sameTitle(it.title, track.title) }

    /** Home Assistant's latitude and longitude from its configuration. */
    private fun placeOf(cfg: JsonObject?): Pair<Double, Double>? {
        val lat = (cfg?.get("latitude") as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        val lon = (cfg["longitude"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        return if (lat == 0.0 && lon == 0.0) null else lat to lon
    }

    /** Home Assistant's unit for temperatures ("°C", "°F") from its configuration. */
    private fun temperatureUnitOf(cfg: JsonObject?): String? =
        ((cfg?.get("unit_system") as? JsonObject)?.get("temperature") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    /**
     * Who plays along: a sync group lists its members in `group_members` (on the leader and
     * on every member). A Music Assistant group player lists only its children, and its
     * members list nobody: they only play from the group's queue, which has its name.
     */
    private fun groupOf(p: PlayerSnapshot, otherNames: List<String?>, q: QueueInfo?): SpeakerGroup? = when {
        p.isGroupPlayer -> SpeakerGroup(self = null, others = otherNames, name = p.friendlyName)
        otherNames.isNotEmpty() -> SpeakerGroup(self = p.friendlyName, others = otherNames)
        else -> q?.name?.takeIf { SpeakerLabel.isOtherName(it, p.friendlyName) }
            ?.let { SpeakerGroup(self = p.friendlyName, others = emptyList(), name = it) }
    }

    /**
     * Prefer the queue's richer data when it describes the same track (or announced it as next,
     * before its own read); Music Assistant's own queue adds a book's authors, an episode's show,
     * the chapters and the speed.
     */
    private fun enrich(p: PlayerSnapshot): PlayerSnapshot {
        val track = p.track ?: return p
        if (track.kind == MediaKind.RADIO) return enrichStation(p, track)
        val known = queueForKey == trackKey(track)
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
        val known = queueForKey == trackKey(track)
        val mine = window?.current?.takeIf { known && it.kind == MediaKind.RADIO && sameTitle(it.title, track.title) }
        val item = queue?.current?.takeIf { known && it.kind == MediaKind.RADIO }
        val station = item?.source ?: mine?.source ?: track.source
        val album = track.album ?: mine?.album ?: track.source?.takeIf { known && station != null && !it.equals(station, ignoreCase = true) }
        val picture = stationPictures.choose(
            trackKey(track),
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

    /** Starts reading the queue anew; a read under way for an older track is canceled, a periodic one waits its turn. */
    private fun startQueueRead(force: Boolean) {
        synchronized(this) {
            if (force && queueJob?.isActive == true) return
            queueJob?.cancel()
            queueJob = scope.launch { refreshQueue(force) }
        }
    }

    private suspend fun refreshQueue(force: Boolean) {
        val key = lastPlayerKey ?: return
        if (!force && queueForKey == key) return
        delay(350) // let a burst of state changes settle
        if (lastPlayerKey != key) return
        val response = try {
            connection.callService("music_assistant", "get_queue", playerEntity, returnResponse = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        queueWorks = response != null
        // No answer (a source without a queue, like a streaming app playing on the speaker directly):
        // nothing is known to follow. A periodic refresh that fails keeps what is known.
        val again = force && queueForKey == key
        val parsed = response?.let { PlayerParser.queue(it, playerEntity, config.baseUrl) }
        if (parsed == null && again) return
        // Music Assistant's queue is read again only when something moved, or for spoken word,
        // whose speed can change at any time.
        val read = parsed?.let { q -> if (!again || windowStale(q)) readWindow(q) else window } ?: window.takeIf { again }
        if (lastPlayerKey != key) return
        queue = parsed
        window = read
        queueForKey = key
        // The words of the song that plays and of the one up next, as they come; never waited for.
        read?.let { w -> lyrics?.want(listOfNotNull(w.currentSong, w.nextSong)) }
        publish(connection.status.value, connection.entities.value)
        if (again || parsed == null) return
        // Music Assistant names the next item anew only when the player reports after the new one
        // has started: a queue replaced as it starts still names the old queue's next for a moment.
        delay(queueSettleMs)
        if (lastPlayerKey != key) return
        refreshQueue(force = true)
        // A book's or an episode's chapters may follow a moment after it starts (Music Assistant 2.11).
        val current = window?.current
        if (current != null && current.kind.spoken && current.chapters.isEmpty()) {
            delay(CHAPTERS_AGAIN_MS)
            if (lastPlayerKey == key) refreshQueue(force = true)
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
        val id = q.queueId ?: lastSnapshot?.activeQueue ?: return null
        val index = q.currentIndex ?: return null
        val (offset, limit) = MaQueue.range(index)
        return try {
            val w = MaQueue.window(client.items(id, offset, limit), offset, index, client.baseUrl)
            maLink = MaLink.CONNECTED
            windowReadAt = now()
            windowFor = Triple(q.queueId, q.currentIndex, q.next?.key)
            w.takeIf { a -> val b = q.current; a.current != null && b != null && (sameTitle(a.current.title, b.title) || sameStation(a.current, b)) }
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

    /** Music Assistant's media players, as the entity registry lists them. */
    private fun maSpeakers(): Set<String> =
        connection.registry.value?.filter { it.platform == MA_PLATFORM && it.entityId.startsWith("media_player.") }
            ?.map { it.entityId }?.toSet().orEmpty()

    /**
     * All of the house's speakers play together (and there are at least [EVERYWHERE_MIN]): the
     * group is as large as the number of Music Assistant speakers (groups not counted) that are
     * not unavailable.
     */
    private fun isEverywhere(g: SpeakerGroup, entities: Map<String, EntityState>): Boolean {
        if (g.name != null) return false
        val all = maSpeakers().count { id ->
            val e = entities[id] ?: return@count false
            e.state != "unavailable" && e.string("mass_player_type") == "player"
        }
        return all >= EVERYWHERE_MIN && g.others.size + 1 >= all
    }

    private fun trackKey(t: TrackInfo) = norm(t.title) + "|" + norm(t.artists.joinToString())

    companion object {
        const val QUEUE_REFRESH_MS = 60_000L

        /** Music Assistant's players report about once a second while they play; this leaves a margin. */
        const val QUEUE_SETTLE_MS = 2_500L

        /** Music Assistant's queue is read again after this long even when nothing seems to have moved. */
        const val WINDOW_MAX_AGE_MS = 5 * 60_000L

        /** A book without chapters is asked once more after this long. */
        const val CHAPTERS_AGAIN_MS = 10_000L

        /** Music Assistant's integration in Home Assistant's entity registry. */
        const val MA_PLATFORM = "music_assistant"

        /** The translation key of Music Assistant's "Favorite current song" button. */
        const val FAVORITE_KEY = "favorite_now_playing"

        /** "Everywhere" needs at least this many speakers playing together. */
        const val EVERYWHERE_MIN = 3

        /** A button's call that Home Assistant has not answered after this long is still running, and counts as taken. */
        const val ACTION_ANSWER_MS = 4_000L

        /** A new song waits at most this long for Music Assistant's queue to describe it. */
        const val DETAILS_WAIT_MS = 2_000L

        /**
         * The service for [command] on player [p], or null when the player does not support it
         * (https://developers.home-assistant.io/docs/core/entity/media-player). Play and pause
         * go as one toggle where the player supports both, else as the one it supports.
         */
        fun serviceFor(command: RemoteCommand, p: PlayerSnapshot): String? {
            val a = p.abilities
            return when (command) {
                RemoteCommand.PLAY_PAUSE -> when {
                    a.play && a.pause -> "media_play_pause"
                    p.status == PlayerStatus.PLAYING -> "media_pause".takeIf { a.pause }
                    else -> "media_play".takeIf { a.play }
                }
                RemoteCommand.PLAY -> "media_play".takeIf { a.play }
                RemoteCommand.PAUSE -> "media_pause".takeIf { a.pause }
                RemoteCommand.NEXT -> "media_next_track".takeIf { a.next }
                RemoteCommand.PREVIOUS -> "media_previous_track".takeIf { a.previous }
                RemoteCommand.VOLUME_UP -> "volume_up".takeIf { a.volume }
                RemoteCommand.VOLUME_DOWN -> "volume_down".takeIf { a.volume }
            }
        }

        fun norm(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).filter { it.isLetterOrDigit() }

        /** Two descriptions of one station (the song each names may differ by a moment). */
        fun sameStation(a: TrackInfo, b: TrackInfo): Boolean =
            a.kind == MediaKind.RADIO && b.kind == MediaKind.RADIO && a.source != null && a.source == b.source

        fun sameTitle(a: String, b: String): Boolean {
            val x = norm(a)
            val y = norm(b)
            return x == y || (x.isNotEmpty() && y.isNotEmpty() && (x.startsWith(y) || y.startsWith(x)))
        }
    }
}
