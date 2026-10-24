package io.github.hazymorning.klangbild.config

import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.data.ha.HaUrls
import io.github.hazymorning.klangbild.domain.EntityId

/**
 * `am start` extras. Configuration extras (`ha_url`, `token`, `ma_url`, `ma_token`, `entity`,
 * `sleep_minutes`, `sleep_action`, `clock`, `tv_speaker` and the display options) are stored; `setup`
 * and `debug` only apply to this launch. A new address without its token drops the stored one:
 * no other app on the TV can redirect a token to an address of its choosing.
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
    const val TV_SPEAKER = "tv_speaker"
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
    const val QUEUE = "queue"
    const val BACKGROUND = "background"
    const val MOTION = "motion"
    const val LIGHTS_LEFT = "lights_left"
    const val LIGHTS_CENTER = "lights_center"
    const val LIGHTS_RIGHT = "lights_right"
    const val LIGHTS_BRIGHTNESS = "lights_level"
    const val SPOKEN_SKIP = "spoken_skip"
    const val SETUP = "setup"
    const val DEBUG = "debug"

    /** The names earlier versions used: they keep working, and the English name wins when both are given. */
    private val formerNames = mapOf(
        "bars_left" to LIGHTS_LEFT,
        "bars_center" to LIGHTS_CENTER,
        "bars_right" to LIGHTS_RIGHT,
        "bar_level" to LIGHTS_BRIGHTNESS,
        "view" to QUEUE,
    )

    private val stored = listOf(
        HA_URL, TOKEN, MA_URL, MA_TOKEN, TV_SPEAKER, ENTITY, SLEEP_MINUTES, SLEEP_ACTION, CLOCK, PROGRESS_BAR, NEXT, ALBUM, GROUP, VOLUME, VALUES, INTENSITY,
        VOLUME_KEYS, REMOTE, APPEARANCE, QUEUE, BACKGROUND, MOTION, LIGHTS_LEFT, LIGHTS_CENTER, LIGHTS_RIGHT, LIGHTS_BRIGHTNESS,
        SPOKEN_SKIP,
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
        intOf(extras[SLEEP_MINUTES])?.takeIf { it in 1..Settings.MAX_SLEEP_MINUTES }?.let { out = out.copy(sleepAfterMinutes = it) }
        (extras[SLEEP_ACTION] as? String)?.let { a ->
            when (a.lowercase()) {
                "release", "freigeben" -> out = out.copy(sleepAction = SleepAction.RELEASE)
                "close", "beenden" -> out = out.copy(sleepAction = SleepAction.CLOSE)
            }
        }
        // While this TV is the speaker followed, it keeps playing here (another is chosen on the phone first).
        boolOf(extras[TV_SPEAKER])?.let { on -> if (on || out.entityId != EntityId.THIS_TV) out = out.copy(tvSpeaker = on) }
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
        // Earlier versions named the queue's place the view: its single cover is the queue off.
        (extras[QUEUE] as? String)?.let { v ->
            when (v.trim().lowercase().replace(" ", "").replace("_", "")) {
                "off", "aus", "single", "einzelbild", "bild" -> QueueDisplay.OFF
                "list", "liste" -> QueueDisplay.LIST
                "flow", "coverflow" -> QueueDisplay.FLOW
                else -> null
            }?.let { out = out.copy(queue = it) }
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
        return out
    }

    fun session(given: Map<String, Any?>): SessionOptions {
        val extras = canonical(given)
        return SessionOptions(
            showSetup = boolOf(extras[SETUP]) ?: false,
            debugOverlay = boolOf(extras[DEBUG]) ?: false,
        )
    }

    fun hasStoredExtras(extras: Map<String, Any?>) = canonical(extras).keys.any { it in stored }

    /** `klangbild://open?light=subtle&album=off` */
    const val DEEP_LINK_SCHEME = "klangbild"

    /**
     * What a link may set: display options and the sleep time, but never the address, token,
     * speaker or the lights to control. Any page in a browser on the TV could open such a link.
     */
    private val linkKeys = setOf(
        CLOCK, PROGRESS_BAR, NEXT, ALBUM, GROUP, VOLUME, VALUES, INTENSITY, VOLUME_KEYS, REMOTE, SLEEP_MINUTES, SLEEP_ACTION,
        APPEARANCE, QUEUE, BACKGROUND, MOTION, LIGHTS_BRIGHTNESS, SPOKEN_SKIP,
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
