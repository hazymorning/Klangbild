package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.klangbild.Pictures
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.domain.Catalog
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ScreenSpec
import org.junit.Rule
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * The queue's list in motion, as a sequence of stills: it comes beside the cover and goes again
 * (the cover never moves), a new song moves it up (the rows that stay glide to their places, the
 * first goes as the new title comes, a new row comes at the foot), and a queue changed beneath
 * the song (only the rows move), two songs swapped (they never cross), and the bar coming for a
 * moment (the list makes room).
 */
@Category(Pictures::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class QueueMotionTest {
    @get:Rule
    val compose = composeRule()

    private val mdpi = ScreenSpec(960, 540, 1f)

    private val songs = listOf(Catalog.desert) + Catalog.cairoNights + Catalog.northernHours

    /** The stage for the song at [index] of [songs], its list holding what follows it. */
    private fun at(index: Int, list: Boolean = true, coming: List<TrackInfo> = songs.drop(index + 1)): StageData {
        val track = songs[index]
        val q = if (list) Fixtures.queueList(coming, screen = mdpi) else null
        return StageData(track, Fixtures.scene(track, mdpi), queue = q)
    }

    private fun frames(name: String, first: StageData, every: Long, count: Int, then: Map<Int, StageData>) {
        var stage by mutableStateOf(first)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.QueueTheater(stage) }
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureRoboImage("build/screenshots/queue_motion/${name}_00.png")
        for (i in 1..count) {
            then[i]?.let { stage = it }
            compose.mainClock.advanceTimeBy(every)
            compose.onRoot().captureRoboImage("build/screenshots/queue_motion/${name}_%02d.png".format(i))
        }
    }

    @Test
    fun theListComes() {
        val off = at(0, list = false)
        frames("comes", off, 100, 12, mapOf(1 to off.copy(queue = at(0).queue)))
    }

    @Test
    fun theListGoes() {
        val on = at(0)
        frames("goes", on, 100, 12, mapOf(1 to on.copy(queue = null)))
    }

    @Test
    fun theNextSongOfTheAlbumMovesTheListUp() = frames("album_step", at(0), 80, 14, mapOf(1 to at(1)))

    @Test
    fun aNewRecordMovesTheListUpToo() = frames("record_step", at(3), 80, 14, mapOf(1 to at(4)))

    /** Two songs swap places: they go and come again, never passing through each other. */
    @Test
    fun aSwapNeverCrosses() {
        val before = at(0)
        val coming = songs.drop(1)
        val swapped = listOf(coming[1], coming[0]) + coming.drop(2)
        frames("swap", before, 100, 10, mapOf(1 to before.copy(queue = Fixtures.queueList(swapped, screen = mdpi))))
    }

    /** The bar hidden by setting comes for a moment (a key): the rows make room as it fades in, the last row goes first. */
    @Test
    fun theBarMakesRoomAsItComes() {
        var progress by mutableStateOf(false)
        val stage = at(0)
        compose.mainClock.autoAdvance = false
        compose.setContent { Fixtures.QueueTheater(stage, progress = progress, barStays = false) }
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureRoboImage("build/screenshots/queue_motion/bar_00.png")
        progress = true
        for (i in 1..10) {
            compose.mainClock.advanceTimeBy(50)
            compose.onRoot().captureRoboImage("build/screenshots/queue_motion/bar_%02d.png".format(i))
        }
    }

    @Test
    fun aSongPutInMovesOnlyTheRows() {
        val inserted = TrackInfo("Interlude", listOf("Amal Haddad"), "Cairo Nights", 2016, 95_000, artworkUrl = Catalog.desert.artworkUrl, queueItemId = "x")
        val before = at(0)
        // The same song and scene: only its queue changed.
        val after = before.copy(queue = Fixtures.queueList(listOf(inserted) + songs.drop(1), screen = mdpi))
        frames("put_in", before, 100, 10, mapOf(1 to after))
    }
}
