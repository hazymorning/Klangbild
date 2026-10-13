package io.github.hazymorning.klangbild.config

import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.data.ha.HaUrls
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.domain.StageView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

/** Where the music's label sits in the landscape. */
enum class MiniPlayer { LEFT, RIGHT, OFF }

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
    val entityId: String? = null,
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
    /** Up and down on the remote change the speaker's volume (instead of the landscape and the quick settings). */
    val volumeKeys: Boolean = false,
    val appearance: AppearanceMode = AppearanceMode.AUTO,
    val view: StageView = StageView.SINGLE,
    /** A song's words take the text column where Music Assistant has them synced (the quick settings' Lyrics). */
    val lyrics: Boolean = false,
    /** Music Assistant itself (optional): for the whole queue in Cover Flow. */
    val maUrl: String? = null,
    val maToken: String? = null,
    val background: BackgroundMode = BackgroundMode.WALL,
    val motion: MotionLevel = MotionLevel.OFF,
    /** The color lights behind the TV and how brightly they join in (by day; at night they dim on their own). */
    val roomLights: RoomLights = RoomLights(),
    val roomLightBrightness: RoomLightBrightness = RoomLightBrightness.DEFAULT,
    /** Lights that are off when the music starts stay off. */
    val lightsOnlyOn: Boolean = false,
    /** With an audiobook or a podcast, left and right jump back 15 and ahead 30 seconds. */
    val spokenSkip: Boolean = true,
    /** The landscape takes the screen after the music has been paused this long (s); 0 never. */
    val landscapePauseSeconds: Int = 180,
    /** The landscape also comes while music plays, after this long without a key pressed (s); 0 never. */
    val landscapePlayingSeconds: Int = 0,
    /** Where the music's label sits in the landscape. */
    val miniPlayer: MiniPlayer = MiniPlayer.LEFT,
    /** The weather entity whose clouds set the landscape's sky; null finds Home Assistant's own, [NO_WEATHER] none. */
    val weatherEntity: String? = null,
    /** Buttons of one's own in the quick settings, set up on the phone. */
    val quickActions: List<QuickAction> = emptyList(),
    /** Values of one's own from Home Assistant in the status line, set up on the phone. */
    val ownValues: List<OwnValue> = emptyList(),
    /** The quick settings have been opened once: the stage no longer tells how to open them. */
    val quickSeen: Boolean = false,
) {
    /** The weather entities to follow: the one chosen, else Home Assistant's usual forecast entities. */
    val weatherEntities: List<String>
        get() = when (weatherEntity) {
            null -> DEFAULT_WEATHER
            NO_WEATHER -> emptyList()
            else -> listOf(weatherEntity)
        }

    companion object {
        /** No weather entity: the landscape's sky stays fair. */
        const val NO_WEATHER = "none"

        /** The entities Home Assistant's own weather integration (Met.no) creates. */
        val DEFAULT_WEATHER = listOf("weather.forecast_home", "weather.home")
    }

    /** How Klangbild signs in to Home Assistant: a sign-in wins over a long-lived token. */
    val haCredentials: HaCredentials?
        get() = when {
            !haRefreshToken.isNullOrBlank() && !haClientId.isNullOrBlank() -> HaCredentials.SignedIn(haRefreshToken, haClientId)
            !haToken.isNullOrBlank() -> HaCredentials.LongLived(haToken)
            else -> null
        }

    val isConfigured: Boolean get() = !haUrl.isNullOrBlank() && haCredentials != null && !entityId.isNullOrBlank()

    val hasMusicAssistant: Boolean get() = !maUrl.isNullOrBlank() && !maToken.isNullOrBlank()

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
        )
    }

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
    /** The landscape shown at once (`landscape=on`), or with the day passing in two minutes (`timelapse`). */
    val landscape: LandscapeRequest? = null,
)

/** A landscape asked for from outside (a link, `am start`, Home Assistant). */
enum class LandscapeRequest { SHOW, TIMELAPSE, HIDE }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context, private val secrets: SecretBox = KeystoreSecretBox()) {
    private object Keys {
        val url = stringPreferencesKey("ha_url")
        val token = stringPreferencesKey("ha_token")
        val refreshToken = stringPreferencesKey("ha_refresh_token")
        val clientId = stringPreferencesKey("ha_client_id")
        val entity = stringPreferencesKey("entity_id")
        val sun = stringPreferencesKey("sun_entity")
        val sleepMinutes = intPreferencesKey("sleep_minutes")
        val sleepAction = stringPreferencesKey("sleep_action")
        val clock = booleanPreferencesKey("show_clock")
        val remote = booleanPreferencesKey("remote_control")
        val progressBar = booleanPreferencesKey("show_time_line")
        val next = booleanPreferencesKey("show_next")
        val album = booleanPreferencesKey("show_album")
        val group = booleanPreferencesKey("show_group")
        val volume = stringPreferencesKey("volume_display")
        val intensity = stringPreferencesKey("light_level")
        val volumeKeys = booleanPreferencesKey("volume_keys")
        val appearance = stringPreferencesKey("appearance")
        val view = stringPreferencesKey("view")
        val lyrics = booleanPreferencesKey("lyrics")
        val maUrl = stringPreferencesKey("ma_url")
        val maToken = stringPreferencesKey("ma_token")
        val background = stringPreferencesKey("background")
        val motion = stringPreferencesKey("motion")
        val lightsLeft = stringPreferencesKey("bars_left")
        val lightsCenter = stringPreferencesKey("bars_center")
        val lightsRight = stringPreferencesKey("bars_right")
        val roomLightBrightness = stringPreferencesKey("bar_level")
        val lightsOnlyOn = booleanPreferencesKey("lights_only_on")
        val spokenSkip = booleanPreferencesKey("spoken_skip")
        val landscapePause = intPreferencesKey("vista_pause_s")
        val landscapePlaying = intPreferencesKey("vista_playing_s")
        val miniPlayer = stringPreferencesKey("mini_player")
        val weather = stringPreferencesKey("weather_entity")
        val quickActions = stringPreferencesKey("quick_actions")
        val quickSeen = booleanPreferencesKey("quick_seen")
        val ownValues = stringPreferencesKey("own_values")
        val showValues = booleanPreferencesKey("show_values")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            haUrl = p[Keys.url],
            haToken = p[Keys.token]?.let(secrets::open),
            haRefreshToken = p[Keys.refreshToken]?.let(secrets::open),
            haClientId = p[Keys.clientId],
            entityId = p[Keys.entity],
            sunEntity = p[Keys.sun] ?: "sun.sun",
            sleepAfterMinutes = p[Keys.sleepMinutes] ?: 10,
            sleepAction = p[Keys.sleepAction]?.let { runCatching { SleepAction.valueOf(it) }.getOrNull() } ?: SleepAction.RELEASE,
            showClock = p[Keys.clock] ?: true,
            remoteControl = p[Keys.remote] ?: true,
            showProgressBar = p[Keys.progressBar] ?: true,
            showNext = p[Keys.next] ?: true,
            showAlbum = p[Keys.album] ?: true,
            showGroup = p[Keys.group] ?: true,
            volumeDisplay = p[Keys.volume]?.let { runCatching { VolumeDisplay.valueOf(it) }.getOrNull() } ?: VolumeDisplay.ON_CHANGE,
            intensity = p[Keys.intensity]?.let { runCatching { Intensity.valueOf(it) }.getOrNull() } ?: Intensity.NORMAL,
            volumeKeys = p[Keys.volumeKeys] ?: false,
            appearance = p[Keys.appearance]?.let { runCatching { AppearanceMode.valueOf(it) }.getOrNull() } ?: AppearanceMode.AUTO,
            view = p[Keys.view]?.let { runCatching { StageView.valueOf(it) }.getOrNull() } ?: StageView.SINGLE,
            lyrics = p[Keys.lyrics] ?: false,
            maUrl = p[Keys.maUrl],
            maToken = p[Keys.maToken]?.let(secrets::open),
            background = p[Keys.background]?.let { runCatching { BackgroundMode.valueOf(it) }.getOrNull() } ?: BackgroundMode.WALL,
            motion = p[Keys.motion]?.let { runCatching { MotionLevel.valueOf(it) }.getOrNull() } ?: MotionLevel.OFF,
            roomLights = RoomLights(p[Keys.lightsLeft], p[Keys.lightsCenter], p[Keys.lightsRight]),
            roomLightBrightness = p[Keys.roomLightBrightness]?.let { RoomLightBrightness.named(it) } ?: RoomLightBrightness.DEFAULT,
            lightsOnlyOn = p[Keys.lightsOnlyOn] ?: false,
            spokenSkip = p[Keys.spokenSkip] ?: true,
            landscapePauseSeconds = p[Keys.landscapePause] ?: 180,
            landscapePlayingSeconds = p[Keys.landscapePlaying] ?: 0,
            miniPlayer = p[Keys.miniPlayer]?.let { runCatching { MiniPlayer.valueOf(it) }.getOrNull() } ?: MiniPlayer.LEFT,
            weatherEntity = p[Keys.weather],
            quickActions = QuickActions.decode(p[Keys.quickActions]),
            ownValues = OwnValues.decode(p[Keys.ownValues]),
            quickSeen = p[Keys.quickSeen] ?: false,
            showValues = p[Keys.showValues] ?: true,
        )
    }

    suspend fun update(transform: (Settings) -> Settings, current: Settings) {
        val s = transform(current)
        context.dataStore.edit { p ->
            fun putOrRemove(key: Preferences.Key<String>, value: String?) {
                if (value.isNullOrBlank()) p.remove(key) else p[key] = value
            }
            fun putSecret(key: Preferences.Key<String>, value: String?) = putOrRemove(key, value?.takeIf { it.isNotBlank() }?.let(secrets::seal))
            putOrRemove(Keys.url, s.haUrl)
            putSecret(Keys.token, s.haToken)
            putSecret(Keys.refreshToken, s.haRefreshToken)
            putOrRemove(Keys.clientId, s.haClientId)
            putOrRemove(Keys.entity, s.entityId)
            p[Keys.sun] = s.sunEntity
            p[Keys.sleepMinutes] = s.sleepAfterMinutes
            p[Keys.sleepAction] = s.sleepAction.name
            p[Keys.clock] = s.showClock
            p[Keys.remote] = s.remoteControl
            p[Keys.progressBar] = s.showProgressBar
            p[Keys.next] = s.showNext
            p[Keys.album] = s.showAlbum
            p[Keys.group] = s.showGroup
            p[Keys.volume] = s.volumeDisplay.name
            p[Keys.intensity] = s.intensity.name
            p[Keys.volumeKeys] = s.volumeKeys
            p[Keys.appearance] = s.appearance.name
            p[Keys.view] = s.view.name
            p[Keys.lyrics] = s.lyrics
            putOrRemove(Keys.maUrl, s.maUrl)
            putSecret(Keys.maToken, s.maToken)
            p[Keys.background] = s.background.name
            p[Keys.motion] = s.motion.name
            putOrRemove(Keys.lightsLeft, s.roomLights.left)
            putOrRemove(Keys.lightsCenter, s.roomLights.center)
            putOrRemove(Keys.lightsRight, s.roomLights.right)
            p[Keys.roomLightBrightness] = s.roomLightBrightness.name
            p[Keys.lightsOnlyOn] = s.lightsOnlyOn
            p[Keys.spokenSkip] = s.spokenSkip
            p[Keys.landscapePause] = s.landscapePauseSeconds
            p[Keys.landscapePlaying] = s.landscapePlayingSeconds
            p[Keys.miniPlayer] = s.miniPlayer.name
            putOrRemove(Keys.weather, s.weatherEntity)
            putOrRemove(Keys.quickActions, s.quickActions.takeIf { it.isNotEmpty() }?.let(QuickActions::encode))
            putOrRemove(Keys.ownValues, s.ownValues.takeIf { it.isNotEmpty() }?.let(OwnValues::encode))
            p[Keys.quickSeen] = s.quickSeen
            p[Keys.showValues] = s.showValues
        }
    }

    /** Seals tokens stored before secrets were sealed (written by an earlier version). */
    suspend fun sealStoredSecrets() {
        val plain = context.dataStore.data.first().let { p ->
            listOf(Keys.token, Keys.refreshToken, Keys.maToken).any { k -> p[k]?.let(secrets::isPlain) == true }
        }
        if (plain) settings.first().let { update({ it }, it) }
    }
}

/**
 * `am start` extras. Configuration extras (`ha_url`, `token`, `ma_url`, `ma_token`, `entity`,
 * `sleep_minutes`, `sleep_action`, `clock` and the display options) are stored; `setup`,
 * `debug` and `landscape` only apply to this launch. A new address without its token drops
 * the stored one: no other app on the TV can redirect a token to an address of its choosing.
 * Names and values are English; the German ones of earlier versions keep working.
 *
 * ```
 * adb shell am start -n io.github.hazymorning.klangbild/.MainActivity \
 *     --es entity media_player.living_room --es light subtle
 * ```
 */
object LaunchExtras {
    const val HA_URL = "ha_url"
    const val TOKEN = "token"
    const val MA_URL = "ma_url"
    const val MA_TOKEN = "ma_token"
    const val ENTITY = "entity"
    const val SLEEP_MINUTES = "sleep_minutes"
    const val SLEEP_ACTION = "sleep_action"
    const val CLOCK = "clock"
    const val PROGRESS_BAR = "time_line"
    const val NEXT = "next"
    const val ALBUM = "album"
    const val GROUP = "group"
    const val VOLUME = "volume"
    const val VALUES = "values"
    const val INTENSITY = "light"
    const val VOLUME_KEYS = "volume_keys"
    const val REMOTE = "remote"
    const val APPEARANCE = "appearance"
    const val VIEW = "view"
    const val BACKGROUND = "background"
    const val MOTION = "motion"
    const val LIGHTS_LEFT = "lights_left"
    const val LIGHTS_CENTER = "lights_center"
    const val LIGHTS_RIGHT = "lights_right"
    const val LIGHTS_BRIGHTNESS = "lights_level"
    const val SPOKEN_SKIP = "spoken_skip"
    const val LANDSCAPE_PAUSE = "landscape_pause"
    const val LANDSCAPE_PLAYING = "landscape_playing"
    const val MINI_PLAYER = "mini_player"
    const val WEATHER = "weather"
    const val SETUP = "setup"
    const val DEBUG = "debug"
    const val LANDSCAPE = "landscape"

    /** The names earlier versions used: they keep working, and the English name wins when both are given. */
    private val formerNames = mapOf(
        "bars_left" to LIGHTS_LEFT,
        "bars_center" to LIGHTS_CENTER,
        "bars_right" to LIGHTS_RIGHT,
        "bar_level" to LIGHTS_BRIGHTNESS,
        "window_view_pause" to LANDSCAPE_PAUSE,
        "window_view_playing" to LANDSCAPE_PLAYING,
        "window_view" to LANDSCAPE,
        "fensterblick_pause" to LANDSCAPE_PAUSE,
        "fensterblick_musik" to LANDSCAPE_PLAYING,
        "fensterblick" to LANDSCAPE,
    )

    private val stored = listOf(
        HA_URL, TOKEN, MA_URL, MA_TOKEN, ENTITY, SLEEP_MINUTES, SLEEP_ACTION, CLOCK, PROGRESS_BAR, NEXT, ALBUM, GROUP, VOLUME, VALUES, INTENSITY,
        VOLUME_KEYS, REMOTE, APPEARANCE, VIEW, BACKGROUND, MOTION, LIGHTS_LEFT, LIGHTS_CENTER, LIGHTS_RIGHT, LIGHTS_BRIGHTNESS,
        SPOKEN_SKIP, LANDSCAPE_PAUSE, LANDSCAPE_PLAYING, MINI_PLAYER, WEATHER,
    )

    /** [extras] under their current names. */
    private fun canonical(extras: Map<String, Any?>): Map<String, Any?> {
        if (formerNames.keys.none { it in extras }) return extras
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in extras) {
            val name = formerNames[key] ?: key
            if (name == key || name !in extras) out[name] = value
        }
        return out
    }

    /** Applies stored-setting extras to [s]. Unknown or invalid values are ignored. */
    fun applyTo(s: Settings, given: Map<String, Any?>): Settings {
        val extras = canonical(given)
        var out = s
        val token = (extras[TOKEN] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val haUrl = (extras[HA_URL] as? String)?.let { HaUrls.normalizeBase(it) }
        val longLived = token?.let { HaCredentials.LongLived(it) }
        val knownUrl = out.haUrl
        if (haUrl != null) out = out.withHa(haUrl, longLived) else if (longLived != null && knownUrl != null) out = out.withHa(knownUrl, longLived)
        val maToken = (extras[MA_TOKEN] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val maUrl = (extras[MA_URL] as? String)?.let { HaUrls.normalizeBase(it) }
        if (maUrl != null) out = out.withMa(maUrl, maToken) else if (maToken != null && out.maUrl != null) out = out.copy(maToken = maToken)
        (extras[ENTITY] as? String)?.trim()?.takeIf { EntityId.isValid(it) }?.let { out = out.copy(entityId = it) }
        intOf(extras[SLEEP_MINUTES])?.takeIf { it in 1..720 }?.let { out = out.copy(sleepAfterMinutes = it) }
        (extras[SLEEP_ACTION] as? String)?.let { a ->
            when (a.lowercase()) {
                "release", "freigeben" -> out = out.copy(sleepAction = SleepAction.RELEASE)
                "close", "beenden" -> out = out.copy(sleepAction = SleepAction.CLOSE)
            }
        }
        boolOf(extras[CLOCK])?.let { out = out.copy(showClock = it) }
        boolOf(extras[PROGRESS_BAR])?.let { out = out.copy(showProgressBar = it) }
        boolOf(extras[NEXT])?.let { out = out.copy(showNext = it) }
        boolOf(extras[ALBUM])?.let { out = out.copy(showAlbum = it) }
        boolOf(extras[GROUP])?.let { out = out.copy(showGroup = it) }
        boolOf(extras[VALUES])?.let { out = out.copy(showValues = it) }
        boolOf(extras[VOLUME_KEYS])?.let { out = out.copy(volumeKeys = it) }
        boolOf(extras[REMOTE])?.let { out = out.copy(remoteControl = it) }
        (extras[VOLUME] as? String)?.let { v ->
            when (v.trim().lowercase()) {
                "on_change", "change", "aenderung", "änderung", "bei_aenderung", "bei_änderung" -> VolumeDisplay.ON_CHANGE
                "always", "immer" -> VolumeDisplay.ALWAYS
                "off", "aus", "never", "nie" -> VolumeDisplay.OFF
                else -> null
            }?.let { out = out.copy(volumeDisplay = it) }
        }
        (extras[INTENSITY] as? String)?.let { v ->
            when (v.trim().lowercase()) {
                "strong", "kraeftig", "kräftig" -> Intensity.STRONG
                "normal" -> Intensity.NORMAL
                "subtle", "dezent" -> Intensity.SUBTLE
                "off", "aus" -> Intensity.OFF
                else -> null
            }?.let { out = out.copy(intensity = it) }
        }
        (extras[APPEARANCE] as? String)?.let { v ->
            when (v.trim().lowercase()) {
                "auto", "automatisch" -> AppearanceMode.AUTO
                "light", "hell" -> AppearanceMode.LIGHT
                "dark", "dunkel" -> AppearanceMode.DARK
                else -> null
            }?.let { out = out.copy(appearance = it) }
        }
        (extras[VIEW] as? String)?.let { v ->
            when (v.trim().lowercase().replace(" ", "").replace("_", "")) {
                "single", "einzelbild", "bild" -> StageView.SINGLE
                "flow", "coverflow" -> StageView.FLOW
                else -> null
            }?.let { out = out.copy(view = it) }
        }
        (extras[BACKGROUND] as? String)?.let { v ->
            when (v.trim().lowercase().replace(" ", "").replace("_", "")) {
                "wall", "plain", "wand", "einfarbig" -> BackgroundMode.WALL
                "cover", "coverfarben", "colours", "colors", "farben" -> BackgroundMode.COVER
                else -> null
            }?.let { out = out.copy(background = it) }
        }
        (extras[MOTION] as? String)?.let { v ->
            when (v.trim().lowercase()) {
                "off", "aus" -> MotionLevel.OFF
                "slow", "gentle", "langsam", "sanft" -> MotionLevel.GENTLE
                "medium", "mittel" -> MotionLevel.MEDIUM
                "fast", "lively", "schnell", "lebhaft" -> MotionLevel.LIVELY
                else -> null
            }?.let { out = out.copy(motion = it) }
        }
        // A word, or how bright in percent ("60", "60 %").
        extras[LIGHTS_BRIGHTNESS]?.let { raw ->
            val v = raw.toString().trim().lowercase()
            when (v) {
                "off", "aus" -> RoomLightBrightness.OFF
                "subtle", "dezent", "schwach" -> RoomLightBrightness.P30
                "normal" -> RoomLightBrightness.P50
                "strong", "kraeftig", "kräftig", "stark" -> RoomLightBrightness.P80
                else -> v.removeSuffix("%").trim().toIntOrNull()?.let { RoomLightBrightness.ofPercent(it) }
            }?.let { out = out.copy(roomLightBrightness = it) }
        }
        // A light given as "" or "none" is removed; anything but a light entity is ignored.
        fun bar(key: String, current: String?): String? = when (val v = (extras[key] as? String)?.trim()) {
            null -> current
            "", "none", "keine" -> null
            else -> v.takeIf { EntityId.isLight(it) } ?: current
        }
        if (extras.keys.any { it == LIGHTS_LEFT || it == LIGHTS_CENTER || it == LIGHTS_RIGHT }) {
            out = out.copy(roomLights = RoomLights(bar(LIGHTS_LEFT, out.roomLights.left), bar(LIGHTS_CENTER, out.roomLights.center), bar(LIGHTS_RIGHT, out.roomLights.right)))
        }
        boolOf(extras[SPOKEN_SKIP])?.let { out = out.copy(spokenSkip = it) }
        // The landscape's delays in seconds ("off" or 0 for never), within what the menu offers.
        secondsOf(extras[LANDSCAPE_PAUSE])?.takeIf { it in 0..3_600 }?.let { out = out.copy(landscapePauseSeconds = it) }
        secondsOf(extras[LANDSCAPE_PLAYING])?.takeIf { it in 0..7_200 }?.let { out = out.copy(landscapePlayingSeconds = it) }
        (extras[MINI_PLAYER] as? String)?.let { v ->
            when (v.trim().lowercase()) {
                "left", "bottom_left", "links", "unten_links" -> MiniPlayer.LEFT
                "right", "bottom_right", "rechts", "unten_rechts" -> MiniPlayer.RIGHT
                "off", "aus" -> MiniPlayer.OFF
                else -> null
            }?.let { out = out.copy(miniPlayer = it) }
        }
        (extras[WEATHER] as? String)?.trim()?.let { v ->
            when {
                v.isEmpty() || v == "auto" -> out = out.copy(weatherEntity = null)
                v == Settings.NO_WEATHER || v == "keine" -> out = out.copy(weatherEntity = Settings.NO_WEATHER)
                EntityId.isWeather(v) -> out = out.copy(weatherEntity = v)
            }
        }
        return out
    }

    private fun secondsOf(v: Any?): Int? = when (v) {
        is String -> if (v.trim().lowercase() in setOf("aus", "off", "nie", "never")) 0 else intOf(v)
        else -> intOf(v)
    }

    fun session(given: Map<String, Any?>): SessionOptions {
        val extras = canonical(given)
        val landscape = extras[LANDSCAPE]
        return SessionOptions(
            showSetup = boolOf(extras[SETUP]) ?: false,
            debugOverlay = boolOf(extras[DEBUG]) ?: false,
            landscape = when ((landscape as? String)?.trim()?.lowercase() ?: (landscape as? Boolean)?.toString()) {
                "on", "true", "1", "yes", "show", "an", "ja", "zeigen" -> LandscapeRequest.SHOW
                "timelapse", "zeitraffer" -> LandscapeRequest.TIMELAPSE
                "off", "false", "0", "no", "aus", "nein" -> LandscapeRequest.HIDE
                else -> null
            },
        )
    }

    fun hasStoredExtras(extras: Map<String, Any?>) = canonical(extras).keys.any { it in stored }

    fun fromIntent(intent: Intent?): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        intent?.extras?.let { b ->
            for (key in b.keySet()) {
                @Suppress("DEPRECATION")
                out[key] = b.get(key)
            }
        }
        intent?.data?.takeIf { it.scheme == DEEP_LINK_SCHEME && it.isHierarchical }?.let { uri ->
            out += fromLink(uri.queryParameterNames.associateWith { uri.getQueryParameter(it) })
        }
        return out
    }

    /** `klangbild://open?light=subtle&album=off` */
    const val DEEP_LINK_SCHEME = "klangbild"

    /**
     * What a link may set: display options and the sleep time, but never the address, token,
     * speaker or the lights to control. Any page in a browser on the TV could open such a link.
     */
    private val linkKeys = setOf(
        CLOCK, PROGRESS_BAR, NEXT, ALBUM, GROUP, VOLUME, VALUES, INTENSITY, VOLUME_KEYS, REMOTE, SLEEP_MINUTES, SLEEP_ACTION,
        APPEARANCE, VIEW, BACKGROUND, MOTION, LIGHTS_BRIGHTNESS, SPOKEN_SKIP, LANDSCAPE_PAUSE, LANDSCAPE_PLAYING, MINI_PLAYER,
        LANDSCAPE,
    )

    /** The query parameters of a `klangbild://` link as extras, unsafe keys dropped. */
    fun fromLink(params: Map<String, String?>): Map<String, Any?> =
        params.filterKeys { (formerNames[it] ?: it) in linkKeys }

    private fun intOf(v: Any?): Int? = when (v) {
        is Int -> v
        is Long -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun boolOf(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true", "1", "yes", "ja", "on", "an" -> true
            "false", "0", "no", "nein", "off", "aus" -> false
            else -> null
        }
        is Int -> v != 0
        else -> null
    }
}
