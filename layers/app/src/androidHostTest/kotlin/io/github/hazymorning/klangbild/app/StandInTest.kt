package io.github.hazymorning.klangbild.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.data.AndroidPictureDecoder
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.data.httpClient
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test

/**
 * A song whose picture did not come shows the generated cover only until it does: the picture is
 * asked for again, at once when the song names a new address for it, else after a while, and its
 * scene then takes the generated one's place. Without a picture the generated cover stays as it is.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StandInTest {
    /** Whether the server has the picture now, and how often it was asked. */
    private val serves = AtomicBoolean(false)
    private val asked = AtomicInteger()
    private val clock = AtomicLong(1_000_000L)

    private val png: ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(180, 60, 40)) }.compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    private val http = httpClient(OkHttp.create { addInterceptor { chain ->
        asked.incrementAndGet()
        val ok = serves.get()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(if (ok) 200 else 404).message(if (ok) "OK" else "Not found")
            .body(if (ok) png.toResponseBody("image/png".toMediaType()) else "".toResponseBody()).build()
    } })

    private val look = Look(Appearance.DARK, 1f)
    private val track = TrackInfo("Song", listOf("A"), album = "Album", artworkUrl = "http://192.168.1.5:8095/imageproxy/a?size=0")

    private fun withScenes(block: suspend (Scenes) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(Scenes(scope, ArtworkLoader(http, http, { null }, AndroidPictureDecoder), QuietLog, clock::get))
        } finally {
            scope.cancel()
        }
    }

    private suspend fun Scenes.made(track: TrackInfo): ArtworkScene {
        val made = CompletableDeferred<ArtworkScene>()
        prepare(track, look) { made.complete(it) }
        return withTimeout(30_000) { made.await() }
    }

    private suspend fun Scenes.settled(track: TrackInfo) = withTimeout(30_000) { while (pending(track)) delay(5) }

    @Test
    fun `the picture takes the stand-in's place once it comes`() = withScenes { scenes ->
        val standIn = scenes.made(track)
        assertThat(standIn.coverGenerated).isTrue()
        // Not again right away: the stand-in stays.
        serves.set(true)
        scenes.prepare(track, look)
        assertThat(scenes.pending(track)).isFalse()
        assertThat(scenes.ready(track, look)).isSameInstanceAs(standIn)
        // A while later the picture is asked for again, and it takes the stand-in's place.
        clock.addAndGet(Scenes.RETRY_MS)
        val scene = scenes.made(track)
        assertThat(scene.coverGenerated).isFalse()
        assertThat(scenes.ready(track, look)).isSameInstanceAs(scene)
        // Once it is there, it is never asked for again.
        val before = asked.get()
        clock.addAndGet(Scenes.RETRY_MS * 4)
        scenes.prepare(track, look)
        assertThat(scenes.pending(track)).isFalse()
        assertThat(asked.get()).isEqualTo(before)
    }

    @Test
    fun `an address that comes later is asked for at once`() = withScenes { scenes ->
        serves.set(true)
        val bare = track.copy(artworkUrl = null)
        val generated = scenes.made(bare)
        assertThat(generated.coverGenerated).isTrue()
        assertThat(asked.get()).isEqualTo(0)
        // The same song, now with its picture's address: no waiting.
        val scene = scenes.made(track)
        assertThat(scene.coverGenerated).isFalse()
        assertThat(scenes.ready(track, look)).isSameInstanceAs(scene)
    }

    @Test
    fun `a stand-in that still finds no picture stays as it is`() = withScenes { scenes ->
        val standIn = scenes.made(track)
        val first = asked.get()
        clock.addAndGet(Scenes.RETRY_MS)
        scenes.prepare(track, look)
        assertThat(scenes.pending(track)).isTrue()
        scenes.settled(track)
        // It was asked for, found nothing, and the screen keeps the very same scene (nothing to fade).
        assertThat(asked.get() > first).isTrue()
        assertThat(scenes.ready(track, look)).isNotNull().isSameInstanceAs(standIn)
    }

    @Test
    fun `a song without a picture keeps its generated cover`() = withScenes { scenes ->
        val bare = track.copy(artworkUrl = null)
        val generated = scenes.made(bare)
        clock.addAndGet(Scenes.RETRY_MS * 4)
        scenes.prepare(bare, look)
        assertThat(scenes.pending(bare)).isFalse()
        assertThat(scenes.ready(bare, look)).isSameInstanceAs(generated)
        assertThat(asked.get()).isEqualTo(0)
    }
}
