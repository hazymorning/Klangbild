package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.Backoff
import io.github.hazymorning.klangbild.data.LightBridge
import io.github.hazymorning.klangbild.data.NowPlayingSource
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.data.ma.MaClient
import io.github.hazymorning.klangbild.data.ma.MaUnknownCommandException
import io.github.hazymorning.klangbild.domain.ActionKind
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.domain.EntityState
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.PlayerSnapshot
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.QueueInfo
import io.github.hazymorning.klangbild.domain.QuickAction
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.SpeakerGroup
import io.github.hazymorning.klangbild.domain.SpeakerLabel
import io.github.hazymorning.klangbild.domain.SpeakerStatus
import io.github.hazymorning.klangbild.domain.SpeakerSwitch
import io.ktor.client.HttpClient
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.math.pow
import kotlin.time.Clock

/**
 * Home Assistant as the source: the configured media_player plus `sun.sun`, the weather and
 * the entities of one's own, enriched with what its queue says ([QueueReader]: what comes next,
 * full-size artwork, with Music Assistant itself the whole queue and the words of the song).
 * Remote commands go out only where the player supports them.
 *
 * While this TV plays as a speaker ([speaker]), its own entity ([OwnSpeaker]) is followed too,
 * and music that starts on it shows instead of the chosen speaker until it has stopped
 * ([SpeakerSwitch]), on the same connection.
 */
class HaSource(
    http: HttpClient,
    private val scope: CoroutineScope,
    private val config: HaConfig,
    /** The speaker chosen: an entity, or [EntityId.THIS_TV] for this TV's own. */
    private val playerEntity: String,
    private val sunEntity: String = "sun.sun",
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ma: MaClient? = null,
    /** The weather entities to read the sky's cover from, the first that exists counts (none: no weather). */
    private val weatherEntities: List<String> = emptyList(),
    /** How often the queue is read again while music plays (shorter in tests). */
    queueRefreshMs: Long = QueueReader.QUEUE_REFRESH_MS,
    /** How often Music Assistant is asked whether a party runs (shorter in tests). */
    private val partyMs: Long = PARTY_MS,
    /** How long a button's call may go unanswered before it counts as still running (shorter in tests). */
    private val actionAnswerMs: Long = ACTION_ANSWER_MS,
    /** How long a new song may wait for Music Assistant's queue to describe it (shorter in tests). */
    detailsWaitMs: Long = QueueReader.DETAILS_WAIT_MS,
    /** How long after a new song's first queue read the queue is read once more (shorter in tests). */
    queueSettleMs: Long = QueueReader.QUEUE_SETTLE_MS,
    /** How often Music Assistant itself is asked what comes next while a song plays (shorter in tests). */
    maQueueMs: Long = QueueReader.MA_QUEUE_MS,
    /** This TV as a speaker, as far as it plays (with Music Assistant set up). */
    speaker: StateFlow<SpeakerStatus>? = null,
    /** How long the first wait is before Music Assistant and Home Assistant are asked again for this TV's own speaker (shorter in tests). */
    ownRetryMs: Long = OwnSpeaker.FIRST_RETRY_MS,
    /** How long this TV's own speaker shows after its music has stopped (shorter in tests). */
    ownBackMs: Long = SpeakerSwitch.BACK_MS,
) : NowPlayingSource, LightBridge {

    private val connection = HaConnection(http, scope)

    /** What changes together changes under this lock, the queue's reader's too. */
    private val lock = SynchronizedObject()
    private val _state = MutableStateFlow(SourceState(link = LinkState.CONNECTING))
    override val state: StateFlow<SourceState> = _state.asStateFlow()

    private var collector: Job? = null

    /** Asks Music Assistant now and then whether a party runs ([partyQueue]). */
    private var partyPoller: Job? = null

    /** The queue a party of Music Assistant's Party plugin plays to, while its guests may join. */
    @Volatile private var partyQueue: String? = null

    /** This TV's own speaker, once it plays and Home Assistant has it. */
    private val own: OwnSpeaker? = if (ma != null && speaker != null) {
        OwnSpeaker(scope, connection, ma, speaker, { Backoff(ownRetryMs, OwnSpeaker.LAST_RETRY_MS) }) { publish(connection.status.value, connection.entities.value) }
    } else {
        null
    }
    private val switch = SpeakerSwitch(ownBackMs)

    /** The speaker shown: the one chosen, or this TV's own while music plays on it; none while "This TV" is chosen and not found. */
    @Volatile private var speakerShown: String? = playerEntity.takeIf { it != EntityId.THIS_TV }

    /** Publishes once more when this TV's own speaker gives the screen back. */
    private var backJob: Job? = null
    private var backAt: Long? = null

    private val queue = QueueReader(
        scope, connection, { speakerShown }, config.baseUrl, ma, now,
        latest = { lastSnapshot }, shown = { _state.value.player }, lock = lock,
        queueRefreshMs = queueRefreshMs, detailsWaitMs = detailsWaitMs, queueSettleMs = queueSettleMs, maQueueMs = maQueueMs,
    ) { publish(connection.status.value, connection.entities.value) }

    private var linkDownSince: Long? = null
    private var lastSnapshot: PlayerSnapshot? = null
    private var receivedAt = 0L
    private var watched: Set<String> = emptySet()

    /** The lights that join in, followed for `LightSync` alongside the group's speakers. */
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
            val player = speakerShown?.let { mapOf("player" to JsonPrimitive(it)) }.orEmpty()
            buildJsonObject { put("variables", JsonObject(player + action.variables)) }
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
        connection.start(config, listOfNotNull(playerEntity.takeIf { it != EntityId.THIS_TV }, sunEntity) + weatherEntities)
        collector = scope.launch {
            combine(connection.status, connection.entities, connection.registry, connection.config, connection.missing) { st, ents, _, _, _ -> st to ents }
                .collect { (st, ents) -> publish(st, ents) }
        }
        partyPoller = ma?.let { client -> scope.launch { followParty(client) } }
        queue.start()
        own?.start()
    }

    override fun stop() {
        collector?.cancel()
        own?.stop()
        backJob?.cancel()
        queue.stop()
        partyPoller?.cancel()
        connection.stop()
    }

    /**
     * Whether a party of Music Assistant's Party plugin runs, and to which queue: asked now and
     * then. An answer that fails twice in a row counts as no party; a server without the plugin
     * is not asked again.
     */
    private suspend fun followParty(client: MaClient) {
        var failed = 0
        while (true) {
            val queue = try {
                client.partyPlayer().also { failed = 0 }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: MaUnknownCommandException) {
                return
            } catch (_: Exception) {
                partyQueue.takeIf { ++failed < PARTY_FAILURES }
            }
            if (queue != partyQueue) {
                partyQueue = queue
                publish(connection.status.value, connection.entities.value)
            }
            delay(partyMs)
        }
    }

    override suspend fun partyUrl(): String? = try {
        ma?.partyUrl()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The network came back: do not wait for the backoff timer. */
    fun networkAvailable() = connection.retryNow()

    /** Sends [command] as the player's service, only when the player supports it. */
    override suspend fun send(command: RemoteCommand) {
        val p = lastSnapshot ?: return
        val service = serviceFor(command, p) ?: return
        val entity = speakerShown ?: return
        attempt { connection.callService("media_player", service, entity) }
    }

    /**
     * Adds what plays to Music Assistant's favorites: through the speaker's own button in Home
     * Assistant ("Favorite current song"), else through Music Assistant itself. A station's song
     * is looked up as a song by Music Assistant; it fails when it cannot find one.
     */
    override suspend fun favorite(): Result<Unit> {
        val button = favoriteButton()
        if (button != null) return tried { connection.callService("button", "press", button) }
        val client = ma ?: return Result.failure(IllegalStateException("No favorite button and no Music Assistant"))
        val player = lastSnapshot?.activeQueue ?: return Result.failure(IllegalStateException("No queue to favorite from"))
        return tried { client.favoriteNowPlaying(player) }
    }

    /** Runs [block]; whether it worked. Only a cancellation passes. */
    private suspend fun attempt(block: suspend () -> Unit): Boolean = tried(block).isSuccess

    /** Runs [block]; what went wrong, if anything. Only a cancellation passes. */
    private suspend fun tried(block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * The speaker's own button that adds what plays to Music Assistant's favorites ("Favorite
     * current song"), found in the entity registry: same device, Music Assistant's translation
     * key (its entity id depends on the language). Null without one.
     */
    private fun favoriteButton(): String? {
        val registry = connection.registry.value ?: return null
        val device = registry.firstOrNull { it.entityId == speakerShown }?.deviceId ?: return null
        return registry.firstOrNull {
            it.deviceId == device && it.platform == MA_PLATFORM && it.translationKey == FAVORITE_KEY && it.entityId.startsWith("button.")
        }?.entityId
    }

    override suspend fun shuffle(on: Boolean): Boolean {
        if (lastSnapshot?.abilities?.shuffle != true) return false
        val entity = speakerShown ?: return false
        val data = buildJsonObject { put("shuffle", on) }
        return attempt { connection.callService("media_player", "shuffle_set", entity, data = data) }
    }

    /** Plays on from [positionMs] in the item's own time (`seek_position` in seconds). */
    override suspend fun seek(positionMs: Long) {
        if (lastSnapshot?.abilities?.seek != true) return
        val entity = speakerShown ?: return
        val data = buildJsonObject { put("seek_position", positionMs.coerceAtLeast(0) / 1000.0) }
        attempt { connection.callService("media_player", "media_seek", entity, data = data) }
    }

    /** Called from the status/entity collector and after queue refreshes, serialized. */
    private fun publish(status: HaStatus, entities: Map<String, EntityState>): Unit = synchronized(lock) {
        val t = now()
        val link = when (status) {
            HaStatus.Connected -> LinkState.CONNECTED
            is HaStatus.AuthFailed -> LinkState.AUTH_FAILED
            is HaStatus.Connecting -> if (lastSnapshot == null && linkDownSince == null) LinkState.CONNECTING else LinkState.RECONNECTING
            is HaStatus.Waiting -> LinkState.RECONNECTING
            HaStatus.Stopped -> LinkState.CONNECTING
        }
        linkDownSince = if (link == LinkState.CONNECTED) null else (linkDownSince ?: t)

        var player = follow(entities, t)
        val entity = speakerShown?.let { entities[it] }
        if (player != null && player != lastSnapshot) {
            receivedAt = t
            lastSnapshot = player
        }
        player = player ?: lastSnapshot.takeIf { link != LinkState.CONNECTED }

        val key = player?.track?.let { TrackMatch.key(it) }
        if (key != null) queue.follow(key, player)

        // One estimate of Home Assistant's clock for the whole state: the position is published on it.
        val skew = connection.clockSkew.offsetMs
        val enriched = player?.let { queue.enrich(it, skew) }
        val sun = entities[sunEntity]?.number("elevation")
        val weather = weatherEntities.firstNotNullOfOrNull { entities[it] }
        // The other speakers of a group are followed too, for their names, and so are the
        // lights; in a larger group also every Music Assistant speaker, to tell "everywhere".
        val others = player?.groupMembers.orEmpty()
        val follow = others.toSet() + lightIds + ownIds + setOfNotNull(own?.entity) + (if (others.size + 1 >= EVERYWHERE_MIN) maSpeakers() else emptySet())
        if (follow != watched) {
            watched = follow
            connection.watch(watched)
        }
        val lightStates = lightIds.mapNotNull { id -> entities[id]?.let { id to it } }.toMap()
        if (lightStates != _lights.value) _lights.value = lightStates
        val queueKnown = queue.knows(key)
        val currentQueue = queue.queueFor(key)
        val currentWindow = queue.windowFor(key)
        val fresh = SourceState(
            link = link,
            player = enriched,
            entityMissing = status == HaStatus.Connected && entity == null && speakerShown in connection.missing.value,
            next = queue.nextOf(currentQueue, key),
            sunElevation = sun ?: _state.value.sunElevation,
            receivedAtMs = receivedAt,
            skewMs = skew,
            linkDownSinceMs = linkDownSince,
            group = player?.let { groupOf(it, others.map { id -> entities[id]?.string("friendly_name") }, currentQueue) }
                ?.let { g -> if (isEverywhere(g, entities)) g.copy(everywhere = true) else g },
            queueKnown = queueKnown,
            coming = currentWindow?.coming.orEmpty(),
            played = currentWindow?.played.orEmpty(),
            remaining = queue.remainingOf(currentQueue, key),
            ma = if (ma == null) null else queue.maLink,
            favorites = player?.isMusicAssistant == true && (favoriteButton() != null || (ma != null && player.activeQueue != null)),
            place = placeOf(connection.config.value) ?: _state.value.place,
            weather = weather?.state?.takeIf { it != "unavailable" && it != "unknown" } ?: _state.value.weather.takeIf { weather == null },
            cloudCoverage = weather?.number("cloud_coverage") ?: _state.value.cloudCoverage.takeIf { weather == null },
            windKmh = weather?.let { w -> w.number("wind_speed")?.let { speed -> kmh(speed, w.string("wind_speed_unit")) } }
                ?: _state.value.windKmh.takeIf { weather == null },
            entities = ownIds.mapNotNull { id -> entities[id]?.let { id to it } }.toMap(),
            precision = connection.registry.value?.mapNotNull { e -> e.displayPrecision?.takeIf { e.entityId in ownIds }?.let { e.entityId to it } }?.toMap().orEmpty(),
            temperatureUnit = temperatureUnitOf(connection.config.value) ?: _state.value.temperatureUnit,
            lyrics = enriched?.track?.let { queue.lyricsOf(it, entity, currentWindow) },
            party = partyQueue?.let { q -> q == player?.activeQueue } == true,
            speaker = speakerShown,
            ownSpeaker = own?.entity,
        )
        // Until the new song is described, what was shown stays: the song before it, all of it.
        _state.value = if (player != null && key != null && queue.awaitsDetails(player, key, t)) fresh.showing(_state.value) else fresh
    }

    /**
     * Which speaker shows ([speakerShown]): the one chosen, or this TV's own while music plays on it
     * ([SpeakerSwitch]). Another one starts afresh: its own snapshot, its own queue. Returns what
     * the speaker shown plays.
     */
    private fun follow(entities: Map<String, EntityState>, t: Long): PlayerSnapshot? {
        val ownId = own?.entity
        val chosen = if (playerEntity == EntityId.THIS_TV) ownId else playerEntity
        val here = ownId?.takeIf { it != chosen }
        val chosenPlayer = chosen?.let { entities[it] }?.let { PlayerParser.player(it, config.baseUrl) }
        val herePlayer = here?.let { entities[it] }?.let { PlayerParser.player(it, config.baseUrl) }
        val showsOwn = if (here == null) {
            switch.reset()
            false
        } else {
            switch.update(herePlayer, chosenPlayer, t)
        }
        // Stopped, it stays a moment: the state is published once more when that is over.
        val back = switch.backAt
        if (back != backAt) {
            backAt = back
            backJob?.cancel()
            backJob = back?.let { at ->
                scope.launch {
                    delay((at - now()).coerceAtLeast(0))
                    publish(connection.status.value, connection.entities.value)
                }
            }
        }
        val next = if (showsOwn) here else chosen
        if (next != speakerShown) {
            speakerShown = next
            lastSnapshot = null
            queue.retarget()
        }
        return if (showsOwn) herePlayer else chosenPlayer
    }

    /** This state with the player, its queue and its time as [shown] has them, and the speaker they belong to. */
    private fun SourceState.showing(shown: SourceState) = copy(
        speaker = shown.speaker,
        player = shown.player,
        receivedAtMs = shown.receivedAtMs,
        next = shown.next,
        group = shown.group,
        queueKnown = shown.queueKnown,
        coming = shown.coming,
        played = shown.played,
        remaining = shown.remaining,
        favorites = shown.favorites,
        lyrics = shown.lyrics,
    )

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

    companion object {
        /** Music Assistant is asked this often whether a party runs. */
        const val PARTY_MS = 60_000L

        /** After this many failed questions in a row, a party counts as over. */
        const val PARTY_FAILURES = 2

        /** Music Assistant's integration in Home Assistant's entity registry. */
        const val MA_PLATFORM = "music_assistant"

        /** The translation key of Music Assistant's "Favorite current song" button. */
        const val FAVORITE_KEY = "favorite_now_playing"

        /** "Everywhere" needs at least this many speakers playing together. */
        const val EVERYWHERE_MIN = 3

        /** A button's call that Home Assistant has not answered after this long is still running, and counts as taken. */
        const val ACTION_ANSWER_MS = 4_000L

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
    }
}

/**
 * [value] of a wind speed in [unit] in km/h; null for a unit the weather entity cannot have.
 * The units are Home Assistant's `UnitOfSpeed` values a weather entity may use; without a
 * unit the speed is taken as km/h, the unit of the metric system.
 */
internal fun kmh(value: Double, unit: String?): Double? = when (unit) {
    null, "km/h" -> value
    "m/s" -> value * 3.6
    "mph" -> value * 1.609344
    "kn" -> value * 1.852
    "ft/s" -> value * 1.09728
    // Beaufort's own formula: v = 0.836 B^1.5 m/s.
    "Beaufort" -> 0.836 * 3.6 * value.coerceAtLeast(0.0).pow(1.5)
    else -> null
}
