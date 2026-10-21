package io.github.hazymorning.klangbild.speaker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hazymorning.klangbild.config.SecretBox
import io.github.hazymorning.klangbild.sendspin.B64
import io.github.hazymorning.klangbild.sendspin.KeyPair
import io.github.hazymorning.klangbild.sendspin.Noise
import io.github.hazymorning.klangbild.sendspin.PairingRecord
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

private val Context.speakerStore: DataStore<Preferences> by preferencesDataStore(name = "speaker")

/**
 * What the speaker keeps about itself. [identity] is null when one is stored but cannot be
 * opened at the moment (the keystore refused): the speaker then stays away rather than come
 * back to Music Assistant as a new speaker under another key.
 */
class SpeakerMemory(
    val identity: KeyPair?,
    val pairingPsk: ByteArray?,
    /** Most recently used first. */
    val records: List<PairingRecord>,
    val outputDelayMs: Int,
    val volume: Int,
    val muted: Boolean,
)

/**
 * The speaker's own memory, apart from the settings: its Curve25519 identity (the `client_id`
 * Music Assistant knows it by), its pairing PSK, the pairing records, and what the spec says a
 * player keeps across restarts (output delay, volume, mute). Secrets are sealed with [secrets]
 * like the tokens; each value is written on its own, so one that cannot be opened is never lost
 * by writing another.
 */
class SpeakerStore(private val context: Context, private val secrets: SecretBox, private val random: SecureRandom = SecureRandom()) {
    private object Keys {
        val identity = stringPreferencesKey("identity")
        val pairingPsk = stringPreferencesKey("pairing_psk")
        val records = stringPreferencesKey("pairing_records")
        val outputDelay = intPreferencesKey("output_delay_ms")
        val volume = intPreferencesKey("volume")
        val muted = booleanPreferencesKey("muted")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The memory, the identity and the pairing PSK made on first use. */
    suspend fun load(): SpeakerMemory {
        context.speakerStore.edit { p ->
            if (Keys.identity !in p) p[Keys.identity] = seal(KeyPair.generate(random).privateKey)
            if (Keys.pairingPsk !in p) p[Keys.pairingPsk] = seal(ByteArray(Noise.KEY_BYTES).also(random::nextBytes))
        }
        val p = context.speakerStore.data.first()
        return SpeakerMemory(
            identity = p[Keys.identity]?.let(::open)?.takeIf { it.size == Noise.KEY_BYTES }?.let(KeyPair::of),
            pairingPsk = p[Keys.pairingPsk]?.let(::open)?.takeIf { it.size == Noise.KEY_BYTES },
            records = p[Keys.records]?.let(::records).orEmpty(),
            outputDelayMs = (p[Keys.outputDelay] ?: 0).coerceIn(0, MAX_OUTPUT_DELAY_MS),
            volume = (p[Keys.volume] ?: DEFAULT_VOLUME).coerceIn(0, 100),
            muted = p[Keys.muted] ?: false,
        )
    }

    suspend fun setOutputDelay(ms: Int) {
        context.speakerStore.edit { it[Keys.outputDelay] = ms.coerceIn(0, MAX_OUTPUT_DELAY_MS) }
    }

    suspend fun setVolume(volume: Int, muted: Boolean) {
        context.speakerStore.edit {
            it[Keys.volume] = volume.coerceIn(0, 100)
            it[Keys.muted] = muted
        }
    }

    /**
     * Keeps [record] first, replacing one for the same server; beyond [MAX_RECORDS] the least
     * recently used goes. Records that cannot be opened are given up for it.
     */
    suspend fun remember(record: PairingRecord) = editRecords { list -> listOf(record) + list.filter { it.serverId != record.serverId } }

    /** Moves the record of [serverId] first: it was just used. */
    suspend fun used(serverId: String) = editRecords { list ->
        val r = list.firstOrNull { it.serverId == serverId } ?: return@editRecords list
        listOf(r) + (list - r)
    }

    /** Forgets the record of [serverId] (the server unpaired). */
    suspend fun forget(serverId: String) = editRecords { list -> list.filter { it.serverId != serverId } }

    private suspend fun editRecords(change: (List<PairingRecord>) -> List<PairingRecord>) {
        context.speakerStore.edit { p ->
            val now = p[Keys.records]?.let(::records).orEmpty()
            val next = change(now).take(MAX_RECORDS)
            writeRecords(p, next)
        }
    }

    private fun writeRecords(p: MutablePreferences, list: List<PairingRecord>) {
        if (list.isEmpty()) {
            p.remove(Keys.records)
            return
        }
        val array = buildJsonArray {
            for (r in list) add(buildJsonObject {
                put("server_id", r.serverId)
                put("psk", B64.encode(r.psk))
            })
        }
        p[Keys.records] = secrets.seal(array.toString())
    }

    private fun records(stored: String): List<PairingRecord>? {
        val text = secrets.open(stored) ?: return null
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["server_id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val psk = (o["psk"] as? JsonPrimitive)?.content?.let(B64::key) ?: return@mapNotNull null
            PairingRecord(id, psk)
        }
    }

    private fun seal(bytes: ByteArray): String = secrets.seal(B64.encode(bytes))

    private fun open(stored: String): ByteArray? = secrets.open(stored)?.let(B64::decode)

    companion object {
        /** The spec asks for room for at least five. */
        const val MAX_RECORDS = 8
        const val MAX_OUTPUT_DELAY_MS = 5_000
        const val DEFAULT_VOLUME = 100
    }
}
