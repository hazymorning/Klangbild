package io.github.hazymorning.klangbild.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.Catalog
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.text.TestTexts
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.stage.Stage
import io.github.hazymorning.klangbild.ui.stage.StageModel
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/** The queue as a list beside the cover (Queue: List), as stills. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class QueueStageTest {
    private val en = TestTexts.english()

    private fun shot(name: String, model: StageModel, t: Texts = Fixtures.texts) =
        captureRoboImage("build/screenshots/queue/$name.png") { Fixtures.Still(t) { Stage(model) } }

    private val playing = Fixtures.tracks[0]

    @Test
    fun playlistDark() = shot("playlist_dark", Fixtures.queueModel(playing, playlistQueue))

    @Test
    fun playlistLight() = shot("playlist_light", Fixtures.queueModel(playing, playlistQueue, appearance = Appearance.LIGHT))

    @Test
    fun playlistOnCoverColors() = shot("playlist_cover_colors", Fixtures.queueModel(playing, playlistQueue, coverField = true))

    @Test
    fun playlistOnPaleCoverColors() =
        shot("playlist_cover_colors_light", Fixtures.queueModel(playing, playlistQueue, appearance = Appearance.LIGHT, coverField = true))

    /** Paused, through the theater: the words, the cover and the rows' small covers dimmed. */
    @Test
    fun paused() {
        val stage = StageData(playing, Fixtures.scene(playing), queue = playlistQueue)
        captureRoboImage("build/screenshots/queue/paused.png") { Fixtures.Still(en) { Fixtures.QueueTheater(stage, PlayerStatus.PAUSED, t = en, group = "Living room") } }
    }

    /** An album: its songs by number, then the next record with its cover and artist. */
    @Test
    fun albumDark() = shot("album_dark", Fixtures.queueModel(desert, album, group = "Living room", t = en), en)

    @Test
    fun albumLight() = shot("album_light", Fixtures.queueModel(desert, album, appearance = Appearance.LIGHT, group = "Living room", t = en), en)

    /** A title on two lines leaves the rows less room. */
    @Test
    fun longTitle() {
        val long = Fixtures.tracks[7]
        shot("long_title", Fixtures.queueModel(long, Fixtures.queueList(playlist.filter { it != long })))
    }

    /** Two songs left: the list ends inside its room, and nothing fades. */
    @Test
    fun endOfTheQueue() = shot("end_of_queue", Fixtures.queueModel(desert, Fixtures.queueList(albumSongs.takeLast(2)), group = "Living room", t = en), en)

    /** No bar (hidden by setting): the rows move up. */
    @Test
    fun withoutBar() = shot("no_bar", Fixtures.queueModel(playing, playlistQueue, progress = false))

    /** The small covers still on their way: the rows show without them. */
    @Test
    fun coversOnTheirWay() = shot("covers_on_their_way", Fixtures.queueModel(playing, Fixtures.queueList(playlist, covers = false)))

    private companion object {
        /** A playlist: every song from another album, the catalog's hard cases among them. */
        val playlist = Fixtures.tracks.drop(1).filter { it.durationMs != null }
        val playlistQueue = Fixtures.queueList(playlist)
        val desert = Catalog.desert
        val albumSongs = Catalog.cairoNights
        val nextRecord = Catalog.northernHours
        val album = Fixtures.queueList(albumSongs + nextRecord)
    }
}

/** The list at 3840 × 2160 (xxxhdpi): every row and small cover crisp. */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xxxhdpi")
class QueueStage4kTest {
    @Test
    fun album4k() {
        val en = TestTexts.english()
        val list = Fixtures.queueList(Catalog.cairoNights + Catalog.northernHours, screen = Fixtures.TV_4K)
        captureRoboImage("build/screenshots/queue/album_4k.png") {
            Fixtures.Still(en) { Stage(Fixtures.queueModel(Catalog.desert, list, screen = Fixtures.TV_4K, group = "Living room", t = en)) }
        }
    }
}
