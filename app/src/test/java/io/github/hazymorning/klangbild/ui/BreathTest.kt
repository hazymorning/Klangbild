package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.relativeLuminance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.render.StageGrid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** The wall's light breathes while the speaker buffers and glides back when it plays again: it never jumps. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class BreathTest {
    @get:Rule
    val compose = composeRule()

    private val screen = ScreenSpec(960, 540, 1f)

    @Test
    fun `the light glides back when buffering ends`() {
        val stage = Fixtures.stage(Fixtures.track(SyntheticCover.SALT_WATER), screen)
        var status by mutableStateOf(PlayerStatus.BUFFERING)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Theater(stage, status) }
        // The breath at its lowest.
        compose.mainClock.advanceTimeBy(1_600)
        val low = light()
        status = PlayerStatus.PLAYING
        compose.mainClock.advanceTimeBy(150)
        val soon = light()
        compose.mainClock.advanceTimeBy(1_000)
        val full = light()
        assertThat(soon).isGreaterThan(low)
        assertThat(soon).isLessThan(full)
    }

    /** The wall's light just beside the cover. */
    private fun light(): Float {
        val picture = compose.onRoot().captureToImage().asAndroidBitmap()
        val art = StageGrid(screen).artRect
        return relativeLuminance(picture.getPixel(((art.right + 6f) * screen.density).roundToInt(), (art.centerY * screen.density).roundToInt()))
    }
}
