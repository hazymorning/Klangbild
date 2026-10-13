package io.github.hazymorning.klangbild.data

import io.github.hazymorning.klangbild.config.QuickAction
import io.github.hazymorning.klangbild.data.ha.EntityState
import io.github.hazymorning.klangbild.data.ma.MaLink
import io.github.hazymorning.klangbild.domain.PlayerSnapshot
import io.github.hazymorning.klangbild.domain.SongLyrics
import io.github.hazymorning.klangbild.domain.SpeakerGroup
import io.github.hazymorning.klangbild.domain.TrackInfo
import kotlinx.coroutines.flow.StateFlow

/** How healthy the link to the music is, phrased for the UI, never as raw errors. */
enum class LinkState {
    /** No Home Assistant configured yet. */
    UNCONFIGURED,
    CONNECTING,
    CONNECTED,
    /** Lost the connection, retrying with backoff. */
    RECONNECTING,
    /** Home Assistant rejected the token. */
    AUTH_FAILED,
}

/** Everything a source knows right now. Pure data; time-based logic lives in the reducer. */
data class SourceState(
    val link: LinkState,
    /** Null until the entity has been seen. */
    val player: PlayerSnapshot? = null,
    /** True when connected but the configured entity does not exist. */
    val entityMissing: Boolean = false,
    val next: TrackInfo? = null,
    val sunElevation: Double? = null,
    /** Local time the player snapshot was received (for extrapolation without timestamps). */
    val receivedAtMs: Long = 0,
    /** Local minus server clock. */
    val skewMs: Long = 0,
    /** Local time since which the link is not [LinkState.CONNECTED] (for grace periods). */
    val linkDownSinceMs: Long? = null,
    /** The speakers playing in sync with this one; null while it plays alone. */
    val group: SpeakerGroup? = null,
    /** The queue around the current track is known ([next], and with Music Assistant [coming] and [played]). */
    val queueKnown: Boolean = false,
    /** What follows the current track and what was played before it (most recent last), from Music Assistant. */
    val coming: List<TrackInfo> = emptyList(),
    val played: List<TrackInfo> = emptyList(),
    /** The optional link to Music Assistant; null when it is not set up. */
    val ma: MaLink? = null,
    /** Where Home Assistant stands (degrees), from its configuration; null until known. */
    val place: Pair<Double, Double>? = null,
    /** The weather entity's state (`sunny`, `cloudy` and so on), its cloud cover in % and its wind in km/h, as far as known. */
    val weather: String? = null,
    val cloudCoverage: Double? = null,
    val windKmh: Double? = null,
    /** What plays can be added to Music Assistant's favorites. */
    val favorites: Boolean = false,
    /**
     * The entities of one's own as far as Home Assistant has them: what the quick settings'
     * buttons work and look at, and the values in the status line.
     */
    val entities: Map<String, EntityState> = emptyMap(),
    /** How many decimals Home Assistant shows those of them that are sensors with a precision of their own. */
    val precision: Map<String, Int> = emptyMap(),
    /** Home Assistant's unit for temperatures ("°C"), for a thermostat's attributes; null until known. */
    val temperatureUnit: String? = null,
    /** What Music Assistant knows of the words of the song that plays; null while nothing is known (or without Music Assistant). */
    val lyrics: SongLyrics? = null,
    /** A party of Music Assistant's Party plugin plays to this speaker, and its guests may join. */
    val party: Boolean = false,
)

enum class RemoteCommand { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

interface NowPlayingSource {
    val state: StateFlow<SourceState>
    fun start()
    fun stop()
    suspend fun send(command: RemoteCommand)

    /** Plays on from [positionMs] in the item's own time. */
    suspend fun seek(positionMs: Long)

    /** Adds what plays to Music Assistant's favorites (a station's song as the song); whether it worked. */
    suspend fun favorite(): Boolean = false

    /** Turns shuffle on or off; whether the call went out. */
    suspend fun shuffle(on: Boolean): Boolean = false

    /** Follows the entities of one's own (the buttons', their conditions', the values'), for [SourceState.entities]. */
    fun followEntities(ids: Set<String>) = Unit

    /** Does what [action] says in Home Assistant; whether Home Assistant took it. */
    suspend fun run(action: QuickAction): Boolean = false

    /** The link guests open to join the party ([SourceState.party]); null when none can be had. */
    suspend fun partyUrl(): String? = null
}
