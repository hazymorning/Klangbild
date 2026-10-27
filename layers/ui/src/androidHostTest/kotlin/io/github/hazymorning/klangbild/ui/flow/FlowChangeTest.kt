package io.github.hazymorning.klangbild.ui.flow

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.app.FlowData
import io.github.hazymorning.klangbild.app.FlowEntry
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.art.SyntheticCovers
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.SceneBuilder
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.ui.Fixtures
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/** Whether Cover Flow's strip takes a new look when the next track comes to the same card. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class FlowChangeTest {
    private val screen = ScreenSpec(960, 540, 1f)
    private val first = Fixtures.track(SyntheticCover.SALT_WATER)
    private val next = first.copy(title = "Ebbe", key = "Ebbe|" + first.artists.joinToString() + "|" + first.album)

    /** A strip of one card: [track] with the cover of [first] on the [appearance] background. */
    private fun strip(track: TrackInfo, appearance: Appearance = Appearance.DARK): FlowData {
        val scene = SceneBuilder.build(track.key, SyntheticCovers.render(SyntheticCover.SALT_WATER), track.artFoil, track.artView, screen, appearance, flow = true)
        return FlowData(listOf(FlowEntry(0L, track, scene)), 0)
    }

    @Test
    fun `only the text changes`() {
        val old = strip(first)
        val new = strip(next)
        assertThat(changeOf(old, new)).isEqualTo(FlowChange.TEXT)
        assertThat(takesNewLook(old, new, FlowChange.TEXT)).isFalse()
    }

    @Test
    fun `on the other background the cards cross-fade too`() {
        val old = strip(first)
        val new = strip(next, Appearance.LIGHT)
        assertThat(changeOf(old, new)).isEqualTo(FlowChange.TEXT)
        assertThat(takesNewLook(old, new, FlowChange.TEXT)).isTrue()
    }
}
