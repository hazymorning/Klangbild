package io.github.hazymorning.klangbild.speaker

import android.os.Process
import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val http: OkHttpClient,
    private val store: SpeakerStore,
    /** Music Assistant's Sendspin address (`ws://host:8927/sendspin`). */
    private val url: String,
    private val identity: SpeakerIdentity,
    /** The audio output, told how to report a change of route. */
    private val sinkFactory: (routed: () -> Unit) -> AudioSink,
    private val nowUs: () -> Long = { System.nanoTime() / 1_000 },
    /** How long the device has slept in all, in microseconds (the monotonic clock stands still meanwhile). */
    private val sleptUs: () -> Long = ::sleptSoFarUs,
    /** Readies the audio thread (a high priority). */
    private val audioThreadSetup: () -> Unit = { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) },
    private val random: SecureRandom = SecureRandom(),
) {
    private val _status = MutableStateFlow(SpeakerStatus(link = SpeakerLink.CONNECTING))
    val status: StateFlow<SpeakerStatus> = _status.asStateFlow()

    private val queue = ChunkQueue(QUEUE_SECONDS * PcmFormat.RATE)
    val playback: Playback = Playback(sinkFactory { playbackResync() }, queue, nowUs)
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

    @Volatile private var volume = SpeakerStore.DEFAULT_VOLUME
    @Volatile private var muted = false
    @Volatile private var outputDelayMs = 0
    @Volatile private var minBufferMs = ArrivalDelays.FLOOR_MS

    /** The audio thread and what it waits for. */
    private val lock = ReentrantLock()
    private val streamCame = lock.newCondition()
    @Volatile private var running = false
    @Volatile private var streaming = false
    @Volatile private var keepOpenUntilUs = 0L
    private var thread: Thread? = null

    /** The token a person enters into Music Assistant to pair it with this speaker (null until the speaker has its key). */
    val pairingToken: String?
        get() {
            val m = memory ?: return null
            val key = m.identity ?: return null
            val psk = m.pairingPsk ?: return null
            return PairingToken.of(key.publicKey, psk)
        }

    /** Loads what the speaker keeps and connects; without its key it stays away and says so. */
    fun start() {
        if (running) return
        running = true
        jobs = listOf(scope.launch { connect() }, scope.launch { watch() })
        thread = Thread({ audioLoop() }, "Klangbild speaker").also { it.start() }
    }

    /** Disconnects with `client/goodbye` ([reason]) and lets the output go. */
    fun stop(reason: String) {
        if (!running) return
        running = false
        connection?.stop(reason)
        connection = null
        jobs.forEach { it.cancel() }
        lock.withLock {
            streaming = false
            streamCame.signalAll()
        }
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
        val m = store.load()
        memory = m
        val key = m.identity
        if (key == null || m.pairingPsk == null) {
            Log.w(TAG, "The speaker's key cannot be opened at the moment; it stays away")
            _status.value = SpeakerStatus(link = SpeakerLink.NO_KEY)
            return
        }
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
        val c = SpeakerConnection(SpeakerConnection.client(http), scope, url, { transport, ended -> newSession(transport, key, m.pairingPsk, hello, ended) })
        connection = c
        Log.i(TAG, "Speaker connecting to Music Assistant")
        c.start()
        c.link.collect { link ->
            if (link == SpeakerLink.CONNECTED && _status.value.link != SpeakerLink.CONNECTED) Log.i(TAG, "Speaker connected to Music Assistant")
            _status.update { it.copy(link = link) }
        }
    }

    private fun newSession(transport: Transport, key: KeyPair, pairingPsk: ByteArray, hello: Hello, ended: (SessionEnd, String) -> Unit): Session {
        val s = Session(transport, key, pairingPsk, { records }, hello, Listener(ended), nowUs, random)
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
                Log.i(TAG, "The device slept: the speaker's clock starts again")
                session?.clockReset()
                playback.resync()
            }
            slept = now
            val buffer = delays.minBufferMs(nowUs())
            if (buffer != minBufferMs) {
                minBufferMs = buffer
                session?.stateChanged()
            }
        }
    }

    private fun playbackResync(): Unit = playback.resync()

    /** The audio thread: plays while a stream runs and a while after it, waits otherwise. */
    private fun audioLoop() {
        audioThreadSetup()
        while (running) {
            val wanted = streaming || nowUs() < keepOpenUntilUs
            if (!wanted) {
                playback.close()
                lock.withLock {
                    while (running && !streaming) streamCame.await()
                }
                continue
            }
            if (!playback.open()) {
                // No output (none on this device, or it is busy): tried again in a moment.
                Thread.sleep(OPEN_RETRY_MS)
                continue
            }
            if (!playback.step()) playback.close()
        }
        playback.close()
    }

    /** What one session tells the speaker. */
    private inner class Listener(private val onEnded: (SessionEnd, String) -> Unit) : Session.Listener {
        override fun playerState() = PlayerState(volume, muted, outputDelayMs, REQUIRED_LEAD_MS, minBufferMs)

        override fun clock(map: ClockMap?) {
            playback.clock = map
        }

        override fun playerActive(active: Boolean) {
            if (!active && streaming) streamEnd()
        }

        override fun streamStart() {
            lock.withLock {
                streaming = true
                streamCame.signalAll()
            }
            _status.update { it.copy(playing = true) }
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
            keepOpenUntilUs = nowUs() + KEEP_OPEN_US
            streaming = false
            _status.update { it.copy(playing = false) }
        }

        override fun volume(volume: Int) {
            this@Speaker.volume = volume
            playback.setVolume(volume, muted)
            scope.launch { store.setVolume(volume, muted) }
        }

        override fun mute(muted: Boolean) {
            this@Speaker.muted = muted
            playback.setVolume(volume, muted)
            scope.launch { store.setVolume(volume, muted) }
        }

        override fun outputDelay(ms: Int) {
            outputDelayMs = ms
            playback.outputDelayUs = ms * 1_000L
            scope.launch { store.setOutputDelay(ms) }
        }

        override fun group(playing: Boolean, groupId: String, name: String) = Unit

        override fun beat(timestampUs: Long, downbeat: Boolean) {
            // Stamped, like the audio, with when it is heard.
            playback.clock?.let { sound.beat(it.clientTime(timestampUs), downbeat) }
        }

        override fun beatsCleared() = sound.beatsCleared()

        override fun paired(record: PairingRecord) {
            records = listOf(record) + records.filter { it.serverId != record.serverId }
            scope.launch { store.remember(record) }
            Log.i(TAG, "The speaker is paired with Music Assistant")
        }

        override fun used(serverId: String) {
            scope.launch { store.used(serverId) }
        }

        override fun unpaired(serverId: String) {
            records = records.filter { it.serverId != serverId }
            scope.launch { store.forget(serverId) }
        }

        override fun ended(end: SessionEnd, detail: String) {
            if (streaming) streamEnd()
            Log.i(TAG, "Speaker connection ended: $end ($detail)")
            onEnded(end, detail)
        }
    }

    companion object {
        private const val TAG = "Klangbild"

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

        /** A gap this long between the monotonic clock and the one that counts sleep: the device slept. */
        const val SLEEP_US = 5_000L
        const val OPEN_RETRY_MS = 1_000L

        /** Time spent asleep so far: the boot clock runs through sleep, the monotonic one does not. */
        fun sleptSoFarUs(): Long {
            val before = System.nanoTime()
            val boot = SystemClock.elapsedRealtimeNanos()
            val after = System.nanoTime()
            return (boot - (before + after) / 2) / 1_000
        }
    }
}
