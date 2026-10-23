package io.github.hazymorning.klangbild

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidPlatformTest {
    private val platform = AndroidPlatform(ApplicationProvider.getApplicationContext())

    @Test
    fun `a picture follows a redirect, a request with a token never does`() {
        MockWebServer().use { server ->
            server.start()
            val moved = MockResponse.Builder().code(302).addHeader("Location", "/moved").build()
            server.enqueue(moved)
            server.enqueue(MockResponse.Builder().body("picture").build())
            server.enqueue(moved)
            runBlocking {
                assertThat(platform.http.get(server.url("/cover").toString()).bodyAsText()).isEqualTo("picture")
                assertThat(platform.tokenHttp.get(server.url("/api/states").toString()).status.value).isEqualTo(302)
            }
        }
    }
}
