package io.github.hazymorning.klangbild.speaker

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.runBlocking

/** A thread for the speaker's audio, readied by [setup] (Android's priority for audio). */
class AndroidAudioThread(private val setup: () -> Unit = { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }) : AudioThread {
    override fun start(loop: suspend () -> Unit) {
        Thread({
            setup()
            runBlocking { loop() }
        }, "Klangbild speaker").start()
    }
}

/** Android's clocks, as the speaker reads them. */
object AndroidClocks {
    /**
     * Time spent asleep so far: the boot clock runs through sleep, the monotonic one does not.
     * Of a few readings the one taken most quickly counts, so a thread held up between two
     * reads never looks like a sleep.
     */
    fun sleptSoFarUs(): Long {
        var best = Long.MAX_VALUE
        var slept = 0L
        repeat(CLOCK_READS) {
            val before = System.nanoTime()
            val boot = SystemClock.elapsedRealtimeNanos()
            val after = System.nanoTime()
            if (after - before < best) {
                best = after - before
                slept = (boot - (before + after) / 2) / 1_000
            }
        }
        return slept
    }

    private const val CLOCK_READS = 3
}
