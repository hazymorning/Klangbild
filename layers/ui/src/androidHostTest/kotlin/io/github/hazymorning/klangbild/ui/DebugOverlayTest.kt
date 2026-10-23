package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.hazymorning.klangbild.sound.SoundFeed
import io.github.hazymorning.klangbild.sound.SoundFrame
import io.github.hazymorning.klangbild.sound.SoundTiming
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/** The debug overlay's sound meter reads its feed only while it shows. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class DebugOverlayTest {
    @get:org.junit.Rule
    val compose = composeRule()

    /** Music that is loud in the bass, a downbeat 50 ms ago, timed exactly; it counts its readers. */
    class Steady : SoundFeed {
        var readers = 0

        override fun read(nowUs: Long, out: SoundFrame): Boolean {
            out.timeUs = nowUs - 5_000
            out.loudness = 0.7f
            floatArrayOf(0.9f, 0.6f, 0.45f, 0.3f).copyInto(out.bands)
            out.onset = 0.2f
            out.beatUs = nowUs - 50_000
            out.downbeat = true
            out.timing = SoundTiming.EXACT
            return true
        }

        override fun reading(on: Boolean) {
            readers += if (on) 1 else -1
        }
    }

    @Test
    fun `the feed is read while the overlay shows, and let go with it`() {
        val feed = Steady()
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) DebugOverlay(FrameStats.stats, feed) }
        compose.waitForIdle()
        assertThat(feed.readers).isEqualTo(1)
        shown = false
        compose.waitForIdle()
        assertThat(feed.readers).isEqualTo(0)
    }
}
