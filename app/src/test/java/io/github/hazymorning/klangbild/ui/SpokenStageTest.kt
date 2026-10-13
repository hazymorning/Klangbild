package io.github.hazymorning.klangbild.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.ui.flow.FlowStage
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.stage.StageModel
import io.github.hazymorning.klangbild.ui.stage.StageTexts
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.hazymorning.klangbild.Pictures
import org.junit.experimental.categories.Category

/**
 * Audiobooks and podcasts on the stage: the chapter in the album's place, the bar over the chapter,
 * speed and sleep timer in words.
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class SpokenStageTest {
    private fun shot(name: String, model: StageModel) =
        captureRoboImage("build/screenshots/spoken/$name.png") { Fixtures.Still { Stage(model) } }

    private val book = Catalog.book
    private val episode = Catalog.episode

    private fun spokenModel(track: TrackInfo, position: Long, lead: String?, appearance: Appearance = Appearance.DARK, view: StageView = StageView.SINGLE, next: TrackInfo? = null) =
        Fixtures.spokenModel(track, position, lead, appearance, view, next)

    private fun lead(pace: String?) = pace

    @Test fun audiobook() = shot("audiobook", spokenModel(book, 2_554_000L, lead(TimeText.speed(1.25f, Fixtures.texts))))

    @Test fun audiobookNumbered() = shot("audiobook_numbered", spokenModel(book, 4_100_000L, lead(StageTexts.sleep(Fixtures.at(23, 10), Fixtures.texts, Fixtures.zone))))

    @Test fun audiobookPaper() = shot("audiobook_paper", spokenModel(book, 2_554_000L, lead(TimeText.speed(1.5f, Fixtures.texts)), appearance = Appearance.LIGHT))

    @Test fun podcast() = shot("podcast", spokenModel(episode, 1_163_000L, lead(StageTexts.sleep(Fixtures.at(23, 10), Fixtures.texts, Fixtures.zone)), next = Catalog.nextEpisode))

    @Test fun podcastFlow() = captureRoboImage("build/screenshots/spoken/podcast_flow.png") {
        val scenes = Fixtures.flowScenes(listOf(Fixtures.track(SyntheticCover.MOSS), episode, Fixtures.track(SyntheticCover.TERRACOTTA)))
        Fixtures.Still { FlowStage(spokenModel(episode, 1_163_000L, lead(null), view = StageView.FLOW), Fixtures.restingCards(scenes, 1)) }
    }
}
