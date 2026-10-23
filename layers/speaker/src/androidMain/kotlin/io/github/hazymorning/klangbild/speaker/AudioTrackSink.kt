package io.github.hazymorning.klangbild.speaker

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The TV's audio output: an AudioTrack of 16-bit stereo PCM at 48 kHz, written from the audio
 * thread. Its timestamps say when a frame left the device at its HDMI port; what the TV or a
 * sound bar adds after that is the output delay's to cover.
 *
 * The track is made for music on the standard path (no low-latency track: those refuse rate
 * changes and gain nothing here) with room for [BUFFER_MS] of audio, which is also how far ahead
 * the audio is written. When it gives no timestamps at all for a while, the frames it has
 * consumed stand in for them; that leaves out what lies beyond the mixer, so it is a last resort.
 */
class AudioTrackSink(
    private val clock: MonoClock,
    /** The output went elsewhere (another device, the same one again): its timing starts anew. */
    private val routed: () -> Unit,
) : AudioSink {
    override val rate = 48_000
    override val bufferFrames: Int = rate * BUFFER_MS / 1_000

    private var track: AudioTrack? = null
    private val stamp = AudioTimestamp()
    private var lastStampUs = 0L
    private var underrunBase = 0
    private val routing = AudioRouting.OnRoutingChangedListener { routed() }

    override fun open(): Boolean {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .setBufferSizeInBytes(maxOf(min, bufferFrames * FRAME_BYTES))
                .build()
        } catch (_: UnsupportedOperationException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            return false
        }
        // It starts playing with the first frames instead of waiting for a full buffer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) t.setStartThresholdInFrames(1)
        t.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
        try {
            t.play()
        } catch (_: IllegalStateException) {
            t.release()
            return false
        }
        track = t
        underrunBase = t.underrunCount
        lastStampUs = clock.nowUs()
        return true
    }

    override fun write(pcm: ShortArray, frames: Int): Int {
        val t = track ?: return -1
        val n = t.write(pcm, 0, frames * 2, AudioTrack.WRITE_BLOCKING)
        return if (n < 0) n else n / 2
    }

    override fun timestamp(out: SinkTimestamp): Boolean {
        val t = track ?: return false
        val now = clock.nowUs()
        if (t.getTimestamp(stamp)) {
            lastStampUs = now
            // The count keeps only 32 bits; the playback compares counts in 32 bits too.
            out.frame = stamp.framePosition and 0xffffffffL
            out.timeUs = stamp.nanoTime / 1_000
            return true
        }
        if (now - lastStampUs < NO_STAMPS_US) return false
        // No timestamps for a while: what the track has consumed, now.
        out.frame = t.playbackHeadPosition.toLong() and 0xffffffffL
        out.timeUs = now
        return true
    }

    override fun underruns(): Int = (track?.underrunCount ?: underrunBase) - underrunBase

    override fun flush() {
        val t = track ?: return
        try {
            t.pause()
            t.flush()
            t.play()
        } catch (_: IllegalStateException) {
            // A track that cannot be paused or played any more fails its next write, which ends it.
        }
        underrunBase = t.underrunCount
        lastStampUs = clock.nowUs()
    }

    override fun close() {
        val t = track ?: return
        track = null
        t.removeOnRoutingChangedListener(routing)
        try {
            t.pause()
            t.flush()
            t.stop()
        } catch (_: IllegalStateException) {
            // Already stopped.
        }
        t.release()
    }

    companion object {
        /** How much audio the output holds, and so how far ahead it is written. */
        const val BUFFER_MS = 200

        /** Without any timestamp for this long, the frames consumed stand in for them. */
        const val NO_STAMPS_US = 500_000L

        private const val FRAME_BYTES = 4
    }
}
