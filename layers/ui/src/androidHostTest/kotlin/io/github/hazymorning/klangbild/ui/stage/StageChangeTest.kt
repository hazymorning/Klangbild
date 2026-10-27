package io.github.hazymorning.klangbild.ui.stage

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.hazymorning.klangbild.app.StageData
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

/** Which change the single cover makes when the next track has the same cover. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class StageChangeTest {
    private val screen = ScreenSpec(960, 540, 1f)
    private val first = Fixtures.track(SyntheticCover.SALT_WATER)
    private val next = first.copy(title = "Ebbe", key = "Ebbe|" + first.artists.joinToString() + "|" + first.album)

    /** [track] with the cover of [first] on the [appearance] background. */
    private fun stage(track: TrackInfo, appearance: Appearance = Appearance.DARK) = StageData(
        track,
        SceneBuilder.build(track.key, SyntheticCovers.render(SyntheticCover.SALT_WATER), track.artFoil, track.artView, screen, appearance),
    )

    @Test
    fun `only the text changes`() {
        assertThat(changeOf(stage(first), stage(next))).isEqualTo(Change.TEXT)
    }

    @Test
    fun `on the other background the cover cross-fades too`() {
        assertThat(changeOf(stage(first), stage(next, Appearance.LIGHT))).isEqualTo(Change.FULL)
    }
}
