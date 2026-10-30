package io.github.hazymorning.klangbild.config

import io.github.hazymorning.klangbild.domain.EntityId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The settings on disk ([store]). Tokens are sealed before they are written ([SecretBox]);
 * what is not stored reads as [Settings]' own default, and so does a value this version does
 * not know.
 */
class SettingsStore(private val store: ValueStore, private val secrets: SecretBox) {
    private val defaults = Settings()

    private object Keys {
        val url = stringKey("ha_url")
        val token = stringKey("ha_token")
        val refreshToken = stringKey("ha_refresh_token")
        val clientId = stringKey("ha_client_id")
        val refused = booleanKey("ha_refused")
        val entity = stringKey("entity_id")
        val sun = stringKey("sun_entity")
        val sleepMinutes = intKey("sleep_minutes")
        val sleepAction = stringKey("sleep_action")
        val clock = booleanKey("show_clock")
        val remote = booleanKey("remote_control")
        val progressBar = booleanKey("show_time_line")
        val next = booleanKey("show_next")
        val album = booleanKey("show_album")
        val group = booleanKey("show_group")
        val volume = stringKey("volume_display")
        val intensity = stringKey("light_level")
        val volumeKeys = booleanKey("volume_keys")
        val appearance = stringKey("appearance")
        val queue = stringKey("queue")

        /** The view of versions before the queue's setting: SINGLE or FLOW. Read once, then removed. */
        val formerView = stringKey("view")
        val lyrics = booleanKey("lyrics")
        val maUrl = stringKey("ma_url")
        val maToken = stringKey("ma_token")
        val playHere = stringKey("play_here")

        /** Play here of versions before it could play only while Klangbild is open: on or off. Read once, then removed. */
        val formerTvSpeaker = booleanKey("tv_speaker")
        val otherSpeaker = stringKey("other_speaker")

        /** The speakers in other rooms, their entity ids separated by commas. */
        val otherRooms = stringKey("other_rooms")
        val showOtherRooms = booleanKey("show_other_rooms")

        /** The presence sensors, their entity ids separated by commas. */
        val presenceSensors = stringKey("presence_sensors")
        val screenOff = booleanKey("screen_off")
        val notifications = booleanKey("notifications")
        val notifyWebhook = stringKey("notify_webhook")
        val notifyDeviceId = stringKey("notify_device_id")
        val background = stringKey("background")
        val motion = stringKey("motion")
        val lightsLeft = stringKey("bars_left")
        val lightsCenter = stringKey("bars_center")
        val lightsRight = stringKey("bars_right")
        val roomLightBrightness = stringKey("bar_level")
        val lightsOnlyOn = booleanKey("lights_only_on")
        val spokenSkip = booleanKey("spoken_skip")
        val quickActions = stringKey("quick_actions")
        val quickSeen = booleanKey("quick_seen")
        val ownValues = stringKey("own_values")
        val showValues = booleanKey("show_values")
        val updateCheck = booleanKey("update_check")

        /** Settings of earlier versions that no longer exist: [migrate] removes them. */
        val gone = listOf(intKey("vista_pause_s"), intKey("vista_playing_s"), stringKey("mini_player"), stringKey("weather_entity"))
    }

    val settings: Flow<Settings> = store.data.map(::read)

    /** Stores [s], every setting of it. */
    suspend fun write(s: Settings) {
        store.edit { p -> write(p, s) }
    }

    /**
     * Brings what an earlier version stored up to date, in the same step that reads it: tokens
     * stored before secrets were sealed are sealed, the view becomes how the queue shows, Play
     * here's switch becomes when it plays, and settings that no longer exist go.
     */
    suspend fun migrate() {
        store.edit { migrate(it) }
    }

    /**
     * [p] brought up to date in place, as [migrate] does on disk. The view and Play here's switch
     * only move their own keys, and the settings gone take only theirs: rewriting every setting
     * would open the sealed tokens, and one that cannot be opened at that moment would be lost.
     */
    internal fun migrate(p: MutableMap<String, Any>) {
        val plain = listOf(Keys.token, Keys.refreshToken, Keys.maToken, Keys.notifyWebhook).any { k -> p[k]?.let(secrets::isPlain) == true }
        if (plain) write(p, read(p))
        Keys.gone.forEach { p.remove(it) }
        p[Keys.formerView]?.let { view ->
            if (Keys.queue !in p) p[Keys.queue] = queueOfView(view).name
            p.remove(Keys.formerView)
        }
        p[Keys.formerTvSpeaker]?.let { on ->
            if (Keys.playHere !in p) p[Keys.playHere] = playHereOf(on).name
            p.remove(Keys.formerTvSpeaker)
        }
    }

    /** The settings as [p] stores them; what an earlier version stored is read with its current meaning. */
    internal fun read(p: Map<String, Any>): Settings =
        Settings(
            haUrl = p[Keys.url],
            haToken = p[Keys.token]?.let(secrets::open),
            haRefreshToken = p[Keys.refreshToken]?.let(secrets::open),
            haClientId = p[Keys.clientId],
            haRefused = p[Keys.refused] ?: defaults.haRefused,
            entityId = p[Keys.entity],
            otherSpeaker = p[Keys.otherSpeaker],
            otherRooms = p[Keys.otherRooms]?.split(',')?.filter(EntityId::isValid).orEmpty(),
            showOtherRooms = p[Keys.showOtherRooms] ?: defaults.showOtherRooms,
            presenceSensors = p[Keys.presenceSensors]?.split(',')?.filter(EntityId::isValid).orEmpty(),
            screenOff = p[Keys.screenOff] ?: defaults.screenOff,
            notifications = p[Keys.notifications] ?: defaults.notifications,
            notifyWebhook = p[Keys.notifyWebhook]?.let(secrets::open),
            notifyDeviceId = p[Keys.notifyDeviceId],
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
            lyrics = p[Keys.lyrics] ?: defaults.lyrics,
            maUrl = p[Keys.maUrl],
            maToken = p[Keys.maToken]?.let(secrets::open),
            playHere = p[Keys.playHere]?.let { enumOr(it, defaults.playHere) } ?: playHereOf(p[Keys.formerTvSpeaker]),
            background = enumOr(p[Keys.background], defaults.background),
            motion = enumOr(p[Keys.motion], defaults.motion),
            roomLights = RoomLights(p[Keys.lightsLeft], p[Keys.lightsCenter], p[Keys.lightsRight]),
            roomLightBrightness = p[Keys.roomLightBrightness]?.let { RoomLightBrightness.named(it) } ?: defaults.roomLightBrightness,
            lightsOnlyOn = p[Keys.lightsOnlyOn] ?: defaults.lightsOnlyOn,
            spokenSkip = p[Keys.spokenSkip] ?: defaults.spokenSkip,
            quickActions = QuickActions.decode(p[Keys.quickActions]),
            ownValues = OwnValues.decode(p[Keys.ownValues]),
            quickSeen = p[Keys.quickSeen] ?: defaults.quickSeen,
            showValues = p[Keys.showValues] ?: defaults.showValues,
            updateCheck = p[Keys.updateCheck] ?: defaults.updateCheck,
        )

    private fun write(p: MutableMap<String, Any>, s: Settings) {
        fun putOrRemove(key: StoreKey<String>, value: String?) {
            if (value.isNullOrBlank()) p.remove(key) else p[key] = value
        }
        fun putSecret(key: StoreKey<String>, value: String?) = putOrRemove(key, value?.takeIf { it.isNotBlank() }?.let(secrets::seal))
        putOrRemove(Keys.url, s.haUrl)
        putSecret(Keys.token, s.haToken)
        putSecret(Keys.refreshToken, s.haRefreshToken)
        putOrRemove(Keys.clientId, s.haClientId)
        p[Keys.refused] = s.haRefused
        putOrRemove(Keys.entity, s.entityId)
        putOrRemove(Keys.otherSpeaker, s.otherSpeaker)
        putOrRemove(Keys.otherRooms, s.otherRooms.joinToString(","))
        p[Keys.showOtherRooms] = s.showOtherRooms
        putOrRemove(Keys.presenceSensors, s.presenceSensors.joinToString(","))
        p[Keys.screenOff] = s.screenOff
        p[Keys.notifications] = s.notifications
        putSecret(Keys.notifyWebhook, s.notifyWebhook)
        putOrRemove(Keys.notifyDeviceId, s.notifyDeviceId)
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
        p.remove(Keys.formerView)
        p[Keys.lyrics] = s.lyrics
        putOrRemove(Keys.maUrl, s.maUrl)
        putSecret(Keys.maToken, s.maToken)
        p[Keys.playHere] = s.playHere.name
        p.remove(Keys.formerTvSpeaker)
        p[Keys.background] = s.background.name
        p[Keys.motion] = s.motion.name
        putOrRemove(Keys.lightsLeft, s.roomLights.left)
        putOrRemove(Keys.lightsCenter, s.roomLights.center)
        putOrRemove(Keys.lightsRight, s.roomLights.right)
        p[Keys.roomLightBrightness] = s.roomLightBrightness.name
        p[Keys.lightsOnlyOn] = s.lightsOnlyOn
        p[Keys.spokenSkip] = s.spokenSkip
        putOrRemove(Keys.quickActions, s.quickActions.takeIf { it.isNotEmpty() }?.let(QuickActions::encode))
        putOrRemove(Keys.ownValues, s.ownValues.takeIf { it.isNotEmpty() }?.let(OwnValues::encode))
        p[Keys.quickSeen] = s.quickSeen
        p[Keys.showValues] = s.showValues
        p[Keys.updateCheck] = s.updateCheck
    }

    /**
     * How the queue shows for the view an earlier version stored: Cover Flow stays Cover Flow, and
     * the single cover, which had no list beside it, is the queue off.
     */
    private fun queueOfView(view: String?): QueueDisplay = if (view == "FLOW") QueueDisplay.FLOW else defaults.queue

    /** When this TV plays for the switch an earlier version stored: on, it also played while Klangbild was closed. */
    private fun playHereOf(on: Boolean?): PlayHere = if (on == true) PlayHere.ALWAYS else defaults.playHere

    /** The constant named [name], or [default] for none or a name this version does not know. */
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
