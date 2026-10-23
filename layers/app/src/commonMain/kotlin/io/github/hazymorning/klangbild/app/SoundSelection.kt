package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.data.ma.MilkdropSound
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.SpeakerStatus
import io.github.hazymorning.klangbild.sound.SoundFeed
import io.github.hazymorning.klangbild.sound.SoundFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile

/**
 * The one sound feed for what shows: this TV's own playback while the music shown plays here
 * (exact), Music Assistant's MilkDrop feed for its other speakers (close, or nothing without the
 * plugin), nothing for any other player. Only the feed chosen is read, and only while someone
 * reads this one.
 */
internal class SoundSelection(
    /** This TV's own playback, while it plays as a speaker. */
    private val own: () -> SoundFeed?,
) : SoundFeed {
    @Volatile private var current: SoundFeed = SoundFeed.None
    private var readers = 0
    private var milkdrop: MilkdropSound? = null
    private val lock = SynchronizedObject()

    override fun read(nowUs: Long, out: SoundFrame): Boolean = current.read(nowUs, out)

    override fun reading(on: Boolean): Unit = synchronized(lock) {
        val before = readers
        readers = (readers + if (on) 1 else -1).coerceAtLeast(0)
        if (before == 0 && readers > 0) current.reading(true)
        if (before > 0 && readers == 0) current.reading(false)
    }

    /** Music Assistant's MilkDrop feed for the address set up, or none without Music Assistant. */
    fun useMilkdrop(feed: MilkdropSound?): Unit = synchronized(lock) {
        if (feed === milkdrop) return
        val old = milkdrop
        milkdrop = feed
        if (current === old) choose(SoundFeed.None)
        old?.follow(null)
    }

    /** What shows and plays now decides the feed. */
    fun update(state: SourceState, speaker: SpeakerStatus): Unit = synchronized(lock) {
        val player = state.player
        val here = speaker.playing && state.speaker != null && state.speaker == state.ownSpeaker
        val queue = player?.activeQueue?.takeIf { player.isMusicAssistant }
        val m = milkdrop
        val next = when {
            here -> own() ?: SoundFeed.None
            m != null && queue != null -> m
            else -> SoundFeed.None
        }
        m?.follow(queue.takeIf { next === m })
        choose(next)
    }

    private fun choose(next: SoundFeed) {
        if (next === current) return
        if (readers > 0) {
            current.reading(false)
            next.reading(true)
        }
        current = next
    }
}
