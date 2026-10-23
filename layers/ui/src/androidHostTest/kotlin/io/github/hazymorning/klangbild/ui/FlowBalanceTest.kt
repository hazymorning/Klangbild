package io.github.hazymorning.klangbild.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * Cover Flow with only some of its cards: the first song (nothing played yet), the last one
 * (nothing to come), a player without a queue (only what played), a single cover. Whatever is
 * there stands in the middle of the screen with the text, as much room left of it as right.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class FlowBalanceTest {
    private val strip: List<TrackInfo> = listOf(
        Fixtures.track(SyntheticCover.TERRACOTTA),
        Fixtures.track(SyntheticCover.MOSS),
        Fixtures.track(SyntheticCover.NIGHT_DRIVE),
        Fixtures.track(SyntheticCover.WHITE_SPACE),
        Fixtures.track(SyntheticCover.BAUHAUS),
    )

    /** The middle card with [left] cards played before it and [right] to come. */
    private fun shot(name: String, left: Int, right: Int, appearance: Appearance = Appearance.DARK, coverField: Boolean = false) =
        captureRoboImage("build/screenshots/flow_balance/$name.png") {
            Fixtures.Still {
                val tracks = strip.subList(2 - left, 3 + right)
                val scenes = Fixtures.flowScenes(tracks, appearance, coverField = coverField)
                FlowStage(
                    Fixtures.model(tracks[left], next = tracks.getOrNull(left + 1), group = "Wohnzimmer", scene = scenes[left]),
                    Fixtures.restingCards(scenes, left),
                )
            }
        }

    @Test fun alone() = shot("alone", 0, 0)
    @Test fun firstSong() = shot("first", 0, 2)
    @Test fun lastSong() = shot("last", 2, 0)
    @Test fun oneEach() = shot("one_each", 1, 1)
    @Test fun oneBefore() = shot("one_before", 1, 0)
    @Test fun oneToCome() = shot("one_to_come", 0, 1)
    @Test fun twoAndOne() = shot("two_and_one", 2, 1)
    @Test fun full() = shot("full", 2, 2)
    @Test fun lastOnPaper() = shot("last_paper", 2, 0, Appearance.LIGHT)
    @Test fun firstOnPaper() = shot("first_paper", 0, 2, Appearance.LIGHT)
    @Test fun lastOnCoverColors() = shot("last_field", 2, 0, coverField = true)
    @Test fun aloneOnCoverColors() = shot("alone_field", 0, 0, coverField = true)
}
