package io.github.hazymorning.klangbild.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * The settings on disk (DataStore). Tokens are sealed before they are written ([SecretBox]);
 * what is not stored reads as [Settings]' own default, and so does a value this version does
 * not know.
 */
class SettingsStore(private val context: Context, private val secrets: SecretBox = KeystoreSecretBox()) {
    private val defaults = Settings()

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
        val queue = stringPreferencesKey("queue")
        val layout = stringPreferencesKey("stage_layout")

        /** The view of versions before the queue's setting: SINGLE or FLOW. Read once, then removed. */
        val formerView = stringPreferencesKey("view")
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

    val settings: Flow<Settings> = context.dataStore.data.map(::read)

    /** Stores [s], every setting of it. */
    suspend fun write(s: Settings) {
        context.dataStore.edit { p -> write(p, s) }
    }

    /**
     * Brings what an earlier version stored up to date, in the same step that reads it: tokens
     * stored before secrets were sealed are sealed, and the view becomes how the queue shows.
     */
    suspend fun migrate() {
        context.dataStore.edit { migrate(it) }
    }

    /**
     * [p] brought up to date in place, as [migrate] does on disk. The view only moves its own
     * key: rewriting every setting would open the sealed tokens, and one that cannot be opened at
     * that moment would be lost.
     */
    internal fun migrate(p: MutablePreferences) {
        val plain = listOf(Keys.token, Keys.refreshToken, Keys.maToken).any { k -> p[k]?.let(secrets::isPlain) == true }
        if (plain) write(p, read(p))
        val view = p[Keys.formerView] ?: return
        if (Keys.queue !in p) p[Keys.queue] = queueOfView(view).name
        p.remove(Keys.formerView)
    }

    /** The settings as [p] stores them; what an earlier version stored is read as it is meant now. */
    internal fun read(p: Preferences): Settings =
        Settings(
            haUrl = p[Keys.url],
            haToken = p[Keys.token]?.let(secrets::open),
            haRefreshToken = p[Keys.refreshToken]?.let(secrets::open),
            haClientId = p[Keys.clientId],
            entityId = p[Keys.entity],
            sunEntity = p[Keys.sun] ?: defaults.sunEntity,
            sleepAfterMinutes = p[Keys.sleepMinutes] ?: defaults.sleepAfterMinutes,
            sleepAction = enumOr(p[Keys.sleepAction], defaults.sleepAction),
            showClock = p[Keys.clock] ?: defaults.showClock,
            remoteControl = p[Keys.remote] ?: defaults.remoteControl,
            showProgressBar = p[Keys.progressBar] ?: defaults.showProgressBar,
            showNext = p[Keys.next] ?: defaults.showNext,
            showAlbum = p[Keys.album] ?: defaults.showAlbum,
            showGroup = p[Keys.group] ?: defaults.showGroup,
            volumeDisplay = enumOr(p[Keys.volume], defaults.volumeDisplay),
            intensity = enumOr(p[Keys.intensity], defaults.intensity),
            volumeKeys = p[Keys.volumeKeys] ?: defaults.volumeKeys,
            appearance = enumOr(p[Keys.appearance], defaults.appearance),
            queue = p[Keys.queue]?.let { enumOr(it, defaults.queue) } ?: queueOfView(p[Keys.formerView]),
            layout = enumOr(p[Keys.layout], defaults.layout),
            lyrics = p[Keys.lyrics] ?: defaults.lyrics,
            maUrl = p[Keys.maUrl],
            maToken = p[Keys.maToken]?.let(secrets::open),
            background = enumOr(p[Keys.background], defaults.background),
            motion = enumOr(p[Keys.motion], defaults.motion),
            roomLights = RoomLights(p[Keys.lightsLeft], p[Keys.lightsCenter], p[Keys.lightsRight]),
            roomLightBrightness = p[Keys.roomLightBrightness]?.let { RoomLightBrightness.named(it) } ?: defaults.roomLightBrightness,
            lightsOnlyOn = p[Keys.lightsOnlyOn] ?: defaults.lightsOnlyOn,
            spokenSkip = p[Keys.spokenSkip] ?: defaults.spokenSkip,
            landscapePauseSeconds = p[Keys.landscapePause] ?: defaults.landscapePauseSeconds,
            landscapePlayingSeconds = p[Keys.landscapePlaying] ?: defaults.landscapePlayingSeconds,
            miniPlayer = enumOr(p[Keys.miniPlayer], defaults.miniPlayer),
            weatherEntity = p[Keys.weather],
            quickActions = QuickActions.decode(p[Keys.quickActions]),
            ownValues = OwnValues.decode(p[Keys.ownValues]),
            quickSeen = p[Keys.quickSeen] ?: defaults.quickSeen,
            showValues = p[Keys.showValues] ?: defaults.showValues,
        )

    private fun write(p: MutablePreferences, s: Settings) {
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
        p[Keys.queue] = s.queue.name
        p[Keys.layout] = s.layout.name
        p.remove(Keys.formerView)
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

    /**
     * How the queue shows for the view an earlier version stored: Cover Flow stays Cover Flow, and
     * the single cover, which had no list beside it, is the queue off.
     */
    private fun queueOfView(view: String?): QueueDisplay = if (view == "FLOW") QueueDisplay.FLOW else defaults.queue

    /** The constant named [name], or [default] for none or a name this version does not know. */
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
