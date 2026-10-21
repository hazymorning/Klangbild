package io.github.hazymorning.klangbild.domain

/** Playback state of the speaker, independent of Home Assistant's string values. */
enum class PlayerStatus { PLAYING, PAUSED, BUFFERING, IDLE, OFF, UNAVAILABLE, UNKNOWN }

/**
 * One consistent view of the speaker. [positionMs] was valid at [positionAtEpochMs]
 * (wall clock of the *server*); the UI extrapolates from there.
 */
data class PlayerSnapshot(
    val status: PlayerStatus,
    val track: TrackInfo?,
    val positionMs: Long? = null,
    val positionAtEpochMs: Long? = null,
    val friendlyName: String? = null,
    /** Music Assistant queue id when MA drives the player (for get_queue enrichment). */
    val activeQueue: String? = null,
    val isMusicAssistant: Boolean = false,
    /** Entity ids of the other speakers playing in sync with this one (this one excluded). */
    val groupMembers: List<String> = emptyList(),
    /** Volume 0..1, null when the player does not report one (a group: the members' average). */
    val volume: Float? = null,
    val muted: Boolean = false,
    /** The entity is a Music Assistant group player ("Ground floor"), not a speaker. */
    val isGroupPlayer: Boolean = false,
    /**
     * How fast the item plays (a book at 1.5×). Only Music Assistant itself knows this; the
     * position runs at this pace.
     */
    val speed: Float = 1f,
    /** Shuffle on or off; null when the player cannot shuffle. */
    val shuffle: Boolean? = null,
    /** What the player lets the remote do. */
    val abilities: PlayerAbilities = PlayerAbilities(),
)

/**
 * What a player lets the remote do, from Home Assistant's supported features: only what it
 * supports is offered and called. A player that does not say is taken to support everything.
 */
data class PlayerAbilities(
    val play: Boolean = true,
    val pause: Boolean = true,
    val seek: Boolean = true,
    val next: Boolean = true,
    val previous: Boolean = true,
    /** Volume up and down (by steps, or by setting the level). */
    val volume: Boolean = true,
    val shuffle: Boolean = true,
)

/** The speakers playing together with the configured one. */
data class SpeakerGroup(
    /** Name of the configured speaker. */
    val self: String?,
    /** Names of the others, in Home Assistant's order; null where a name is not known (yet). */
    val others: List<String?>,
    /** The group's own name, when it has one (a Music Assistant group player). */
    val name: String? = null,
    /** Every speaker of the house plays along. */
    val everywhere: Boolean = false,
)

/** What Music Assistant's queue adds on top of the player entity. */
data class QueueInfo(
    /** Identity of the current item, to match it against the player state. */
    val currentTitle: String?,
    val current: TrackInfo?,
    val next: TrackInfo?,
    /**
     * Name of the queue the speaker plays from: its own, or that of a group player it
     * plays in, whose members do not list each other in `group_members`.
     */
    val name: String? = null,
    /** Music Assistant's id of the queue and the current item's place in it (to read the whole queue). */
    val queueId: String? = null,
    val currentIndex: Int? = null,
    /** How many items the queue holds in all. */
    val items: Int? = null,
)
