package io.github.hazymorning.klangbild.domain

/** How the connection to Home Assistant stands, phrased for the UI, never as raw errors. */
enum class LinkState {
    /** No Home Assistant configured yet. */
    UNCONFIGURED,
    CONNECTING,
    CONNECTED,
    /** Lost the connection, retrying with backoff. */
    RECONNECTING,
    /** Home Assistant refused the token. */
    AUTH_FAILED,
}

/** How the optional connection to Music Assistant stands, for the menu, never as raw errors. */
enum class MaLink {
    CONNECTED,

    /** Music Assistant refused the token (expired after a year, or deleted). */
    AUTH_FAILED,

    /** No answer, or not the answer of a Music Assistant server. */
    UNREACHABLE,
}

/** Everything the source knows right now, as plain data; [ScreenStateMachine] decides what it means over time. */
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
    /** Local time since which the connection is not [LinkState.CONNECTED] (for grace periods). */
    val linkDownSinceMs: Long? = null,
    /** The speakers playing in sync with this one; null while it plays alone. */
    val group: SpeakerGroup? = null,
    /** The queue around the current track is known ([next], and with Music Assistant [coming] and [played]). */
    val queueKnown: Boolean = false,
    /** What follows the current track and what was played before it (most recent last), from Music Assistant. */
    val coming: List<TrackInfo> = emptyList(),
    val played: List<TrackInfo> = emptyList(),
    /** The optional connection to Music Assistant; null when it is not set up. */
    val ma: MaLink? = null,
    /** What plays can be added to Music Assistant's favorites. */
    val favorites: Boolean = false,
    /**
     * The states of the entities that custom buttons, their conditions and custom values use, as
     * far as Home Assistant has them.
     */
    val entities: Map<String, EntityState> = emptyMap(),
    /** The display precision (decimals) Home Assistant sets for some of [entities], by entity id. */
    val precision: Map<String, Int> = emptyMap(),
    /** Home Assistant's unit for temperatures ("°C"), for a thermostat's attributes; null until known. */
    val temperatureUnit: String? = null,
    /** What Music Assistant knows of the lyrics of the track that plays; null while nothing is known (or without Music Assistant). */
    val lyrics: SongLyrics? = null,
    /** A party of Music Assistant's Party plugin plays to this speaker, and its guests may join. */
    val party: Boolean = false,
    /** The entity of the speaker shown: the one chosen, or this TV's own while music plays on it. */
    val speaker: String? = null,
    /** This TV's own speaker in Home Assistant, once it plays for Music Assistant and Home Assistant has it. */
    val ownSpeaker: String? = null,
)
