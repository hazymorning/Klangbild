package io.github.hazymorning.klangbild.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.data.AndroidPictureDecoder
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.data.httpClient
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CompletableDeferred
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * A switch to Cover Flow and straight back lets go of the scene still being made for Cover Flow:
 * the single cover's own scene starts at once instead of waiting for one nobody will show.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScenesTest {
    /** How often a cover was asked for. The first ask hangs until it is canceled; later ones find no cover, and the figure steps in. */
    private val asked = AtomicInteger()

    private val http = httpClient(OkHttp.create { addInterceptor { chain ->
        if (asked.getAndIncrement() == 0) {
            while (!chain.call().isCanceled()) Thread.sleep(5)
            throw IOException("canceled")
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not found").body("".toResponseBody()).build()
    } })

    @Test
    fun `a switch back never waits for a scene made for the frame left`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scenes = Scenes(scope, ArtworkLoader(http, http, { null }, AndroidPictureDecoder))
            val screen = ScreenSpec(960, 540, 1f)
            val track = TrackInfo("Song", listOf("A"), artworkUrl = "http://192.168.1.5:8095/cover.jpg")
            val look = Look(Appearance.DARK, 1f)
            scenes.frame = Frame(screen, flow = true)
            scenes.prepare(track, look)
            assertThat(scenes.pending(track)).isTrue()
            // Cover Flow's scene is really waiting for its cover.
            withTimeout(5_000) { while (asked.get() == 0) delay(5) }
            // Back to the single cover while Cover Flow's scene still waits for its cover.
            scenes.frame = Frame(screen, flow = false)
            assertThat(scenes.pending(track)).isFalse()
            val made = CompletableDeferred<ArtworkScene>()
            val start = System.nanoTime()
            scenes.prepare(track, look) { made.complete(it) }
            assertThat(scenes.pending(track)).isTrue()
            val scene = withTimeout(30_000) { made.await() }
            // It never waited for the cover the scene left behind was still waiting for.
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(Scenes.COVER_TIMEOUT_MS)
            assertThat(scenes.ready(track, look)).isSameInstanceAs(scene)
            assertThat(scenes.pending(track)).isFalse()
            // The cover was asked for again, and its first ask let go.
            assertThat(asked.get()).isGreaterThanOrEqualTo(2)
        } finally {
            scope.cancel()
        }
    }
}
