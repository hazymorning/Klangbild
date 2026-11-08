package io.github.hazymorning.klangbild.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.AndroidPictureDecoder
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.data.httpClient
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.PlayerSnapshot
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import kotlin.test.Test

/**
 * The next song's scene is made while the song before it plays, so it shows as soon as it starts
 * and the song before (its lyrics too) never stays on screen while it is made. In Cover Flow too,
 * where the album's next track shares the middle card and is no card of its own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MadeAheadTest {
    /** The pictures asked for, in order. */
    private val asked: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val http = httpClient(OkHttp.create { addInterceptor { chain ->
        asked += chain.request().url.toString()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not found").body("".toResponseBody()).build()
    } })

    private val first = TrackInfo("Eins", listOf("A"), album = "Album", artworkUrl = "http://192.168.1.5:8095/one.jpg")
    private val second = TrackInfo("Zwei", listOf("A"), album = "Album", artworkUrl = "http://192.168.1.5:8095/two.jpg")
    private val other = TrackInfo("Drei", listOf("B"), album = "Other", artworkUrl = "http://192.168.1.5:8095/three.jpg")

    @Test
    fun `Cover Flow makes the album's next track ahead`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val builder = StageBuilder(scope, ArtworkLoader(http, http, { null }, AndroidPictureDecoder), QuietLog, System::currentTimeMillis) {}
            builder.screen(ScreenSpec(960, 540, 1f))
            val settings = Settings(queue = QueueDisplay.FLOW)
            val source = SourceState(
                link = LinkState.CONNECTED,
                player = PlayerSnapshot(PlayerStatus.PLAYING, first),
                next = second,
                queueKnown = true,
                coming = listOf(second, other),
            )
            val stage = Screen.Stage(first, PlayerStatus.PLAYING, next = second)
            val look = Look(Appearance.DARK, 1f)
            // Another album comes later: Cover Flow shows, with the album's card in the middle.
            assertThat(builder.follow(settings, source, stage, onStage = null)).isTrue()
            var ui = UiState(screen = stage, queue = QueueDisplay.FLOW, source = source)
            withTimeout(30_000) {
                while (ui.flow?.cards?.size != 2) {
                    ui = builder.build(ui, settings, stage, look, null)
                    delay(20)
                }
            }
            assertThat(ui.flow?.middle?.track).isEqualTo(first)
            // While the first track still plays, the second one's picture is asked for.
            withTimeout(30_000) {
                while (second.artworkUrl !in asked) {
                    ui = builder.build(ui, settings, stage, look, null)
                    delay(20)
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
