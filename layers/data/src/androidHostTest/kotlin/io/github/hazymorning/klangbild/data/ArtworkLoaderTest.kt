package io.github.hazymorning.klangbild.data

import assertk.assertThat
import assertk.assertions.containsExactly
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

/** Covers are downloaded with a limit: nothing heavier than a cover is read. */
class ArtworkLoaderTest {
    @Test
    fun `a download heavier than any cover is not read`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray((ArtworkLoader.MAX_BYTES + 1).toInt()))).build())
            server.enqueue(MockResponse.Builder().body("cover").build())
            val decoded = CopyOnWriteArrayList<Int>()
            val loader = httpClient(OkHttp.create()).let { http -> ArtworkLoader(http, http, { null }, { bytes -> decoded += bytes.size; null }) }
            loader.load(TrackInfo("x", artworkUrl = server.url("/huge.png").toString()))
            loader.load(TrackInfo("y", artworkUrl = server.url("/cover.png").toString()))
            assertThat(decoded).containsExactly("cover".length)
        }
    }
}
