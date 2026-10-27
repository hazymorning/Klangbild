package io.github.hazymorning.klangbild.speaker

import dev.whyoleg.cryptography.random.CryptographyRandom
import io.github.hazymorning.klangbild.data.Backoff
import io.github.hazymorning.klangbild.data.Log
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.domain.SpeakerStatus
import io.github.hazymorning.klangbild.sendspin.B64
import io.github.hazymorning.klangbild.sendspin.ClockMap
import io.github.hazymorning.klangbild.sendspin.Hello
import io.github.hazymorning.klangbild.sendspin.KeyPair
import io.github.hazymorning.klangbild.sendspin.PairingRecord
import io.github.hazymorning.klangbild.sendspin.PairingToken
import io.github.hazymorning.klangbild.sendspin.PcmFormat
import io.github.hazymorning.klangbild.sendspin.PlayerState
import io.github.hazymorning.klangbild.sendspin.Session
import io.github.hazymorning.klangbild.sendspin.SessionEnd
import io.github.hazymorning.klangbild.sendspin.Transport
import io.github.hazymorning.klangbild.sound.PcmSound
import io.ktor.client.HttpClient
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.random.Random

/** How this speaker introduces itself: the TV's name, and what it is. */
class SpeakerIdentity(val name: String, val productName: String?, val manufacturer: String?, val softwareVersion: String?)

/**
 * This TV as a speaker in Music Assistant: its connection ([SpeakerConnection]), what it keeps
 * ([SpeakerStore]) and its playback ([Playback]) on an audio thread of its own, which runs while
 * a stream plays and for a while after it (Music Assistant ends the stream and starts a new one
 * when a song is skipped, and the output stays warm for that).
 *
 * Everything the session reports comes on the connection's thread and is handed on at once:
 * audio into the queue, the clock to the playback, the beats to [sound], the rest to [status]
 * and the store.
 */
class Speaker(
    private val scope: CoroutineScope,
    /** Pings the server now and then ([SpeakerConnection.PING_MS]). */
    private val http: HttpClient,
    private val store: SpeakerStore,
    /** Music Assistant's Sendspin address (`ws://host:8927/sendspin`). */
    private val url: String,
    private val identity: SpeakerIdentity,
    /** The audio output, told how to report a change of route. */
    private val sinkFactory: (routed: () -> Unit) -> AudioSink,
    /** The thread the audio plays on. */
    private val audioThread: AudioThread,
    /** How long the device has slept in all, in microseconds (the monotonic clock stands still meanwhile). */
    private val sleptUs: () -> Long,
    private val log: Log,
    private val monotonic: MonoClock = MonoClock.SYSTEM,
    private val random: Random = CryptographyRandom,
    /** How long to wait before asking the keystore again for a key it could not open. */
    private val keyRetry: () -> Backoff = { Backoff(KEY_FIRST_RETRY_MS, KEY_LAST_RETRY_MS) },
) {
    private val _status = MutableStateFlow(SpeakerStatus(link = SpeakerLink.CONNECTING))
    val status: StateFlow<SpeakerStatus> = _status.asStateFlow()

    private val queue = ChunkQueue(QUEUE_SECONDS * PcmFormat.RATE)
    private val playback: Playback = Playback(sinkFactory { playbackResync() }, queue, monotonic)
    private val delays = ArrivalDelays()

    /** What this TV plays, as a sound feed: measured on its way to the output, while someone reads it. */
    val sound = PcmSound(PcmFormat.RATE)

    init {
        playback.tap = Playback.PcmTap { pcm, frames, heardUs -> sound.played(pcm, frames, heardUs) }
    }

    @Volatile private var memory: SpeakerMemory? = null
    @Volatile private var records: List<PairingRecord> = emptyList()
    @Volatile private var connection: SpeakerConnection? = null
    @Volatile private var session: Session? = null
    private var jobs = emptyList<Job>()

    /** What a player keeps across restarts, as last told: written one after another, the latest last. */
    private data class Kept(val volume: Int, val muted: Boolean, val outputDelayMs: Int)

    private val kept = MutableStateFlow<Kept?>(null)

    /** Starting and stopping, and the connection they make and end, change together. */
    private val lifecycle = SynchronizedObject()

    /** Music Assistant says the group plays (`group/update`); a stream that starts says so too. */
    @Volatile private var groupPlaying = true

    @Volatile private var volume = SpeakerStore.DEFAULT_VOLUME
    @Volatile private var muted = false
    @Volatile private var outputDelayMs = 0
    @Volatile private var minBufferMs = ArrivalDelays.FLOOR_MS

    /** The audio thread and what wakes it: a stream that came, or the end. */
    private val streamCame = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var running = false
    @Volatile private var streaming = false
    @Volatile private var keepOpenUntilUs = 0L

    /** The token a person enters into Music Assistant to pair it with this speaker (null until the speaker has its key). */
    val pairingToken: String?
        get() {
            val m = memory ?: return null
            val key = m.identity ?: return null
            val psk = m.pairingPsk ?: return null
            return PairingToken.of(key.publicKey, psk)
        }

    /** Loads what the speaker keeps and connects; until its key opens it stays away and says so. */
    fun start() {
        synchronized(lifecycle) {
            if (running) return
            running = true
            jobs = listOf(scope.launch { connect() }, scope.launch { watch() }, scope.launch { keep() })
        }
        audioThread.start { audioLoop() }
    }

    /** Disconnects with `client/goodbye` ([reason]) and lets the output go. */
    fun stop(reason: String) {
        synchronized(lifecycle) {
            if (!running) return
            running = false
            connection?.stop(reason)
            connection = null
            jobs.forEach { it.cancel() }
        }
        streaming = false
        streamCame.trySend(Unit)
        _status.update { SpeakerStatus(link = SpeakerLink.OFF, clientId = it.clientId) }
    }

    /** The network came back, or the TV woke up: try at once. */
    fun retryNow() {
        connection?.retryNow()
    }

    /**
     * Another app takes the TV's sound: for a moment ([lasting] false) the speaker only goes
     * quiet, in time underneath; for good it also leaves its group, so Music Assistant stops
     * playing here. It plays again once the sound is back, or a new stream starts.
     */
    fun soundTaken(taken: Boolean, lasting: Boolean = false) {
        playback.silence(taken)
        if (taken && lasting) session?.leave()
    }

    /** The output's timing can no longer be trusted (an HDMI device came or went): put in place anew. */
    fun outputChanged() = playback.resync()

    private suspend fun connect() {
        val (m, key, psk) = keyed()
        if (!running) return
        records = m.records
        volume = m.volume
        muted = m.muted
        outputDelayMs = m.outputDelayMs
        playback.setVolume(volume, muted)
        playback.outputDelayUs = outputDelayMs * 1_000L
        val clientId = B64.encode(key.publicKey)
        _status.update { it.copy(clientId = clientId) }
        val hello = Hello(
            name = identity.name,
            productName = identity.productName,
            manufacturer = identity.manufacturer,
            softwareVersion = identity.softwareVersion,
            bufferCapacity = BUFFER_SECONDS * (PcmFormat.RATE / CHUNKS_PER_SECOND * PcmFormat.FRAME_BYTES + 13) * CHUNKS_PER_SECOND,
            beats = true,
            beatBufferCapacity = BEAT_BUFFER_BYTES,
        )
        val c = SpeakerConnection(http, scope, url, { transport, ended -> newSession(transport, key, psk, hello, ended) })
        // A stop that came meanwhile leaves no connection behind.
        synchronized(lifecycle) {
            if (!running) return
            connection = c
            c.start()
        }
        log.info("Speaker connecting to Music Assistant")
        c.link.collect { link ->
            if (link == SpeakerLink.CONNECTED && _status.value.link != SpeakerLink.CONNECTED) log.info("Speaker connected to Music Assistant")
            _status.update { it.copy(link = link) }
        }
    }

    /**
     * What the speaker keeps, with its key and pairing PSK, once the keystore opens them: asked
     * again, less and less often, while it refuses. The key is never replaced, since a new one
     * would be a new speaker in Music Assistant.
     */
    private suspend fun keyed(): Triple<SpeakerMemory, KeyPair, ByteArray> {
        val wait = keyRetry()
        while (true) {
            val m = store.load()
            memory = m
            val key = m.identity
            val psk = m.pairingPsk
            if (key != null && psk != null) return Triple(m, key, psk)
            if (_status.value.link != SpeakerLink.NO_KEY) {
                log.warn("The speaker's key cannot be opened at the moment; it stays away and tries again")
                _status.value = SpeakerStatus(link = SpeakerLink.NO_KEY)
            }
            delay(wait.nextDelayMs())
        }
    }

    private fun newSession(transport: Transport, key: KeyPair, pairingPsk: ByteArray, hello: Hello, ended: (SessionEnd, String) -> Unit): Session {
        val s = Session(transport, key, pairingPsk, { records }, hello, Listener(ended), monotonic::nowUs, random)
        session = s
        return s
    }

    /** Now and then: a sleep the device woke from, a new buffer to ask for. */
    private suspend fun watch() {
        var slept = sleptUs()
        while (scope.isActive) {
            delay(WATCH_MS)
            val now = sleptUs()
            if (now - slept > SLEEP_US) {
                log.info("The device slept: the speaker's clock starts again")
                session?.clockReset()
                playback.resync()
            }
            slept = now
            val buffer = delays.minBufferMs(monotonic.nowUs())
            if (buffer != minBufferMs) {
                minBufferMs = buffer
                session?.stateChanged()
            }
        }
    }

    private fun playbackResync(): Unit = playback.resync()

    /** Writes what a player keeps as it changes, one write after another, so the latest is the one kept. */
    private suspend fun keep() {
        kept.collect { k ->
            if (k == null) return@collect
            store.setVolume(k.volume, k.muted)
            store.setOutputDelay(k.outputDelayMs)
        }
    }

    private fun remember() {
        kept.value = Kept(volume, muted, outputDelayMs)
    }

    /** Whether music plays here: a stream runs and its group plays. */
    private fun playingNow() {
        val playing = streaming && groupPlaying
        _status.update { if (it.playing == playing) it else it.copy(playing = playing) }
    }

    /** The audio thread: plays while a stream runs and a while after it, waits otherwise. */
    private suspend fun audioLoop() {
        try {
            while (running) {
                val wanted = streaming || monotonic.nowUs() < keepOpenUntilUs
                if (!wanted) {
                    playback.close()
                    while (running && !streaming) streamCame.receive()
                    continue
                }
                if (!playback.open() || !playback.step()) {
                    // No output (none on this device, or it is busy), or one that failed: another in a moment.
                    playback.close()
                    delay(OPEN_RETRY_MS)
                }
            }
        } finally {
            playback.close()
        }
    }

    /** What one session tells the speaker. */
    private inner class Listener(private val onEnded: (SessionEnd, String) -> Unit) : Session.Listener {
        override fun playerState() = PlayerState(volume, muted, outputDelayMs, REQUIRED_LEAD_MS, minBufferMs)

        override fun clock(map: ClockMap?) {
            playback.clock = map
        }

        override fun streamStart() {
            streaming = true
            streamCame.trySend(Unit)
            groupPlaying = true
            playingNow()
        }

        override fun audio(timestampUs: Long, sendAheadUs: Long, bytes: ByteArray, offset: Int, length: Int, arrivalUs: Long) {
            val c = playback.clock
            // A send_ahead of 0 or the largest value measured nothing.
            if (c != null && sendAheadUs != 0L && sendAheadUs != 0xffffffffL) delays.add(c.clientTime(timestampUs - sendAheadUs), arrivalUs)
            queue.add(timestampUs, bytes, offset, length / PcmFormat.FRAME_BYTES)
        }

        override fun clear() {
            playback.clear()
            sound.cleared()
        }

        override fun streamEnd() {
            playback.clear()
            sound.cleared()
            keepOpenUntilUs = monotonic.nowUs() + KEEP_OPEN_US
            streaming = false
            playingNow()
        }

        override fun volume(volume: Int) {
            this@Speaker.volume = volume
            playback.setVolume(volume, muted)
            remember()
        }

        override fun mute(muted: Boolean) {
            this@Speaker.muted = muted
            playback.setVolume(volume, muted)
            remember()
        }

        override fun outputDelay(ms: Int) {
            outputDelayMs = ms
            playback.outputDelayUs = ms * 1_000L
            remember()
        }

        override fun group(playing: Boolean, groupId: String, name: String) {
            groupPlaying = playing
            playingNow()
        }

        override fun beat(timestampUs: Long, downbeat: Boolean) {
            // Stamped, like the audio, with when it is heard.
            playback.clock?.let { sound.beat(it.clientTime(timestampUs), downbeat) }
        }

        override fun beatsCleared() = sound.beatsCleared()

        override fun paired(record: PairingRecord) {
            records = listOf(record) + records.filter { it.serverId != record.serverId }
            scope.launch { store.remember(record) }
            log.info("The speaker is paired with Music Assistant")
        }

        override fun used(serverId: String) {
            scope.launch { store.used(serverId) }
        }

        override fun unpaired(serverId: String) {
            records = records.filter { it.serverId != serverId }
            scope.launch { store.forget(serverId) }
        }

        override fun ended(end: SessionEnd, detail: String) {
            log.info("Speaker connection ended: $end ($detail)")
            onEnded(end, detail)
        }
    }

    companion object {
        /** Music Assistant may send this far ahead (player@v1's `buffer_capacity`). */
        const val BUFFER_SECONDS = 10

        /** The queue holds a little more, for a lowered output delay. */
        const val QUEUE_SECONDS = 12

        /** Room for the beats Music Assistant sends ahead (10 bytes each, up to 3 s ahead). */
        const val BEAT_BUFFER_BYTES = 4_096

        /** Music Assistant sends 25 ms chunks: the capacity counts their headers too. */
        const val CHUNKS_PER_SECOND = 40

        /** What a stream needs from its start to its first sound: the output opening and its timestamps settling. */
        const val REQUIRED_LEAD_MS = 1_000

        /** After a stream ends, the output stays open this long for the next one. */
        const val KEEP_OPEN_US = 30_000_000L

        const val WATCH_MS = 2_000L

        /** A key the keystore refused is asked for again after this long at first, then less and less often. */
        const val KEY_FIRST_RETRY_MS = 5_000L
        const val KEY_LAST_RETRY_MS = 5 * 60_000L

        /**
         * A gap this long between the monotonic clock and the one that counts sleep: the device
         * slept. Far above what reading the two clocks can be off by, far below a real sleep.
         */
        const val SLEEP_US = 100_000L
        const val OPEN_RETRY_MS = 1_000L
    }
}
