package io.github.hazymorning.klangbild.data.ha

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HaUrlsTest {
    @Test
    fun `normalizes typical user input`() {
        assertThat(HaUrls.normalizeBase("192.168.1.5:8123")).isEqualTo("http://192.168.1.5:8123")
        assertThat(HaUrls.normalizeBase(" http://192.168.1.5:8123/ ")).isEqualTo("http://192.168.1.5:8123")
        assertThat(HaUrls.normalizeBase("homeassistant.local:8123")).isEqualTo("http://homeassistant.local:8123")
        assertThat(HaUrls.normalizeBase("homeassistant:8123")).isEqualTo("http://homeassistant:8123")
        assertThat(HaUrls.normalizeBase("abc123.ui.nabu.casa")).isEqualTo("https://abc123.ui.nabu.casa")
        assertThat(HaUrls.normalizeBase("https://abc123.ui.nabu.casa/lovelace/0")).isEqualTo("https://abc123.ui.nabu.casa")
        assertThat(HaUrls.normalizeBase("HTTPS://Example.org:443")).isEqualTo("https://example.org")
        assertThat(HaUrls.normalizeBase("ws://10.0.0.2:8123/api/websocket")).isEqualTo("http://10.0.0.2:8123")
        assertThat(HaUrls.normalizeBase("http://[fd00::12]:8123")).isEqualTo("http://[fd00::12]:8123")
    }

    @Test
    fun `rejects garbage`() {
        assertThat(HaUrls.normalizeBase("")).isNull()
        assertThat(HaUrls.normalizeBase("   ")).isNull()
        assertThat(HaUrls.normalizeBase("http://")).isNull()
    }

    @Test
    fun `derives websocket url`() {
        assertThat(HaUrls.webSocketUrl("http://192.168.1.5:8123")).isEqualTo("ws://192.168.1.5:8123/api/websocket")
        assertThat(HaUrls.webSocketUrl("https://abc.ui.nabu.casa")).isEqualTo("wss://abc.ui.nabu.casa/api/websocket")
    }

    @Test
    fun `resolves relative proxy paths and keeps absolute urls`() {
        val base = "http://192.168.1.5:8123"
        assertThat(HaUrls.resolve(base, "/api/media_player_proxy/media_player.wz?token=abc&cache=123"))
            .isEqualTo("http://192.168.1.5:8123/api/media_player_proxy/media_player.wz?token=abc&cache=123")
        assertThat(HaUrls.resolve(base, "http://192.168.1.6:8095/imageproxy/ff00?size=512&fmt=jpg"))
            .isEqualTo("http://192.168.1.6:8095/imageproxy/ff00?size=512&fmt=jpg")
        assertThat(HaUrls.resolve("https://x.ui.nabu.casa", "/api/media_player_proxy/a?token=1"))
            .isEqualTo("https://x.ui.nabu.casa/api/media_player_proxy/a?token=1")
        assertThat(HaUrls.resolve(base, null)).isNull()
        assertThat(HaUrls.resolve(base, "")).isNull()
    }

    @Test
    fun `token only goes to the home assistant host`() {
        val base = "http://192.168.1.5:8123"
        assertThat(HaUrls.isHaHost(base, "http://192.168.1.5:8123/api/media_player_proxy/x")).isTrue()
        assertThat(HaUrls.isHaHost(base, "http://192.168.1.5:8095/imageproxy/x")).isFalse()
        assertThat(HaUrls.isHaHost(base, "https://i.scdn.co/image/abc")).isFalse()
    }
}
