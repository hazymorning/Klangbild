package io.github.hazymorning.klangbild.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * The song that plays, told again with only a new link to its picture, changes nothing on screen:
 * not a frame differs, on the single stage and in Cover Flow.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class NewLinkTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)
    private val link = "http://ha.local:8123/api/media_player_proxy/media_player.wz?token=2&cache=c1"

    @Test
    fun theStageStaysAsItIs() {
        val track = Fixtures.track(SyntheticCover.TERRACOTTA)
        val scene = Fixtures.scene(track, mdpi, Appearance.DARK)
        var stage by mutableStateOf(StageData(track, scene))
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.Theater(stage, PlayerStatus.PLAYING, group = "Wohnzimmer") }
        unchanged { stage = StageData(track.copy(artworkFallbackUrl = link), scene) }
    }

    @Test
    fun coverFlowStaysAsItIs() {
        val covers = listOf(SyntheticCover.TERRACOTTA, SyntheticCover.NOCTURNE, SyntheticCover.MOSS)
        val scenes = covers.mapIndexed { i, c -> i.toLong() to Fixtures.scene(Fixtures.track(c), mdpi, flow = true) }.toMap()
        val ids = scenes.keys.toList()
        var strip by mutableStateOf(Fixtures.flow(scenes, ids, ids[1]))
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.FlowTheaterOf(strip) }
        unchanged {
            val middle = strip.middle
            strip = strip.copy(cards = strip.cards.map { if (it === middle) it.copy(track = it.track.copy(artworkFallbackUrl = link)) else it })
        }
    }

    private fun unchanged(change: () -> Unit) {
        compose.mainClock.advanceTimeBy(3_000)
        val steady = capture()
        change()
        var changed = 0
        repeat(70) {
            compose.mainClock.advanceTimeBy(16)
            if (!capture().sameAs(steady)) changed++
        }
        assertThat(changed).isEqualTo(0)
    }

    private fun capture(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
}
