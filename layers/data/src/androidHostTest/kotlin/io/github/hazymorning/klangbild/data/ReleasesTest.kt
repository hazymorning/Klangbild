package io.github.hazymorning.klangbild.data

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test

/** Klangbild's releases on a stand-in for GitHub. */
class ReleasesTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString().trimEnd('/')
    private val releases = Releases(httpClient(OkHttp.create()), api = base, site = base)

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `the newest release is asked for by its tag, and only a version this app reads counts`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body("""{"tag_name":"v2026.11.0","draft":false,"prerelease":false}""").build())
        assertThat(releases.latest()).isEqualTo("v2026.11.0")
        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/repos/hazymorning/Klangbild/releases/latest")
        assertThat(request.headers["Accept"]).isEqualTo("application/vnd.github+json")
        assertThat(request.headers["Authorization"]).isNull()
        server.enqueue(MockResponse.Builder().body("""{"tag_name":"6.4.0"}""").build())
        assertThat(releases.latest()).isNull()
        server.enqueue(MockResponse.Builder().code(403).build())
        assertFailure { releases.latest() }.isInstanceOf<HttpStatusException>()
    }

    @Test
    fun `the app of a release comes from its download`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().body("apk").build())
        var got = ""
        assertThat(releases.download("v2026.11.0") { got = it.readBuffer().readByteArray().decodeToString(); true }).isTrue()
        assertThat(got).isEqualTo("apk")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/hazymorning/Klangbild/releases/download/v2026.11.0/klangbild.apk")
        server.enqueue(MockResponse.Builder().code(404).build())
        assertThat(releases.download("v2026.11.1") { true }).isFalse()
        assertFailure { releases.download("../other") { true } }.isInstanceOf<IllegalArgumentException>()
    }
}
