package io.github.hazymorning.klangbild.data

import assertk.assertThat
import assertk.assertions.containsExactly
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

/** Covers are downloaded with a limit, and an address that doesn't answer can't hold up the fallback. */
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

    @Test
    fun `an address that never answers leaves the fallback its turn`() = runBlocking {
        // A server that takes the call and never answers it.
        ServerSocket(0).use { silent ->
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body("cover").build())
                val decoded = CopyOnWriteArrayList<Int>()
                val loader = httpClient(OkHttp.create()).let { http ->
                    ArtworkLoader(http, http, { null }, { bytes -> decoded += bytes.size; null }, fallbackAfterMs = 300L)
                }
                val track = TrackInfo("x", artworkUrl = "http://127.0.0.1:${silent.localPort}/cover.png", artworkFallbackUrl = server.url("/cover.png").toString())
                withTimeout(2_000) { loader.load(track) }
                assertThat(decoded).containsExactly("cover".length)
            }
        }
    }
}
