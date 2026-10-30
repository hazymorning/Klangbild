package io.github.hazymorning.klangbild.config

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import kotlin.test.Test

class SettingsTest {
    private val url = "http://192.168.1.5:8123"
    private val set = Settings(entityId = "media_player.wz").withHa(url, HaCredentials.SignedIn("refresh", "http://192.168.1.23:8765/"))

    @Test
    fun `refused credentials are dropped until new ones are set`() {
        val refused = set.refused()
        assertThat(refused.haCredentials).isNull()
        assertThat(refused.haRefused).isTrue()
        // Saved again without new credentials, it is still refused.
        assertThat(refused.withHa(url, null).haRefused).isTrue()
        // New credentials, or another Home Assistant, start afresh.
        assertThat(refused.withHa(url, HaCredentials.LongLived("new")).haRefused).isFalse()
        assertThat(refused.withHa("http://192.168.1.6:8123", null).haRefused).isFalse()
    }

    @Test
    fun `a registration for notifications belongs to the Home Assistant it was made with`() {
        val registered = set.copy(notifyWebhook = "hook", notifyDeviceId = "a1b2")
        assertThat(registered.withHa(url, HaCredentials.LongLived("new")).notifyWebhook).isEqualTo("hook")
        val moved = registered.withHa("http://192.168.1.6:8123", HaCredentials.LongLived("other"))
        assertThat(moved.notifyWebhook).isNull()
        // Registered there, this TV stays the same device.
        assertThat(moved.notifyDeviceId).isEqualTo("a1b2")
    }
}
