package io.github.hazymorning.klangbild.domain


/** Why nothing is on stage. Each reason has its own quiet line of text. */
enum class RestReason {
    /** Nothing plays (idle, off, stopped). */
    IDLE,
    /** Paused for a while; the stage has made way for the rest screen. */
    PAUSED,
    /** First connection attempt in progress. */
    CONNECTING,
    /** Connection lost for longer than the grace period. */
    OFFLINE,
    /** The speaker entity reports `unavailable`. */
    UNAVAILABLE,
    /** The configured entity does not exist in Home Assistant. */
    ENTITY_MISSING,
}

enum class SetupReason { FIRST_RUN, AUTH_FAILED, REQUESTED }

/** What the screen shows. */
sealed interface Screen {
    data class Stage(
        val track: TrackInfo,
        val status: PlayerStatus,
        val next: TrackInfo?,
        /**
         * The connection has been gone for longer than the grace period: the stage stays,
         * dimmed like a pause, and says so until [Timing.linkGiveUpMs].
         */
        val linkDown: Boolean = false,
    ) : Screen

    data class Rest(val reason: RestReason, val speaker: String? = null) : Screen

    data class Setup(val reason: SetupReason) : Screen
}

/** Settings the state machine needs (subset of the app settings). */
data class Timing(
    /** After this long without music the screen is released (or the app closes). */
    val sleepAfterMs: Long = 10 * 60_000L,
    /** A paused stage gives way to the rest screen after this long. */
    val pauseToRestMs: Long = 3 * 60_000L,
    /** Keep showing the stage unchanged this long after the connection dropped. */
    val linkGraceMs: Long = 20_000L,
    /** After this long without connection the rest screen explains what is going on. */
    val linkGiveUpMs: Long = 120_000L,
    /**
     * Between two songs a player may say for a moment that nothing plays (or play without a
     * title). The stage stays this long before it makes way for the rest screen, so a skip never
     * passes through it.
     */
    val betweenSongsMs: Long = 4_000L,
)

data class ScreenDecision(
    val screen: Screen,
    /** Nothing has played for the sleep timeout: the screen may turn off, or the app close. */
    val asleep: Boolean,
)

/**
 * Turns the source state plus time into what the screen shows. Pure apart from the one
 * piece of memory it needs: since when no music has been playing.
 */
class ScreenStateMachine(val timing: Timing = Timing()) {
    private var notPlayingSince: Long? = null
    private var setupRequested = false

    /** The last stage with music playing, and when it was last seen: what stays between two songs. */
    private var lastPlaying: Screen.Stage? = null
    private var lastPlayingAt = 0L

    fun requestSetup(show: Boolean) {
        setupRequested = show
    }

    val isSetupRequested: Boolean get() = setupRequested

    /** [holdPause]: a paused stage shows something to be seen (a party's code) and does not turn into the rest screen. */
    fun decide(source: SourceState, now: Long, holdPause: Boolean = false): ScreenDecision {
        val raw = screenFor(source, now)
        val screen = when {
            raw is Screen.Stage && raw.status != PlayerStatus.PAUSED -> raw.also {
                lastPlaying = it
                lastPlayingAt = now
            }
            raw is Screen.Stage -> raw.also { lastPlaying = null }
            // Nothing plays for a moment right after a song played: the stage stays.
            raw is Screen.Rest && raw.reason == RestReason.IDLE && now - lastPlayingAt < timing.betweenSongsMs -> lastPlaying ?: raw
            else -> raw.also { if (raw !is Screen.Rest || raw.reason != RestReason.IDLE) lastPlaying = null }
        }
        val playing = screen is Screen.Stage &&
            (screen.status == PlayerStatus.PLAYING || screen.status == PlayerStatus.BUFFERING)
        notPlayingSince = if (playing) null else (notPlayingSince ?: now)
        val idleFor = notPlayingSince?.let { now - it } ?: 0L
        // Setup gets more time (someone is typing on a phone), but never forever (OLED).
        val limit = if (screen is Screen.Setup) timing.sleepAfterMs * 3 else timing.sleepAfterMs
        val asleep = !playing && idleFor >= limit
        // A paused stage that has waited long enough becomes the rest screen.
        val shown = if (!holdPause && screen is Screen.Stage && screen.status == PlayerStatus.PAUSED && idleFor >= timing.pauseToRestMs) {
            Screen.Rest(RestReason.PAUSED)
        } else {
            screen
        }
        return ScreenDecision(shown, asleep)
    }

    private fun screenFor(s: SourceState, now: Long): Screen {
        if (setupRequested) return Screen.Setup(SetupReason.REQUESTED)
        when (s.link) {
            LinkState.UNCONFIGURED -> return Screen.Setup(SetupReason.FIRST_RUN)
            LinkState.AUTH_FAILED -> return Screen.Setup(SetupReason.AUTH_FAILED)
            else -> Unit
        }
        val player = s.player
        val speaker = player?.friendlyName
        val linkDown = s.link == LinkState.CONNECTING || s.link == LinkState.RECONNECTING
        if (linkDown) {
            if (player == null) return Screen.Rest(RestReason.CONNECTING)
            val downFor = s.linkDownSinceMs?.let { now - it } ?: 0L
            val track = player.track
            return if (track != null && player.status in stagedStatuses && downFor < timing.linkGiveUpMs) {
                Screen.Stage(track, player.status, s.next, linkDown = downFor >= timing.linkGraceMs)
            } else {
                Screen.Rest(RestReason.OFFLINE, speaker)
            }
        }
        if (s.entityMissing) return Screen.Rest(RestReason.ENTITY_MISSING)
        if (player == null) return Screen.Rest(RestReason.CONNECTING)
        if (player.status == PlayerStatus.UNAVAILABLE) return Screen.Rest(RestReason.UNAVAILABLE, speaker)
        val track = player.track
        if (track != null && player.status in stagedStatuses) {
            return Screen.Stage(track, player.status, s.next)
        }
        return Screen.Rest(RestReason.IDLE, speaker)
    }

    companion object {
        private val stagedStatuses = setOf(PlayerStatus.PLAYING, PlayerStatus.PAUSED, PlayerStatus.BUFFERING)
    }
}
