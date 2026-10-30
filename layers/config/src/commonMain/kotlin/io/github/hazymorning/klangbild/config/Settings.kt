package io.github.hazymorning.klangbild.config

import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.domain.OwnValue
import io.github.hazymorning.klangbild.domain.QuickAction
import kotlin.math.roundToInt

/** What to do once the sleep timeout has passed without music. */
enum class SleepAction {
    /** Clear "keep screen on": the TV's own screensaver and sleep timer take over. */
    RELEASE,
    /** Close the app (back to the TV's home screen). */
    CLOSE,
}

/** When the speaker's volume appears on the stage. */
enum class VolumeDisplay {
    /** For a few seconds after it changed (wherever it was changed). */
    ON_CHANGE,
    /** Always, next to the clock. */
    ALWAYS,
    OFF,
}

/** How strongly the artwork lights the wall around it (a factor on the light's luminance). */
enum class Intensity(val factor: Float) {
    STRONG(1.45f),
    NORMAL(1f),
    SUBTLE(0.5f),
    OFF(0f),
}

/** Light or dark wall: by the sun, or always the one. */
enum class AppearanceMode {
    /** Light from sunrise to sunset, dark at night. */
    AUTO,
    LIGHT,
    DARK,
}

/** What stands behind the picture. */
enum class BackgroundMode {
    /** The wall: black with the artwork's light on it, or plain paper. */
    WALL,

    /** The cover's own colors, softly blurred, fill the screen. */
    COVER,
}

/** How the colors behind the picture move, on the screen and in the lights; [speed] scales the drift. */
enum class MotionLevel(val speed: Float) {
    OFF(0f),
    GENTLE(0.3f),
    MEDIUM(0.6f),
    LIVELY(1f),
}

/**
 * How brightly the lights behind the TV glow by day, as a share of their full brightness,
 * in steps of a tenth. At night they dim on their own.
 */
enum class RoomLightBrightness(val brightness: Float) {
    OFF(0f), P10(0.1f), P20(0.2f), P30(0.3f), P40(0.4f), P50(0.5f), P60(0.6f), P70(0.7f), P80(0.8f), P90(0.9f), P100(1f);

    val percent: Int get() = (brightness * 100f).roundToInt()

    companion object {
        val DEFAULT = P60

        /** A stored level by name. The four steps of earlier versions read as their nearest. */
        fun named(name: String): RoomLightBrightness? = when (name) {
            "SUBTLE" -> P30
            "NORMAL" -> P50
            "STRONG" -> P80
            else -> entries.firstOrNull { it.name == name }
        }

        /** The step nearest to [percent] (1 to 100); 0 or less is off. */
        fun ofPercent(percent: Int): RoomLightBrightness = if (percent <= 0) OFF else entries.drop(1).minBy { kotlin.math.abs(it.percent - percent) }
    }
}

/**
 * How the stage shows the queue: not at all (one large cover, the last line naming what comes
 * next), as a list beside that same cover, or as Cover Flow.
 */
enum class QueueDisplay {
    OFF,

    /** What comes next as a list in the text column, beside the cover. */
    LIST,

    /** Cover Flow: the cover in the middle, those before it to the left, those to come to the right. */
    FLOW,
}

/** When this TV plays as one of Music Assistant's speakers (needs Music Assistant). */
enum class PlayHere {
    OFF,

    /** While Klangbild is open. Closed, the TV leaves Music Assistant, so no music starts here over another app. */
    OPEN,

    /** Also while Klangbild is closed. */
    ALWAYS,
}

/** The color lights behind the TV (Home Assistant light entities), from the left; any may be missing. */
data class RoomLights(val left: String? = null, val center: String? = null, val right: String? = null) {
    /** Each light once, from the left. */
    val ids: List<String> get() = listOfNotNull(left, center, right).distinct()

    val isSet: Boolean get() = ids.isNotEmpty()
}

/** What the stage shows: the part of the settings the UI needs. */
data class DisplayOptions(
    val clock: Boolean = true,
    /** The progress bar (named after the time line it replaced; stored under that name). */
    val progressBar: Boolean = true,
    /** What comes next, on the artwork's bottom edge. */
    val next: Boolean = true,
    val album: Boolean = true,
    /** "Living room + 5" while other speakers play along. */
    val group: Boolean = true,
    val volume: VolumeDisplay = VolumeDisplay.ON_CHANGE,
    /** The values of one's own from Home Assistant, after the clock. */
    val values: Boolean = true,
    val intensity: Intensity = Intensity.NORMAL,
    val background: BackgroundMode = BackgroundMode.WALL,
    val motion: MotionLevel = MotionLevel.OFF,
)

/**
 * Persistent settings. Secrets live only in app-private storage (no backup, see manifest),
 * sealed with a key in the Android Keystore ([SecretBox]).
 */
data class Settings(
    val haUrl: String? = null,
    /** A long-lived access token, when Klangbild was set up with one. */
    val haToken: String? = null,
    /** The refresh token of a sign-in through Home Assistant's login page, and the client id it was made for. */
    val haRefreshToken: String? = null,
    val haClientId: String? = null,
    /** Home Assistant refused its credentials, so they were dropped ([refused]); the setup screen says so until new ones are set. */
    val haRefused: Boolean = false,
    val entityId: String? = null,
    /** The last speaker followed other than "This TV": the menu's Speaker goes back to it. */
    val otherSpeaker: String? = null,
    /** Speakers in other rooms, chosen on the phone, whose music shows while nothing plays here. */
    val otherRooms: List<String> = emptyList(),
    /** The music of [otherRooms] shows (the menu's "Music in other rooms"). */
    val showOtherRooms: Boolean = true,
    /** Presence sensors in this room, chosen on the phone: on while they see someone. */
    val presenceSensors: List<String> = emptyList(),
    /** The screen goes dark while [presenceSensors] see no one (the menu's "Screen off"). */
    val screenOff: Boolean = true,
    val sunEntity: String = "sun.sun",
    val sleepAfterMinutes: Int = 10,
    val sleepAction: SleepAction = SleepAction.RELEASE,
    val showClock: Boolean = true,
    val remoteControl: Boolean = true,
    val showProgressBar: Boolean = true,
    val showNext: Boolean = true,
    val showAlbum: Boolean = true,
    val showGroup: Boolean = true,
    val volumeDisplay: VolumeDisplay = VolumeDisplay.ON_CHANGE,
    /** The values of one's own show in the status line. */
    val showValues: Boolean = true,
    val intensity: Intensity = Intensity.NORMAL,
    /** Up and down on the remote change the speaker's volume (instead of opening the settings and the quick settings). */
    val volumeKeys: Boolean = false,
    val appearance: AppearanceMode = AppearanceMode.AUTO,
    /** How the queue shows: not at all, as a list, or as Cover Flow. */
    val queue: QueueDisplay = QueueDisplay.OFF,
    /** A song's words take the text column where Music Assistant has them synced (the quick settings' Lyrics). */
    val lyrics: Boolean = false,
    /** Music Assistant's URL and token, optional. Lyrics, the queue list, favorites, the party code and Play here need them. */
    val maUrl: String? = null,
    val maToken: String? = null,
    val playHere: PlayHere = PlayHere.OFF,
    val background: BackgroundMode = BackgroundMode.WALL,
    val motion: MotionLevel = MotionLevel.OFF,
    /** The color lights behind the TV and how brightly they join in (by day; at night they dim on their own). */
    val roomLights: RoomLights = RoomLights(),
    val roomLightBrightness: RoomLightBrightness = RoomLightBrightness.DEFAULT,
    /** Lights that are off when the music starts stay off. */
    val lightsOnlyOn: Boolean = false,
    /** With an audiobook or a podcast, left and right jump back 15 and ahead 30 seconds. */
    val spokenSkip: Boolean = true,
    /** Buttons of one's own in the quick settings, set up on the phone. */
    val quickActions: List<QuickAction> = emptyList(),
    /** Values of one's own from Home Assistant in the status line, set up on the phone. */
    val ownValues: List<OwnValue> = emptyList(),
    /** The quick settings have been opened once: the stage no longer tells how to open them. */
    val quickSeen: Boolean = false,
    /** Klangbild asks GitHub for a new version of itself as it starts (the menu's Check for updates). */
    val updateCheck: Boolean = false,
) {
    companion object {
        /** "Turn off after" goes up to this many minutes (twelve hours). */
        const val MAX_SLEEP_MINUTES = 720

        /** At most this many speakers in other rooms. */
        const val MAX_OTHER_ROOMS = 16

        /** At most this many presence sensors. */
        const val MAX_PRESENCE_SENSORS = 4
    }

    /** The speakers in other rooms whose music shows now: none while the menu hides them. */
    val shownOtherRooms: List<String> get() = if (showOtherRooms) otherRooms else emptyList()

    /** The screen goes dark while no one is in the room: switched on, with sensors to tell. */
    val darkWhenNobody: Boolean get() = screenOff && presenceSensors.isNotEmpty()

    /** How Klangbild signs in to Home Assistant: a sign-in wins over a long-lived token. */
    val haCredentials: HaCredentials?
        get() = when {
            !haRefreshToken.isNullOrBlank() && !haClientId.isNullOrBlank() -> HaCredentials.SignedIn(haRefreshToken, haClientId)
            !haToken.isNullOrBlank() -> HaCredentials.LongLived(haToken)
            else -> null
        }

    val isConfigured: Boolean get() = !haUrl.isNullOrBlank() && haCredentials != null && !entityId.isNullOrBlank()

    val hasMusicAssistant: Boolean get() = !maUrl.isNullOrBlank() && !maToken.isNullOrBlank()

    /** Playing here is on, while Klangbild is open or always. */
    val tvSpeaker: Boolean get() = playHere != PlayHere.OFF

    /** This TV plays as a speaker: switched on, and Music Assistant set up. */
    val speakerOn: Boolean get() = tvSpeaker && hasMusicAssistant

    /** Whether the speaker runs now: switched on, and Klangbild [open] unless it plays always. */
    fun speakerRuns(open: Boolean): Boolean = speakerOn && (open || playHere == PlayHere.ALWAYS)

    /** Playing here turned [on] or off. Turned on, it plays while Klangbild is open, unless it already plays always. */
    fun withPlayHere(on: Boolean): Settings = copy(
        playHere = when {
            !on -> PlayHere.OFF
            tvSpeaker -> playHere
            else -> PlayHere.OPEN
        },
    )

    /** Following [entity] (or "This TV"), keeping the speaker followed before it to go back to. */
    fun following(entity: String): Settings = copy(
        entityId = entity,
        otherSpeaker = if (entity != EntityId.THIS_TV) entity else entityId?.takeIf { it != EntityId.THIS_TV } ?: otherSpeaker,
    )

    /**
     * [haUrl] set to [url] with [credentials]. Credentials only ever go to the address they were
     * made for, so a new address without new credentials drops the old ones.
     */
    fun withHa(url: String, credentials: HaCredentials?): Settings {
        val keep = credentials ?: haCredentials.takeIf { url == haUrl }
        return copy(
            haUrl = url,
            haToken = (keep as? HaCredentials.LongLived)?.token,
            haRefreshToken = (keep as? HaCredentials.SignedIn)?.refreshToken,
            haClientId = (keep as? HaCredentials.SignedIn)?.clientId,
            haRefused = haRefused && credentials == null && url == haUrl,
        )
    }

    /**
     * Without the Home Assistant credentials, which it refused. They are never sent again, since
     * Home Assistant counts every attempt as a failed login.
     */
    fun refused(): Settings = copy(haToken = null, haRefreshToken = null, haClientId = null, haRefused = true)

    /** The same for Music Assistant; no address clears both. */
    fun withMa(url: String?, token: String?): Settings =
        if (url == null) copy(maUrl = null, maToken = null) else copy(maUrl = url, maToken = token ?: maToken.takeIf { url == maUrl })

    val display: DisplayOptions
        get() = DisplayOptions(
            clock = showClock,
            progressBar = showProgressBar,
            next = showNext,
            album = showAlbum,
            group = showGroup,
            volume = volumeDisplay,
            values = showValues,
            intensity = intensity,
            background = background,
            motion = motion,
        )
}

/** Per-launch options from `am start` extras that are not stored. */
data class SessionOptions(
    val showSetup: Boolean = false,
    val debugOverlay: Boolean = false,
)
