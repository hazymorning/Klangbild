package io.github.hazymorning.klangbild.data

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import io.ktor.client.request.HttpRequestBuilder
import kotlin.test.Test

/** A token in the Authorization header: as it is, or refused as OkHttp refuses it. */
class BearerTest {
    @Test
    fun `a token goes into the header as it is`() {
        val request = HttpRequestBuilder().apply { bearer("abc.DEF-123_~+/=\tx") }
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer abc.DEF-123_~+/=\tx")
    }

    @Test
    fun `a token with a character no header holds is refused, and the message leaves the token out`() {
        assertFailure { HttpRequestBuilder().bearer("secret\nX-Other: 1") }
            .isInstanceOf<IllegalArgumentException>()
            .hasMessage("Unexpected char 0x0a at 13 in Authorization value")
        assertFailure { HttpRequestBuilder().bearer("geheimä") }
            .isInstanceOf<IllegalArgumentException>()
            .hasMessage("Unexpected char 0xe4 at 13 in Authorization value")
    }
}
