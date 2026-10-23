package io.github.hazymorning.klangbild.data

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNull
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.test.Test

/** Covers are fetched with limits: no download beyond a cover's weight, no picture beyond a cover's size. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ArtworkLoaderTest {
    private fun png(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    @Test
    fun `odd shapes are subsampled to the size of a large square`() {
        val wide = AndroidPictureDecoder.decode(png(9000, 600))!!
        assertThat(wide.width.toLong() * wide.height).isLessThanOrEqualTo(ArtworkLoader.MAX_DECODE_PX.toLong() * ArtworkLoader.MAX_DECODE_PX)
        val square = AndroidPictureDecoder.decode(png(3000, 3000))!!
        assertThat(square.width).isEqualTo(1500)
    }

    @Test
    fun `a download heavier than any cover is not read`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray((ArtworkLoader.MAX_BYTES + 1).toInt()))).build())
            server.enqueue(MockResponse.Builder().body(Buffer().write(png(64, 64))).build())
            val loader = httpClient(OkHttp.create()).let { http -> ArtworkLoader(http, http, { null }, AndroidPictureDecoder) }
            assertThat(loader.load(TrackInfo("x", artworkUrl = server.url("/huge.png").toString()))).isNull()
            assertThat(loader.load(TrackInfo("y", artworkUrl = server.url("/cover.png").toString()))!!.width).isEqualTo(64)
        }
    }
}
