package io.github.hazymorning.klangbild.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.data.AndroidPictureDecoder
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.data.httpClient
import io.github.hazymorning.klangbild.domain.ArtistBio
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.Lrc
import io.github.hazymorning.klangbild.domain.PlayerSnapshot
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.SongBio
import io.github.hazymorning.klangbild.domain.SongLyrics
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
import kotlin.test.Test

/** A few sentences about the artist take the text column as chosen, after the song's words and before the queue's list. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ArtistInfoTest {
    /** No picture comes: the generated cover stands in at once. */
    private val http = httpClient(OkHttp.create { addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not found").body("".toResponseBody()).build()
    } })

    private val song = TrackInfo("Eins", listOf("Lumen Drive"), album = "Album", durationMs = 200_000, artworkUrl = "http://192.168.1.5:8095/one.jpg")
    private val next = TrackInfo("Zwei", listOf("Theo Lind"), album = "Other", durationMs = 180_000, artworkUrl = "http://192.168.1.5:8095/two.jpg")
    private val bio = ArtistBio.of("Lumen Drive is a German synthwave duo. It was formed in 2016.")!!
    private val source = SourceState(
        link = LinkState.CONNECTED,
        player = PlayerSnapshot(PlayerStatus.PLAYING, song),
        next = next,
        queueKnown = true,
        coming = listOf(next),
        bio = SongBio(song.key, bio),
    )

    /** The stage for [song] as the builder makes it with [settings] from [source]. */
    private fun stage(settings: Settings, source: SourceState = this.source): StageData = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val builder = StageBuilder(scope, ArtworkLoader(http, http, { null }, AndroidPictureDecoder), QuietLog, System::currentTimeMillis) {}
            builder.screen(ScreenSpec(960, 540, 1f))
            val stage = Screen.Stage(song, PlayerStatus.PLAYING, next = next)
            builder.follow(settings, source, stage, onStage = null)
            var ui = UiState(screen = stage, queue = settings.queue, source = source)
            withTimeout(30_000) {
                while (ui.stage == null) {
                    ui = builder.build(ui, settings, stage, Look(Appearance.DARK, 1f), null)
                    delay(20)
                }
            }
            ui.stage!!
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `with Artist info on, the sentences take the column, also from the queue's list`() {
        val on = stage(Settings(artistInfo = true, queue = QueueDisplay.LIST))
        assertThat(on.bio).isSameInstanceAs(bio)
        assertThat(on.queue).isNull()
        val off = stage(Settings(queue = QueueDisplay.LIST))
        assertThat(off.bio).isNull()
        assertThat(off.queue).isNotNull()
    }

    @Test
    fun `the song's words come first, and another song's artist is not this one's`() {
        val words = Lrc.parse("[00:05.00]Erste Zeile\n[00:09.00]Zweite Zeile")!!
        val both = stage(Settings(artistInfo = true, lyrics = true), source.copy(lyrics = SongLyrics(song.key, words)))
        assertThat(both.lyrics).isSameInstanceAs(words)
        assertThat(both.bio).isNull()
        // Lyrics on, but this song has none: the artist's sentences.
        assertThat(stage(Settings(artistInfo = true, lyrics = true), source.copy(lyrics = SongLyrics(song.key, null))).bio).isSameInstanceAs(bio)
        assertThat(stage(Settings(artistInfo = true), source.copy(bio = SongBio(next.key, bio))).bio).isNull()
    }
}
