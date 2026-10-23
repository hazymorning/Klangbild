package io.github.hazymorning.klangbild.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.hazymorning.klangbild.data.WebUrl.Companion.toWebUrl
import io.github.hazymorning.klangbild.data.WebUrl.Companion.toWebUrlOrNull
import io.ktor.client.request.HttpRequestBuilder
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import kotlin.test.Test

/**
 * The URL code ported from OkHttp ([WebUrl] with its host names, Punycode and IDNA mapping, and
 * [address]) reads, writes and requests every URL as OkHttp itself does: the addresses people
 * type and the awkward ones.
 */
class WebUrlTest {
    @Test
    fun `a URL is read and written as OkHttp reads and writes it`() {
        for (input in INPUTS) {
            val ours = input.toWebUrlOrNull()
            val theirs = input.toHttpUrlOrNull()
            assertThat(ours?.toString(), name = input).isEqualTo(theirs?.toString())
            if (ours == null || theirs == null) continue
            assertThat(ours.scheme, name = input).isEqualTo(theirs.scheme)
            assertThat(ours.host, name = input).isEqualTo(theirs.host)
            assertThat(ours.port, name = input).isEqualTo(theirs.port)
        }
    }

    @Test
    fun `a link resolves as OkHttp resolves it`() {
        for (base in listOf("http://a/b/c/d;p?q", "https://host:8123/api/states", "http://[::1]/")) {
            for (link in LINKS) {
                assertThat(base.toWebUrl().resolve(link)?.toString(), name = "$base + $link").isEqualTo(base.toHttpUrl().resolve(link)?.toString())
            }
        }
    }

    @Test
    fun `path segments and query parameters are encoded as OkHttp encodes them`() {
        for (segment in listOf("a b", "ä", "€", "%", "%41", "/", "\\", "?", "#", ".", "..", "", "a+b", "😀")) {
            for ((name, value) in listOf("q" to "a b", "ä" to "&=", "+" to "+", "x" to null, "" to "", "%" to "%41", "#" to "?/")) {
                val ours = "http://host/api".toWebUrl().newBuilder().addPathSegment(segment).addQueryParameter(name, value).build()
                val theirs = "http://host/api".toHttpUrl().newBuilder().addPathSegment(segment).addQueryParameter(name, value).build()
                assertThat(ours.toString(), name = "$segment, $name=$value").isEqualTo(theirs.toString())
            }
        }
    }

    @Test
    fun `a request goes where OkHttp would send it`() {
        for (input in INPUTS + listOf("ws://192.168.1.5:8095/ws", "wss://ha.example.com/api/websocket", "WS://Host:80/a b")) {
            // Ktor's OkHttp engine hands OkHttp the request's URL as a string.
            val requested = runCatching { HttpRequestBuilder().apply { address(input) }.build().url.toString() }.getOrNull()
            assertThat(requested?.let(::okHttpUrl), name = input).isEqualTo(okHttpUrl(input))
        }
    }

    /** The URL OkHttp requests for [url], or null where it refuses it. */
    private fun okHttpUrl(url: String): String? = runCatching { Request.Builder().url(url).build().url.toString() }.getOrNull()

    private companion object {
        /** Addresses as the setup page passes them on, and the awkward ones. */
        val INPUTS = listOf(
            "http://192.168.1.5:8123",
            "http://192.168.1.5:8123/",
            "https://homeassistant.local:8123",
            "http://homeassistant.local:8123/lovelace/0?edit=1",
            "https://ha.example.com",
            "HTTP://HA.Example.COM:8123/Path",
            "http://192.168.001.005:8123",
            "http://127.1/",
            "http://0x7f.0.0.1/",
            "http://1.2.3.4.5/",
            "http://256.1.1.1/",
            "http://[::1]:8123/",
            "http://[0:0:0:0:0:0:0:1]/",
            "http://[2001:db8::7]:8123",
            "http://[2001:DB8:0:0:8:800:200C:417A]/",
            "http://[::ffff:192.168.1.5]/",
            "http://[1:2:3:4:5:6:7::]/",
            "http://[::1:2:3:4:5:6:7]/",
            "http://[fe80::1%25en0]/",
            "http://[1::2::3]/",
            "http://[::1/",
            "http://[::1]x/",
            "http://user:pass@192.168.1.5:8123/",
            "http://us er:p@ss@host/",
            "http://:@host/",
            "http://host:65535/",
            "http://host:65536/",
            "http://host:0/",
            "http://host:/",
            "http://host:8a/",
            "https://host:443/",
            "http://host:80/",
            "https://host:80/",
            "http:/host/path",
            "http:host/path",
            "http:\\\\host\\path",
            "http://host/a/../b/./c",
            "http://host/a/%2e%2E/b",
            "http://host/a/b/../../../c",
            "http://host/%zz/%41%42/%",
            "http://host/a b/ä/€/\"<>^`{}|",
            "http://host/path?q=a b&r=ä&s=%zz&t=+&u&=&v=\"'<>",
            "http://host/path?",
            "http://host/path#",
            "http://host/path#frag ment ä %zz",
            "http://host?query",
            "http://host#fragment",
            "http://bücher.example/",
            "http://BÜCHER.example/",
            "http://xn--bcher-kva.example/",
            "http://例え.テスト/",
            "http://ⓐⓑⓒ.example/",
            "http://ＡＢＣ．example/",
            "http://ab­c.example/",
            "http://a‍b.example/",
            "http://exa%6Dple.com/",
            "http://exa%zzmple.com/",
            "http://a b.example/",
            "http://a_b.example/",
            "http://ä_b.example/",
            "http://-a.example/",
            "http://a..b/",
            "http://.a/",
            "http://a./",
            "http://xn--9i0fa/",
            "http://xn--/",
            "http://xn--a.example/",
            "http://😀.example/",
            "http://\uD800.example/",
            "http://host/\uD800/",
            "ftp://host/",
            "mailto:someone@example.com",
            "//host/path",
            "host:8123",
            "",
            " http://host/ ",
            "http://host/\ttab\n",
        )

        /** RFC 3986's examples of links (5.4.1 and 5.4.2), and a few more. */
        val LINKS = listOf(
            "g:h", "g", "./g", "g/", "/g", "//g", "?y", "g?y", "#s", "g#s", "g?y#s", ";x", "g;x", "g;x?y#s",
            "", ".", "./", "..", "../", "../g", "../..", "../../", "../../g",
            "../../../g", "../../../../g", "/./g", "/../g", "g.", ".g", "g..", "..g", "./../g", "./g/.",
            "g/./h", "g/../h", "g;x=1/./y", "g;x=1/../y", "g?y/./x", "g?y/../x", "g#s/./x", "g#s/../x", "http:g",
            "https://other/", "//other:8123/x", "\\\\other\\x", "a b", "%zz", "?a b", "#ä",
        )
    }
}
