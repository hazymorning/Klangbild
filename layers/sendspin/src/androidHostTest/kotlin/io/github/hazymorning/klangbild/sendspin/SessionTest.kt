package io.github.hazymorning.klangbild.sendspin

import assertk.Assert
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAtLeast
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.support.expected
import assertk.assertions.support.show
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test

/**
 * The session against a server played here: Noise's initiator, the messages as messaging.md
 * and pairing.md give them. What either side sends waits in a queue until the test hands it
 * over, as a connection would.
 */
class SessionTest {
    private val random = CryptographyRandom.Default
    private val clientKeys = KeyPair.generate(random)
    private val serverKeys = KeyPair.generate(random)
    private val serverId = B64.encode(serverKeys.publicKey)
    private val pairingPsk = ByteArray(32).also(random::nextBytes)
    private var records = listOf<PairingRecord>()
    private var now = 1_000_000L

    /** What the speaker was told. */
    private val events = mutableListOf<String>()
    private val audio = mutableListOf<Pair<Long, Int>>()
    private var ended: SessionEnd? = null

    private val listener = object : Session.Listener {
        override fun playerState() = PlayerState(volume = 40, muted = false, outputDelayMs = 0, requiredLeadTimeMs = 500, minBufferMs = 200)
        override fun clock(map: ClockMap?) {
            if (map != null) events += "clock"
        }
        override fun streamStart() { events += "start" }
        override fun audio(timestampUs: Long, sendAheadUs: Long, bytes: ByteArray, offset: Int, length: Int, arrivalUs: Long) {
            audio += timestampUs to length
        }
        override fun clear() { events += "clear" }
        override fun streamEnd() { events += "end" }
        override fun volume(volume: Int) { events += "volume $volume" }
        override fun mute(muted: Boolean) { events += "mute $muted" }
        override fun outputDelay(ms: Int) { events += "delay $ms" }
        override fun group(playing: Boolean, groupId: String, name: String) { events += "group $playing" }
        override fun beat(timestampUs: Long, downbeat: Boolean) { events += "beat $timestampUs" }
        override fun beatsCleared() { events += "beats cleared" }
        override fun paired(record: PairingRecord) {
            events += "paired"
            records = listOf(record)
        }
        override fun used(serverId: String) { events += "used" }
        override fun unpaired(serverId: String) { events += "unpaired" }
        override fun ended(end: SessionEnd, detail: String) {
            ended = end
            events += "ended $end $detail"
        }
    }

    /** The server's side of one connection. */
    private inner class Server {
        val toClient = ArrayDeque<Any>()
        val fromClient = mutableListOf<Message>()
        var closed = false
        private var clientInit = ByteArray(0)
        private var channel: NoiseChannel? = null
        private var handshake: KkPsk2Handshake? = null
        private var psk = Psk.SENTINEL

        val transport = object : Transport {
            override fun sendText(text: String): Boolean {
                val m = Message.parse(text)
                if (m.type == "client/init") clientInit = text.toByteArray() else fromClient += m
                textFromClient(m)
                return true
            }
            override fun sendBinary(bytes: ByteArray): Boolean {
                val c = channel!!
                val plain = c.receive.decrypt(ByteArray(0), bytes)
                assertThat(plain[0]).isEqualTo(0.toByte())
                val m = Message.parse(String(plain, 1, plain.size - 1))
                fromClient += m
                messageFromClient(m)
                return true
            }
            override fun close() { closed = true }
        }

        /** How the server greets: which PSK it names, and how. */
        var greet: (Server) -> Unit = { it.handshakeWith(Psk.SENTINEL, PskCategory.SENTINEL) }
        var onMessage: (Message) -> Unit = {}

        /** Closes right after client/init, as a server from before the handshake does. */
        var closeNow = false

        fun handshakeWith(psk: ByteArray, category: PskCategory, idOf: ByteArray = psk, withCategory: Boolean = true) {
            val init = Message.of("server/init", buildJsonObject {
                put("server_id", serverId)
                put("version", 1)
            }).encode()
            toClient += init
            this.psk = psk
            val h = KkPsk2Handshake(true, clientInit + init.toByteArray(), serverKeys, clientKeys.publicKey) { KeyPair.generate(random) }
            handshake = h
            val payload = buildJsonObject {
                put("psk_id", Psk.id(idOf))
                if (withCategory) put("psk_category", category.code)
            }.toString().toByteArray()
            toClient += Message.of("noise/handshake", buildJsonObject { put("data", B64.encode(h.writeFirst(payload))) }).encode()
        }

        private fun textFromClient(m: Message) {
            when (m.type) {
                "client/init" -> greet(this)
                "noise/handshake" -> {
                    val (payload, c) = handshake!!.readSecond(psk, B64.decode(m.string("data")!!)!!)
                    assertThat(String(payload)).isEqualTo("{}")
                    channel = c
                    send(Message.of("server/hello", buildJsonObject { put("name", "Music Assistant") }))
                }
            }
        }

        /** Swaps the keys in band, naming [newPsk] as [category]. */
        fun rehandshake(newPsk: ByteArray, category: PskCategory) {
            val c = channel!!
            val h = KkPsk2Handshake(true, c.handshakeHash, serverKeys, clientKeys.publicKey) { KeyPair.generate(random) }
            handshake = h
            psk = newPsk
            rehandshaking = true
            val payload = buildJsonObject {
                put("psk_id", Psk.id(newPsk))
                put("psk_category", category.code)
            }.toString().toByteArray()
            send(Message.of("noise/handshake", buildJsonObject { put("data", B64.encode(h.writeFirst(payload))) }))
        }

        /** A re-handshake under way: nothing but its answer is taken up (connection.md, "Re-handshake"). */
        private var rehandshaking = false

        private fun messageFromClient(m: Message) {
            if (rehandshaking && m.type != "noise/handshake") return
            when (m.type) {
                "client/time" -> send(Message.of("server/time", buildJsonObject {
                    val t1 = m.long("client_transmitted")!!
                    put("client_transmitted", t1)
                    put("server_received", t1 + OFFSET + 100)
                    put("server_transmitted", t1 + OFFSET + 150)
                }))
                "noise/handshake" -> {
                    // The answer to a re-handshake: under the old keys, the new ones from here on.
                    val (_, c) = handshake!!.readSecond(psk, B64.decode(m.string("data")!!)!!)
                    channel = c
                    rehandshaking = false
                }
            }
            onMessage(m)
        }

        fun send(m: Message) {
            val body = m.encode().toByteArray()
            toClient += channel!!.send.encrypt(ByteArray(0), byteArrayOf(0) + body)
        }

        fun sendRaw(plain: ByteArray) {
            toClient += channel!!.send.encrypt(ByteArray(0), plain)
        }

        fun activate(activities: List<String>, roles: List<String>?, pairing: String? = null) = send(Message.of("server/activate", buildJsonObject {
            putJsonArray("activities") { activities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            if (roles != null) putJsonArray("active_roles") { roles.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            if (pairing != null) putJsonObject("pairing") { put("method", pairing) }
        }))
    }

    private val hello = Hello("Living room TV", "Model", "Maker", "2026.10", bufferCapacity = 1_000_000, beats = true, beatBufferCapacity = 4_096)

    private fun session(server: Server) = Session(server.transport, clientKeys, pairingPsk, { records }, hello, listener, { now }, random)

    /** Hands over everything the server has sent, and what that brings back, until it is quiet. */
    private fun pump(server: Server, session: Session) {
        while (server.toClient.isNotEmpty()) {
            now += 300
            when (val next = server.toClient.removeFirst()) {
                is String -> session.onText(next)
                is ByteArray -> session.onBinary(next, now)
            }
        }
    }

    /** Through the handshake, the hellos and an activation for playback, until the clock settles. */
    private fun connect(server: Server = Server()): Pair<Server, Session> {
        server.onMessage = { m -> if (m.type == "client/hello") server.activate(listOf("playback"), listOf(Roles.PLAYER, Roles.VISUALIZER)) }
        val s = session(server)
        s.start()
        // The activation starts the first burst; the rest follow on the timer.
        pump(server, s)
        repeat(Session.SYNC_BURSTS - 1) {
            now += 100_000
            s.burst()
            pump(server, s)
        }
        return server to s
    }

    @Test
    fun `it connects, introduces itself, settles the clock and reports available`() {
        val (server, s) = connect()
        val hello = server.fromClient.first { it.type == "client/hello" }
        assertThat(hello.string("name")).isEqualTo("Living room TV")
        assertThat(hello.strings("supported_roles")).isNotNull().containsExactly(Roles.PLAYER, Roles.VISUALIZER)
        // Nothing Music Assistant would take for an old client: no supported_commands or trust_level in the hello.
        assertThat(hello.obj("player@v1_support")!!.keys).containsExactlyInAnyOrder("supported_formats", "buffer_capacity")
        assertThat(server.fromClient.count { it.type == "client/time" }).isEqualTo(Session.BURST * Session.SYNC_BURSTS)
        val state = server.fromClient.single { it.type == "client/state" }
        assertThat(state.bool("available")).isNotNull().isTrue()
        assertThat(state.obj("player")!!.keys).containsAtLeast("volume", "muted", "output_delay_ms", "supported_commands")
        assertThat(state.obj("visualizer")!!["types"].toString()).isEqualTo("[\"beat\"]")
        assertThat(s.synced).isTrue()
        assertThat(events).contains("clock")
    }

    @Test
    fun `an approval that comes after the first activation brings the player`() {
        // Music Assistant 2.10.7 lets an unpaired speaker in first, then adds its roles.
        val server = Server()
        server.onMessage = { m ->
            if (m.type == "client/hello") {
                server.activate(emptyList(), emptyList())
                server.activate(listOf("playback"), listOf(Roles.PLAYER))
            }
        }
        val s = session(server)
        s.start()
        pump(server, s)
        assertThat(s.activated).isTrue()
        repeat(Session.SYNC_BURSTS - 1) {
            now += 300_000
            s.burst()
            pump(server, s)
        }
        assertThat(s.synced).isTrue()
        val state = server.fromClient.single { it.type == "client/state" }
        assertThat(state.bool("available")).isNotNull().isTrue()
        assertThat(state.obj("player")).isNotNull()
    }

    @Test
    fun `once available it stays so, and a clock that starts again waits for its bursts`() {
        val (server, s) = connect()
        val clocks = events.count { it == "clock" }
        s.clockReset()
        assertThat(s.synced).isFalse()
        // The playback is told to wait: no clock until it has settled again.
        pump(server, s)
        assertThat(events.count { it == "clock" }).isEqualTo(clocks)
        s.stateChanged()
        assertThat(server.fromClient.last { it.type == "client/state" }.bool("available")).isNotNull().isTrue()
        repeat(Session.SYNC_BURSTS) {
            now += 300_000
            s.burst()
            pump(server, s)
        }
        assertThat(s.synced).isTrue()
        assertThat(events.count { it == "clock" }).isGreaterThan(clocks)
    }

    @Test
    fun `a command before the clock settles still leaves it available once it has`() {
        val server = Server()
        server.onMessage = { m -> if (m.type == "client/hello") server.activate(listOf("playback"), listOf(Roles.PLAYER)) }
        val s = session(server)
        s.start()
        pump(server, s)
        assertThat(s.synced).isFalse()
        server.send(Message.of("server/command", buildJsonObject {
            putJsonObject("player") {
                put("command", "volume")
                put("volume", 30)
            }
        }))
        pump(server, s)
        assertThat(server.fromClient.last { it.type == "client/state" }.bool("available")).isNotNull().isFalse()
        repeat(Session.SYNC_BURSTS - 1) {
            now += 300_000
            s.burst()
            pump(server, s)
        }
        assertThat(s.synced).isTrue()
        assertThat(server.fromClient.last { it.type == "client/state" }.bool("available")).isNotNull().isTrue()
    }

    @Test
    fun `a stream plays its chunks and obeys the server's commands`() {
        val (server, s) = connect()
        server.send(Message.of("stream/start", buildJsonObject {
            put("server_transmitted", 5)
            put("player", PcmFormat.json())
        }))
        server.sendRaw(byteArrayOf(4) + long(123_456_789) + int(25_000) + ByteArray(4_800))
        server.send(Message.of("server/command", buildJsonObject {
            putJsonObject("player") {
                put("command", "volume")
                put("volume", 30)
            }
        }))
        server.send(Message.of("stream/end", JsonObject(emptyMap())))
        pump(server, s)
        assertThat(audio).containsExactlyInAnyOrder(123_456_789L to 4_800)
        assertThat(events).containsInOrder("start", "volume 30", "end")
        // The new volume is reported back.
        assertThat(server.fromClient.count { it.type == "client/state" }).isEqualTo(2)
        assertThat(ended).isNull()
    }

    @Test
    fun `audio in fragments comes together again`() {
        val (server, s) = connect()
        server.send(Message.of("stream/start", buildJsonObject { put("player", PcmFormat.json()) }))
        val payload = long(77) + int(0) + ByteArray(1_200)
        server.sendRaw(byteArrayOf(1, 2, 4) + payload.copyOfRange(0, 500))
        server.sendRaw(byteArrayOf(1, 1) + payload.copyOfRange(500, payload.size))
        pump(server, s)
        assertThat(audio).containsExactlyInAnyOrder(77L to 1_200)
    }

    @Test
    fun `beats come while the visualizer streams`() {
        val (server, s) = connect()
        server.send(Message.of("stream/start", buildJsonObject {
            putJsonObject("visualizer") {
                put("types", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("beat")) })
                put("rate_max", 1)
                put("tracks_downbeats", false)
            }
        }))
        server.sendRaw(byteArrayOf(17) + long(9_000) + byteArrayOf(0))
        pump(server, s)
        assertThat(events).contains("beat 9000")
    }

    @Test
    fun `a session that ends ends its streams, the beats too`() {
        val (server, s) = connect()
        server.send(Message.of("stream/start", buildJsonObject {
            put("player", PcmFormat.json())
            putJsonObject("visualizer") { }
        }))
        pump(server, s)
        s.goodbye("shutdown")
        assertThat(events).containsInOrder("start", "end", "beats cleared", "ended GOODBYE shutdown")
    }

    @Test
    fun `pairing by PSK keeps a long-term PSK and the next connection uses it`() {
        val server = Server()
        server.greet = { it.handshakeWith(pairingPsk, PskCategory.PAIRING) }
        server.onMessage = { m ->
            when (m.type) {
                "client/hello" -> server.activate(listOf("pairing"), emptyList(), pairing = "pairing_psk")
                "client/pair-finalize" -> {
                    server.send(Message.of("server/pair-finalize"))
                    server.rehandshake(B64.decode(m.string("long_term_psk")!!)!!, PskCategory.LONG_TERM)
                }
                "noise/handshake" -> server.activate(listOf("playback"), listOf(Roles.PLAYER))
            }
        }
        val s = session(server)
        s.start()
        pump(server, s)
        val init = server.fromClient.single { it.type == "client/pair-init" }
        assertThat(init.int("pairing_index")).isEqualTo(1)
        assertThat(events).containsInOrder("paired", "used")
        assertThat(records.single().serverId).isEqualTo(serverId)
        // The long-term PSK brings the player.
        server.send(Message.of("stream/start", buildJsonObject { put("player", PcmFormat.json()) }))
        pump(server, s)
        assertThat(events).contains("start")
        assertThat(ended).isNull()

        // Next time the server names the long-term PSK.
        events.clear()
        val again = Server()
        again.greet = { it.handshakeWith(records.single().psk, PskCategory.LONG_TERM) }
        connect(again)
        assertThat(events).contains("used")
        assertThat(ended).isNull()
    }

    @Test
    fun `a PSK it does not hold falls back to the Sentinel`() {
        val server = Server()
        server.greet = { it.handshakeWith(Psk.SENTINEL, PskCategory.LONG_TERM, idOf = ByteArray(32) { 1 }) }
        val (_, s) = connect(server)
        assertThat(s.synced).isTrue()
        assertThat(ended).isNull()
    }

    @Test
    fun `another server naming a long-term PSK fails the handshake`() {
        records = listOf(PairingRecord("someone-else", ByteArray(32) { 3 }))
        val server = Server()
        server.greet = { it.handshakeWith(records.single().psk, PskCategory.LONG_TERM) }
        connect(server)
        assertThat(ended).isEqualTo(SessionEnd.BROKEN)
        assertThat(server.closed).isTrue()
    }

    @Test
    fun `a paired session may not pair again`() {
        records = listOf(PairingRecord(serverId, ByteArray(32) { 5 }))
        val server = Server()
        server.greet = { it.handshakeWith(records.single().psk, PskCategory.LONG_TERM) }
        server.onMessage = { m -> if (m.type == "client/hello") server.activate(listOf("pairing"), emptyList(), pairing = "pairing_psk") }
        val s = session(server)
        s.start()
        pump(server, s)
        assertThat(server.fromClient.last().type).isEqualTo("client/goodbye")
        assertThat(server.fromClient.last().string("reason")).isEqualTo("unauthorized")
        assertThat(ended).isEqualTo(SessionEnd.UNAUTHORIZED)
    }

    @Test
    fun `an older server and a newer one are told apart`() {
        val cases = listOf<Pair<(Server) -> Unit, SessionEnd>>(
            // Music Assistant 2.9: closes at once.
            { srv: Server -> srv.closeNow = true } to SessionEnd.OLD_SERVER,
            // An even older answer.
            { srv: Server -> srv.toClient += Message.of("server/hello", buildJsonObject { put("name", "old") }).encode() } to SessionEnd.OLD_SERVER,
            // Music Assistant 2.10.0 to 2.10.6: the first handshake message names no category.
            { srv: Server -> srv.handshakeWith(Psk.SENTINEL, PskCategory.SENTINEL, withCategory = false) } to SessionEnd.OLD_SERVER,
            { srv: Server -> srv.toClient += Message.of("server/error", buildJsonObject { put("reason", "unsupported_version") }).encode() } to SessionEnd.NEW_SERVER,
        )
        for ((greet, expected) in cases) {
            ended = null
            val server = Server()
            server.greet = greet
            val s = session(server)
            s.start()
            pump(server, s)
            if (server.closeNow) s.onClosed(byServer = true)
            assertThat(ended).isEqualTo(expected)
        }
        // A connection lost on the way, before the server could answer, says nothing of its version.
        ended = null
        val server = Server()
        server.greet = { srv -> srv.closeNow = true }
        val s = session(server)
        s.start()
        pump(server, s)
        s.onClosed(byServer = false)
        assertThat(ended).isEqualTo(SessionEnd.LOST)
    }

    @Test
    fun `text after the handshake or a short audio message breaks the session`() {
        val (server, s) = connect()
        server.toClient += Message.of("server/time").encode()
        pump(server, s)
        assertThat(ended).isEqualTo(SessionEnd.BROKEN)

        ended = null
        val (server2, s2) = connect()
        server2.send(Message.of("stream/start", buildJsonObject { put("player", PcmFormat.json()) }))
        server2.sendRaw(byteArrayOf(4, 0, 0, 0))
        pump(server2, s2)
        assertThat(ended).isEqualTo(SessionEnd.BROKEN)
    }

    @Test
    fun `a goodbye says why`() {
        val (server, s) = connect()
        s.goodbye("shutdown")
        assertThat(server.fromClient.last().string("reason")).isEqualTo("shutdown")
        assertThat(server.closed).isTrue()
        assertThat(ended).isEqualTo(SessionEnd.GOODBYE)
    }

    private fun long(v: Long) = ByteArray(8) { (v shr (56 - 8 * it)).toByte() }
    private fun int(v: Int) = ByteArray(4) { (v shr (24 - 8 * it)).toByte() }

    private companion object {
        const val OFFSET = 7_000_000_000L
    }

    /** The [expected] items come in this order, with any others between them. */
    private fun Assert<List<String>>.containsInOrder(vararg expected: String) = given { actual ->
        var next = 0
        for (item in actual) if (next < expected.size && item == expected[next]) next++
        if (next < expected.size) expected("to contain in order:${show(expected.toList())} but was:${show(actual)}")
    }
}
