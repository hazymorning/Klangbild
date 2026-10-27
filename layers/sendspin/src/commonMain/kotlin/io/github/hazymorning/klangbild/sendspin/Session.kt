package io.github.hazymorning.klangbild.sendspin

import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.random.Random

/** How a connection carries messages; the session never sees the WebSocket itself. */
interface Transport {
    fun sendText(text: String): Boolean
    fun sendBinary(bytes: ByteArray): Boolean

    /** Closes the connection without another word. */
    fun close()
}

/** The server answered as a version of the protocol from before Music Assistant 2.10.7 does. */
private class OldServerException : Exception()

/** A server this speaker has been paired with, and the PSK the pairing gave. */
class PairingRecord(val serverId: String, val psk: ByteArray)

/** Why a session ended. */
enum class SessionEnd {
    /** Something broke the protocol, or a message did not open: closed without a word. */
    BROKEN,

    /** The server is older than this protocol: Music Assistant before 2.10.7. */
    OLD_SERVER,

    /** The server speaks a newer version of the protocol than this app. */
    NEW_SERVER,

    /** The server refused how this speaker introduced itself. */
    REFUSED,

    /** The server wanted more than this speaker allows it (`unauthorized`). */
    UNAUTHORIZED,

    /** The server ended the pairing ([Listener.unpaired]). */
    UNPAIRED,

    /** Klangbild ended it. */
    GOODBYE,

    /** The connection went away. */
    LOST,
}

/**
 * One Sendspin connection as the client sees it (connection.md, messaging.md, pairing.md):
 * `client/init`, the Noise handshake as its responder, the encrypted hellos and activation, then
 * time sync, the player's stream and commands, beats, pairing by PSK and the server's
 * re-handshakes. It knows no sockets ([Transport]) and no audio output ([Listener]).
 *
 * Everything runs on whichever thread calls it, one call at a time: messages from the
 * connection's reader thread, bursts and state from a timer. Calls are serialized here.
 */
class Session(
    private val transport: Transport,
    private val identity: KeyPair,
    /** This speaker's pairing PSK: always among the candidates, so a server can pair with its token. */
    private val pairingPsk: ByteArray,
    private val records: () -> List<PairingRecord>,
    private val hello: Hello,
    private val listener: Listener,
    /** The local clock in microseconds (monotonic): the clock of [ClockMap]'s local side. */
    private val nowUs: () -> Long,
    private val random: Random = CryptographyRandom.Default,
) : SynchronizedObject() {
    /** What the session tells the speaker. Called on the session's calling thread, while it holds the session. */
    interface Listener {
        /** The player's state now (volume, mute, delay, timing), for `client/state`. */
        fun playerState(): PlayerState

        /** The server's clock as the time filter has it after each burst; null while it has not settled. */
        fun clock(map: ClockMap?)

        /** A player stream starts (or its format is set again). */
        fun streamStart()

        /** [length] bytes of PCM at [offset] in [bytes], to be played from server time [timestampUs]; [arrivalUs] when it came. */
        fun audio(timestampUs: Long, sendAheadUs: Long, bytes: ByteArray, offset: Int, length: Int, arrivalUs: Long)

        /** The player's queued audio goes (a seek, another song). */
        fun clear()

        /** The player's stream ended. */
        fun streamEnd()

        fun volume(volume: Int)
        fun mute(muted: Boolean)
        fun outputDelay(ms: Int)

        /** The group plays or not. */
        fun group(playing: Boolean, groupId: String, name: String)

        /** A beat heard at server time [timestampUs], [downbeat] when it begins a bar. */
        fun beat(timestampUs: Long, downbeat: Boolean)

        /** The visualizer's stream (beats) was cleared or ended. */
        fun beatsCleared()

        /** Pairing finished: keep [record]. */
        fun paired(record: PairingRecord)

        /** A long-term PSK was used to connect. */
        fun used(serverId: String)

        /** The server ended the pairing with [serverId]: forget its record. */
        fun unpaired(serverId: String)

        /** The session is over; [detail] says more for the log (never a secret). */
        fun ended(end: SessionEnd, detail: String)
    }

    private enum class Step { NEW, INIT_SENT, AWAIT_NOISE, AWAIT_HELLO, AWAIT_ACTIVATE, ACTIVE, OVER }

    private var step = Step.NEW
    private var clientInit = ByteArray(0)
    private var serverId: String? = null
    private var serverKey: ByteArray? = null
    private var channel: NoiseChannel? = null
    private var matched = PskCategory.SENTINEL
    private val reassembler = Reassembler(MAX_MESSAGE) { it == MessageId.JSON || it == MessageId.AUDIO || it == MessageId.BEAT }

    private var prologue = ByteArray(0)

    /** The long-term PSK just handed to the server, kept once it says it has stored it. */
    private var pendingRecord: PairingRecord? = null

    private var roles = emptySet<String>()
    private var everActivated = false
    private var pairingIndex = 0
    private var playerStream = false
    private var beatStream = false
    private var stateSent = false

    private val filter = TimeFilter()
    private var burstLeft = 0
    private var burstSentUs = -1L
    private var burstBest: TimeFilter.Measurement? = null
    private var burstBestAt = 0L
    private var bursts = 0

    /** Whether the server has activated the session at least once: the connection is set up, roles or not. */
    val activated: Boolean get() = synchronized(this) { everActivated }

    /** Whether the time filter has settled enough to schedule playback. */
    val synced: Boolean get() = synchronized(this) { bursts >= SYNC_BURSTS && filter.errorUs < SYNC_ERROR_US }

    /**
     * What `client/state` says of availability: true once the clock has settled, and then for
     * the rest of the connection, so a wobble of the clock never takes the speaker out of its
     * group (the playback waits for the clock meanwhile).
     */
    private var available = false

    /** Opens the conversation with `client/init`. */
    fun start(): Unit = synchronized(this) {
        check(step == Step.NEW)
        val text = Outgoing.clientInit(B64.encode(identity.publicKey)).encode()
        clientInit = text.encodeToByteArray()
        step = Step.INIT_SENT
        transport.sendText(text)
    }

    /** A text message arrived (only the handshake's are text). */
    fun onText(text: String): Unit = synchronized(this) {
        try {
            when (step) {
                Step.INIT_SENT -> serverInit(text)
                Step.AWAIT_NOISE -> noiseFirst(text)
                Step.OVER -> Unit
                // Text after the switch to encrypted messages is a silent failure.
                else -> throw ProtocolException("text message in transport mode")
            }
        } catch (_: OldServerException) {
            end(SessionEnd.OLD_SERVER, "first handshake message without psk_category")
        } catch (e: ProtocolException) {
            end(SessionEnd.BROKEN, e.message ?: "protocol")
        } catch (e: NoiseException) {
            end(SessionEnd.BROKEN, e.message ?: "noise")
        }
    }

    /** A binary message arrived at local time [arrivalUs]. */
    fun onBinary(bytes: ByteArray, arrivalUs: Long): Unit = synchronized(this) {
        if (step == Step.OVER) return
        try {
            val c = channel ?: throw ProtocolException("binary message before the handshake")
            if (bytes.size > Noise.MAX_MESSAGE) throw ProtocolException("message beyond Noise's limit")
            val plain = c.receive.decrypt(EMPTY, bytes)
            val whole = reassembler.accept(plain) ?: return
            when (whole[0].toInt() and 0xff) {
                MessageId.JSON -> json(Message.parse(whole.decodeToString(1, whole.size)), arrivalUs)
                MessageId.AUDIO -> audio(whole, arrivalUs)
                MessageId.BEAT -> beat(whole)
            }
        } catch (_: OldServerException) {
            // A re-handshake in an older version's form: the server changed underneath.
            end(SessionEnd.BROKEN, "re-handshake message without psk_category")
        } catch (e: ProtocolException) {
            end(SessionEnd.BROKEN, e.message ?: "protocol")
        } catch (e: NoiseException) {
            end(SessionEnd.BROKEN, e.message ?: "noise")
        }
    }

    /**
     * The connection went away underneath: closed by the server ([byServer]), or lost (a failure,
     * a timeout).
     */
    fun onClosed(byServer: Boolean): Unit = synchronized(this) {
        when {
            step == Step.OVER -> Unit
            // Music Assistant before 2.10 closes at once on a client/init it does not know; a
            // connection lost meanwhile says nothing about the server's version.
            step == Step.INIT_SENT && byServer -> end(SessionEnd.OLD_SERVER, "closed before server/init")
            else -> end(SessionEnd.LOST, "connection closed")
        }
    }

    /** Starts a burst of time messages (messaging.md: at least eight, each after the answer to the one before). */
    fun burst(): Unit = synchronized(this) {
        if (step != Step.ACTIVE) return
        // A burst whose answer never came is given up.
        if (burstLeft > 0 && nowUs() - burstSentUs < BURST_STALL_US) return
        burstLeft = BURST
        burstBest = null
        sendTime()
    }

    /**
     * The local clock stood still meanwhile (the device slept): the time filter starts afresh,
     * with a burst at once. Until it has settled again, the playback waits for it.
     */
    fun clockReset(): Unit = synchronized(this) {
        filter.reset()
        bursts = 0
        burstLeft = 0
        listener.clock(null)
        burst()
    }

    /** The player's state changed here (volume by the TV, the output delay): the server hears of it. */
    fun stateChanged(): Unit = synchronized(this) {
        if (step == Step.ACTIVE && stateSent) sendState()
    }

    /**
     * Leaves the group with `client/leave` (messaging.md): something else on the TV took its
     * sound, and the server stops playing here rather than on in a group that no longer hears it.
     */
    fun leave(): Unit = synchronized(this) {
        if (step == Step.ACTIVE) send(Outgoing.leave())
    }

    /** Ends the session with `client/goodbye` ([reason] as the spec names it). */
    fun goodbye(reason: String): Unit = synchronized(this) {
        if (step == Step.OVER) return
        if (channel != null) send(Outgoing.goodbye(reason))
        end(SessionEnd.GOODBYE, reason)
    }

    // --- the handshake --------------------------------------------------------------------

    private fun serverInit(text: String) {
        val m = Message.parse(text)
        when (m.type) {
            "server/init" -> Unit
            "server/error" -> {
                val reason = m.string("reason")
                end(if (reason == "unsupported_version") SessionEnd.NEW_SERVER else SessionEnd.REFUSED, "server/error ${reason ?: "?"}")
                return
            }
            // A server from before this handshake answers something else, or nothing at all.
            else -> {
                end(SessionEnd.OLD_SERVER, "answered ${m.type.take(40)}")
                return
            }
        }
        if (m.int("version") != 1) {
            end(SessionEnd.NEW_SERVER, "server/init version ${m.payload["version"]}")
            return
        }
        val id = m.string("server_id")
        serverKey = B64.key(id) ?: throw ProtocolException("server_id is not a key")
        serverId = id
        prologue = clientInit + text.encodeToByteArray()
        step = Step.AWAIT_NOISE
    }

    private fun noiseFirst(text: String) {
        val m = Message.parse(text)
        if (m.type != "noise/handshake") throw ProtocolException("expected noise/handshake")
        val (message, newChannel, category) = respond(m, prologue, initial = true)
        // In place before the answer goes: the server's first encrypted message may follow at once.
        channel = newChannel
        matched = category
        step = Step.AWAIT_HELLO
        transport.sendText(Outgoing.noiseHandshake(message).encode())
        if (category == PskCategory.LONG_TERM) listener.used(checkNotNull(serverId))
    }

    /**
     * Answers the server's first handshake message: reads which PSK it names, picks it (a miss
     * in the [initial] handshake falls back to the Sentinel), and writes the second message.
     */
    private fun respond(m: Message, prologue: ByteArray, initial: Boolean): Triple<ByteArray, NoiseChannel, PskCategory> {
        val data = m.string("data")?.let(B64::decode) ?: throw ProtocolException("noise/handshake without data")
        val handshake = KkPsk2Handshake(false, prologue, identity, checkNotNull(serverKey)) { KeyPair.generate(random) }
        val payload = try {
            Json.parseToJsonElement(handshake.readFirst(data).decodeToString()) as? JsonObject
        } catch (e: NoiseException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: throw ProtocolException("first handshake payload is not an object")
        val pskId = (payload["psk_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // Music Assistant 2.10.0 to 2.10.6 names the PSK without its category, and speaks an older protocol after it.
        if (pskId != null && !payload.containsKey("psk_category")) throw OldServerException()
        val category = PskCategory.of((payload["psk_category"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        if (pskId == null || category == null) throw ProtocolException("first handshake payload without psk_id or psk_category")
        var chosen: Pair<ByteArray, PskCategory>? = when (category) {
            PskCategory.SENTINEL -> if (Psk.id(Psk.SENTINEL) == pskId) Psk.SENTINEL to category else null
            PskCategory.PAIRING -> if (Psk.id(pairingPsk) == pskId) pairingPsk to category else null
            PskCategory.LONG_TERM -> records().firstOrNull { Psk.id(it.psk) == pskId }?.let { r ->
                // A long-term PSK belongs to one server: another server naming it fails the handshake.
                if (r.serverId != serverId) throw ProtocolException("long-term PSK of another server")
                r.psk to category
            }
        }
        if (chosen == null) {
            if (!initial) throw ProtocolException("unknown PSK in a re-handshake")
            chosen = Psk.SENTINEL to PskCategory.SENTINEL
        }
        val (message, newChannel) = handshake.writeSecond(chosen.first, EMPTY_OBJECT)
        return Triple(message, newChannel, chosen.second)
    }

    // --- encrypted messages ---------------------------------------------------------------

    private fun json(m: Message, arrivalUs: Long) {
        when (step) {
            Step.AWAIT_HELLO -> {
                if (m.type != "server/hello") throw ProtocolException("expected server/hello, got ${m.type.take(40)}")
                send(Outgoing.clientHello(hello))
                step = Step.AWAIT_ACTIVATE
                return
            }
            Step.AWAIT_ACTIVATE -> if (m.type != "server/activate") {
                // Answers to what went out before a re-handshake may still come; nothing else counts until the activation.
                if (m.type == "server/time") time(m, arrivalUs)
                return
            }
            else -> Unit
        }
        // During a re-handshake only its second half and the activation under the new keys come.
        when (m.type) {
            "server/activate" -> activate(m)
            "server/time" -> time(m, arrivalUs)
            "server/command" -> command(m)
            "stream/start" -> streamStart(m)
            "stream/clear" -> streamClear(m)
            "stream/end" -> streamEnd(m)
            "group/update" -> group(m)
            "server/unpair" -> unpair()
            "noise/handshake" -> rehandshake(m)
            "server/pair-finalize" -> pairFinalized()
            "pair/abort" -> pendingRecord = null
        }
    }

    private fun activate(m: Message) {
        val acts = m.strings("activities")?.toSet() ?: emptySet()
        // Unpaired access is on: playback may come over the Sentinel or the pairing PSK too.
        val permitted = if (matched == PskCategory.LONG_TERM) setOf(PLAYBACK) else setOf(PLAYBACK, PAIRING)
        if (!permitted.containsAll(acts)) return refuse()
        val newRoles = if (m.payload.containsKey("active_roles")) m.strings("active_roles")?.toSet() ?: emptySet() else roles
        if (PAIRING in acts) {
            pairingIndex++
            val method = m.obj("pairing")?.get("method")?.let { (it as? JsonPrimitive)?.content }
            if (method != PAIRING_PSK || matched != PskCategory.PAIRING) {
                send(Outgoing.pairAbort("method_not_supported"))
            } else {
                // The Pairing PSK flow: both messages at once, a new long-term PSK in the second.
                val psk = ByteArray(Noise.KEY_BYTES).also(random::nextBytes)
                pendingRecord = PairingRecord(checkNotNull(serverId), psk)
                send(Outgoing.pairInit(pairingIndex))
                send(Outgoing.pairFinalize(psk))
            }
        }
        val old = roles
        roles = newRoles
        // A role taken away stops at once, whatever it still had queued.
        endStreams(player = Roles.PLAYER in old - newRoles, beats = Roles.VISUALIZER in old - newRoles)
        step = Step.ACTIVE
        everActivated = true
        // A role added needs its state: sent now, or once the clock has settled. The clock is
        // set going with the first role (a connection only for pairing needs none).
        if ((newRoles - old).isNotEmpty()) stateSent = false
        if (roles.isNotEmpty() && bursts == 0 && burstLeft == 0) burst() else if (stateOwed()) sendState()
    }

    private fun refuse() {
        send(Outgoing.goodbye("unauthorized"))
        end(SessionEnd.UNAUTHORIZED, "activities or roles not allowed")
    }

    private fun time(m: Message, arrivalUs: Long) {
        val t1 = m.long("client_transmitted") ?: return
        if (burstLeft <= 0 || t1 != burstSentUs) return
        val t2 = m.long("server_received") ?: return
        val t3 = m.long("server_transmitted") ?: return
        val measured = TimeFilter.Measurement.of(t1, t2, t3, arrivalUs)
        val best = burstBest
        if (best == null || measured.maxError < best.maxError) {
            burstBest = measured
            burstBestAt = arrivalUs
        }
        burstLeft--
        if (burstLeft > 0) {
            sendTime()
            return
        }
        val chosen = burstBest ?: return
        if (filter.update(chosen.offset, chosen.maxError, burstBestAt)) {
            bursts++
            listener.clock(if (synced) filter.snapshot() else null)
        }
        // Available once the clock can be trusted: a client/state says so.
        if (stateOwed()) sendState()
    }

    private fun sendTime() {
        burstSentUs = nowUs()
        send(Outgoing.clientTime(burstSentUs))
    }

    /**
     * A client/state is owed: none since a role came, or none yet saying the speaker is available
     * now that the clock can be trusted (a command answered before then said it was not).
     */
    private fun stateOwed() = synced && roles.isNotEmpty() && (!stateSent || !available)

    private fun sendState() {
        val player = if (Roles.PLAYER in roles) listener.playerState() else null
        available = available || synced
        send(Outgoing.clientState(available = available, player = player, beats = Roles.VISUALIZER in roles))
        stateSent = true
    }

    private fun command(m: Message) {
        if (Roles.PLAYER !in roles) return
        val p = m.obj("player") ?: return
        when ((p["command"] as? JsonPrimitive)?.content) {
            "volume" -> (p["volume"] as? JsonPrimitive)?.intOrNull?.let { listener.volume(it.coerceIn(0, 100)) }
            "mute" -> (p["mute"] as? JsonPrimitive)?.booleanOrNull?.let { listener.mute(it) }
            "set_output_delay" -> (p["output_delay_ms"] as? JsonPrimitive)?.intOrNull?.let { listener.outputDelay(it.coerceIn(0, 5_000)) }
            else -> return
        }
        sendState()
    }

    private fun streamStart(m: Message) {
        val player = m.obj("player")
        if (player != null && Roles.PLAYER in roles) {
            if (!PcmFormat.matches(player)) throw ProtocolException("stream format not offered")
            playerStream = true
            listener.streamStart()
        }
        if (m.obj("visualizer") != null && Roles.VISUALIZER in roles) beatStream = true
    }

    private fun streamClear(m: Message) {
        val targets = m.strings("roles")
        if (playerStream && (targets == null || "player" in targets)) listener.clear()
        if (beatStream && (targets == null || "visualizer" in targets)) listener.beatsCleared()
    }

    private fun streamEnd(m: Message) {
        val targets = m.strings("roles")
        endStreams(player = targets == null || "player" in targets, beats = targets == null || "visualizer" in targets)
    }

    /** Ends the player's stream if [player], and the visualizer's if [beats], each only while it runs. */
    private fun endStreams(player: Boolean, beats: Boolean) {
        if (player && playerStream) {
            playerStream = false
            listener.streamEnd()
        }
        if (beats && beatStream) {
            beatStream = false
            listener.beatsCleared()
        }
    }

    private fun group(m: Message) {
        listener.group(m.string("playback_state") == "playing", m.string("group_id") ?: "", m.string("group_name") ?: "")
    }

    private fun unpair() {
        if (matched != PskCategory.LONG_TERM) return
        listener.unpaired(checkNotNull(serverId))
        send(Outgoing.goodbye("unpaired"))
        end(SessionEnd.UNPAIRED, "server/unpair")
    }

    private fun pairFinalized() {
        val record = pendingRecord ?: return
        pendingRecord = null
        listener.paired(record)
    }

    /**
     * The server swaps the session's keys (connection.md, "Re-handshake"): its first message
     * comes under the old keys, the answer goes under them too, and from then on the new keys
     * carry everything, starting with the server's activation.
     */
    private fun rehandshake(m: Message) {
        val c = checkNotNull(channel)
        val (message, newChannel, category) = respond(m, c.handshakeHash, initial = false)
        send(Outgoing.noiseHandshake(message))
        channel = newChannel
        matched = category
        pairingIndex = 0
        step = Step.AWAIT_ACTIVATE
        if (category == PskCategory.LONG_TERM) listener.used(checkNotNull(serverId))
    }

    private fun audio(whole: ByteArray, arrivalUs: Long) {
        if (whole.size < MessageId.AUDIO_HEADER) throw ProtocolException("audio message too short")
        if (!playerStream || Roles.PLAYER !in roles) return
        val length = whole.size - MessageId.AUDIO_HEADER
        if (length % PcmFormat.FRAME_BYTES != 0) throw ProtocolException("audio of part of a frame")
        listener.audio(whole.int64(1), whole.uint32(9), whole, MessageId.AUDIO_HEADER, length, arrivalUs)
    }

    private fun beat(whole: ByteArray) {
        if (whole.size < MessageId.VISUALIZER_HEADER) throw ProtocolException("visualizer message too short")
        if (!beatStream || Roles.VISUALIZER !in roles || whole.size < MessageId.VISUALIZER_HEADER + 1) return
        listener.beat(whole.int64(1), whole[MessageId.VISUALIZER_HEADER].toInt() and 1 == 1)
    }

    private fun send(m: Message) {
        val c = channel ?: return
        val body = utf8(m.encode())
        if (body.size + 1 > Noise.MAX_MESSAGE - Noise.TAG_BYTES) throw ProtocolException("message too long to send")
        val plain = ByteArray(body.size + 1)
        body.copyInto(plain, 1)
        transport.sendBinary(c.send.encrypt(EMPTY, plain))
    }

    private fun end(end: SessionEnd, detail: String) {
        if (step == Step.OVER) return
        step = Step.OVER
        endStreams(player = true, beats = true)
        transport.close()
        listener.ended(end, detail)
    }

    companion object {
        /** Messages per burst: the spec's minimum. */
        const val BURST = 8

        /** A burst whose answer has not come after this long is started again. */
        const val BURST_STALL_US = 2_000_000L

        /** The clock counts as settled after this many bursts with an error below [SYNC_ERROR_US]. */
        const val SYNC_BURSTS = 3
        const val SYNC_ERROR_US = 5_000.0

        /** The longest fragmented message kept: an audio chunk of 150 ms with room to spare. */
        const val MAX_MESSAGE = 256 * 1024

        private const val PLAYBACK = "playback"
        private const val PAIRING = "pairing"
        private const val PAIRING_PSK = "pairing_psk"
        private val EMPTY = ByteArray(0)
        private val EMPTY_OBJECT = "{}".encodeToByteArray()
    }
}
