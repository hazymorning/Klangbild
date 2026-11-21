package io.github.hazymorning.klangbild.setup

import io.github.hazymorning.klangbild.config.QuickActions
import io.github.hazymorning.klangbild.data.HttpStatusException
import io.github.hazymorning.klangbild.data.ha.HaAuth
import io.github.hazymorning.klangbild.data.ha.HaAuthRejectedException
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.data.ha.HaRest
import io.github.hazymorning.klangbild.data.ha.LocalNetwork
import io.github.hazymorning.klangbild.domain.ActionKind
import io.github.hazymorning.klangbild.json.string
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One speaker offered on the setup page. */
data class SpeakerOption(val entityId: String, val name: String, val state: String, val musicAssistant: Boolean)

/** One entity offered with its state in words: a light to join in, or a sensor to tell whether someone is in the room. */
data class StateOption(val entityId: String, val name: String, val state: String)

/**
 * One entity a button of one's own can work (a script, a scene, an automation, a button, a
 * switch); a script with the [fields] it declares.
 */
data class ActionOption(val entityId: String, val name: String, val fields: List<ScriptField> = emptyList())

/**
 * A field a script declares, as its selector asks for it ([type]: [TEXT], [NUMBER], [BOOLEAN],
 * [SELECT] with its [options] as value and label, [ENTITY] of its [domains]).
 */
data class ScriptField(
    val key: String,
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
    val type: String = TEXT,
    val options: List<Pair<String, String>> = emptyList(),
    val default: JsonElement? = null,
    val example: String? = null,
    val domains: List<String> = emptyList(),
) {
    companion object {
        const val TEXT = "text"
        const val NUMBER = "number"
        const val BOOLEAN = "boolean"
        const val SELECT = "select"
        const val ENTITY = "entity"
    }
}

/**
 * Any entity, for the values in the status line and the buttons' conditions: its raw [state]
 * and [unit], its attributes in words ([attributes]) and its lists by their count ([lists]).
 */
data class EntityOption(
    val entityId: String,
    val name: String,
    val state: String,
    val unit: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val lists: Map<String, Int> = emptyMap(),
)

sealed interface DiscoverResult {
    data class Ok(
        val speakers: List<SpeakerOption>,
        val lights: List<StateOption> = emptyList(),
        val actions: List<ActionOption> = emptyList(),
        val entities: List<EntityOption> = emptyList(),
        val sensors: List<StateOption> = emptyList(),
    ) : DiscoverResult

    /** [refused]: Home Assistant refused the credentials. */
    data class Failed(val message: String, val refused: Boolean = false) : DiscoverResult
}

/** What Home Assistant offers the setup page, and the fields its scripts declare. */
object Discovery {
    /**
     * Asks Home Assistant at [url] for its media players, lights, what a button can work and
     * every entity for the values and conditions (REST `/api/states`), and for the fields its
     * scripts declare (`/api/services`, as far as it answers), with [credentials]. Every
     * failure becomes one calm sentence for the setup page.
     */
    suspend fun discover(http: HttpClient, url: String, credentials: HaCredentials, t: Texts): DiscoverResult {
        val rest = HaRest(http, HaAuth(http, url, credentials))
        return try {
            coroutineScope {
                // A script's fields help, but the page works without them.
                val services = async {
                    try {
                        rest.services()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                options(rest.allStates(), t, services.await())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: HaAuthRejectedException) {
            DiscoverResult.Failed(t[Strings.discoverRejected], refused = true)
        } catch (e: IOException) {
            when (e) {
                is HttpStatusException -> DiscoverResult.Failed(t.format(Strings.discoverUnexpected, e.code))
                is LocalNetwork.CleartextRefused -> DiscoverResult.Failed(t[Strings.discoverCleartext])
                else -> DiscoverResult.Failed(t.format(Strings.discoverUnreachable, url))
            }
        } catch (_: IllegalArgumentException) {
            DiscoverResult.Failed(t[Strings.discoverInvalidAddress])
        }
    }

    /** What the setup page offers, from all states as JSON text ([body]), with the scripts' fields from all [services]. */
    fun options(body: String, t: Texts, services: String? = null): DiscoverResult {
        val arr = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return DiscoverResult.Failed(t[Strings.discoverGarbled])
        val states = arr.mapNotNull { it as? JsonObject }
        val fields = services?.let(::scriptFields).orEmpty()
        fun JsonObject.id() = (this["entity_id"] as? JsonPrimitive)?.content
        fun JsonObject.attrs() = this["attributes"] as? JsonObject
        fun JsonObject.name() = (attrs()?.get("friendly_name") as? JsonPrimitive)?.content ?: id()
        fun JsonObject.state() = (this["state"] as? JsonPrimitive)?.content ?: "unknown"
        val speakers = states.mapNotNull { o ->
            val id = o.id()?.takeIf { it.startsWith("media_player.") } ?: return@mapNotNull null
            val attrs = o.attrs()
            val ma = attrs?.get("mass_player_type") != null ||
                (attrs?.get("app_id") as? JsonPrimitive)?.content == "music_assistant"
            // A screen of Music Assistant's own (a TV it plays to) is no speaker for this one.
            val display = (attrs?.get("device_class") as? JsonPrimitive)?.content == "tv" && ma
            if (display) null else SpeakerOption(id, o.name() ?: id, stateWord(o.state(), t), ma)
        }.sortedWith(compareByDescending<SpeakerOption> { it.musicAssistant }.thenBy { it.name.lowercase() })
        // Every light Home Assistant controls can join in: in color, in white, or by its brightness.
        val lights = states.mapNotNull { o ->
            val id = o.id()?.takeIf { it.startsWith("light.") } ?: return@mapNotNull null
            StateOption(id, o.name() ?: id, stateWord(o.state(), t))
        }.sortedBy { it.name.lowercase() }
        // What the quick settings' own buttons can work, by kind (scripts first), then by name;
        // a script with the fields it declares.
        val actions = states.mapNotNull { o ->
            val id = o.id()?.takeIf { ActionKind.of(it) != null } ?: return@mapNotNull null
            ActionOption(id, o.name() ?: id, if (id.startsWith("script.")) fields[id.substringAfter('.')].orEmpty() else emptyList())
        }.sortedWith(compareBy<ActionOption> { ActionKind.DOMAINS.indexOf(it.entityId.substringBefore('.')) }.thenBy { it.name.lowercase() })
        // Every entity, for the values in the status line and the buttons' conditions.
        val entities = states.mapNotNull { o ->
            val id = o.id() ?: return@mapNotNull null
            entityOption(id, o.name() ?: id, o.state(), o.attrs())
        }.sortedBy { it.name.lowercase() }
        // Every binary sensor can tell whether someone is in the room; presence sensors first, then motion sensors.
        val sensors = states.mapNotNull { o ->
            val id = o.id()?.takeIf { it.startsWith("binary_sensor.") } ?: return@mapNotNull null
            val rank = PRESENCE_CLASSES.indexOf(o.attrs()?.string("device_class")).let { if (it < 0) PRESENCE_CLASSES.size else it }
            rank to StateOption(id, o.name() ?: id, stateWord(o.state(), t))
        }.sortedWith(compareBy<Pair<Int, StateOption>> { it.first }.thenBy { it.second.name.lowercase() }).map { it.second }
        return DiscoverResult.Ok(speakers, lights, actions, entities, sensors)
    }

    /** The device classes of sensors that see someone in a room, the likeliest first. */
    private val PRESENCE_CLASSES = listOf("presence", "occupancy", "motion")

    /**
     * An entity as the page offers it: its raw state and unit, and the attributes worth
     * showing or comparing (words and numbers cut short, lists by their count). What only
     * describes the entity (its name, icon, unit, what it supports) and long lists of
     * choices are left out.
     */
    private fun entityOption(id: String, name: String, state: String, attrs: JsonObject?): EntityOption {
        val words = LinkedHashMap<String, String>()
        val lists = LinkedHashMap<String, Int>()
        attrs?.forEach { (key, v) ->
            if (key in DESCRIBING || key.endsWith("_list") || words.size + lists.size >= MAX_ATTRIBUTES) return@forEach
            when (v) {
                is JsonPrimitive -> if (v !is JsonNull) words[key] = v.content.take(MAX_ATTRIBUTE_TEXT)
                is JsonArray -> lists[key] = v.size
                else -> Unit
            }
        }
        val unit = attrs?.string("unit_of_measurement")
        return EntityOption(id, name, state.take(MAX_ATTRIBUTE_TEXT), unit, words, lists)
    }

    /** Attributes that describe an entity rather than tell how it stands. */
    private val DESCRIBING = setOf(
        "friendly_name", "icon", "entity_picture", "unit_of_measurement", "device_class", "state_class", "supported_features",
        "attribution", "restored", "editable", "id", "assumed_state", "supported_color_modes", "options", "entity_picture_local",
    )

    /** At most this many attributes per entity, each cut to this many characters. */
    private const val MAX_ATTRIBUTES = 24
    private const val MAX_ATTRIBUTE_TEXT = 40

    /**
     * The fields each script declares, from all of Home Assistant's actions as JSON text
     * (`script.<id>` is the action `script.<id>`): what the page asks for. A field's
     * selector says how: a switch, a number, a choice, an entity, or text.
     */
    fun scriptFields(services: String): Map<String, List<ScriptField>> {
        val all = runCatching { Json.parseToJsonElement(services) as? JsonArray }.getOrNull() ?: return emptyMap()
        val script = all.firstOrNull { ((it as? JsonObject)?.get("domain") as? JsonPrimitive)?.content == "script" } as? JsonObject
        val scripts = script?.get("services") as? JsonObject ?: return emptyMap()
        return scripts.mapValues { (_, desc) ->
            val declared = (desc as? JsonObject)?.get("fields") as? JsonObject ?: return@mapValues emptyList()
            // Fields may stand in sections of their own.
            val flat = declared.entries.flatMap { (key, f) ->
                val inner = (f as? JsonObject)?.get("fields") as? JsonObject
                inner?.entries?.map { it.key to it.value } ?: listOf(key to f)
            }
            flat.mapNotNull { (key, f) -> (f as? JsonObject)?.let { scriptField(key, it) } }
        }
    }

    private fun scriptField(key: String, f: JsonObject): ScriptField? {
        if (!QuickActions.isVariableName(key)) return null
        val selector = f["selector"] as? JsonObject
        val (kind, config) = selector?.entries?.firstOrNull()?.let { it.key to (it.value as? JsonObject) } ?: ("text" to null)
        val multiple = (config?.get("multiple") as? JsonPrimitive)?.content == "true"
        val type = when {
            multiple -> ScriptField.TEXT
            kind == "boolean" -> ScriptField.BOOLEAN
            kind == "number" -> ScriptField.NUMBER
            kind == "select" -> ScriptField.SELECT
            kind == "entity" -> ScriptField.ENTITY
            else -> ScriptField.TEXT
        }
        val options = (config?.get("options") as? JsonArray).orEmpty().mapNotNull { o ->
            when (o) {
                is JsonPrimitive -> o.content to o.content
                is JsonObject -> o.string("value")?.let { v -> v to (o.string("label") ?: v) }
                else -> null
            }
        }
        // An entity selector names its domains directly, or in its filters.
        fun domains(o: JsonObject?): List<String> = when (val d = o?.get("domain")) {
            is JsonPrimitive -> listOf(d.content)
            is JsonArray -> d.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }
        val filters = when (val fl = config?.get("filter")) {
            is JsonObject -> listOf(fl)
            is JsonArray -> fl.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }
        return ScriptField(
            key = key,
            name = f.string("name") ?: key,
            description = f.string("description"),
            required = (f["required"] as? JsonPrimitive)?.content == "true",
            type = if (type == ScriptField.SELECT && options.isEmpty()) ScriptField.TEXT else type,
            options = options,
            default = f["default"],
            example = (f["example"] as? JsonPrimitive)?.content,
            domains = (domains(config) + filters.flatMap(::domains)).distinct(),
        )
    }

    private fun stateWord(state: String, t: Texts) = when (state) {
        "playing" -> t[Strings.entityStatePlaying]
        "paused" -> t[Strings.entityStatePaused]
        "idle" -> t[Strings.entityStateIdle]
        "off" -> t[Strings.entityStateOff]
        "on" -> t[Strings.entityStateOn]
        "unavailable" -> t[Strings.entityStateUnavailable]
        "buffering" -> t[Strings.entityStateBuffering]
        "standby" -> t[Strings.entityStateStandby]
        else -> t[Strings.entityStateUnknown]
    }
}
