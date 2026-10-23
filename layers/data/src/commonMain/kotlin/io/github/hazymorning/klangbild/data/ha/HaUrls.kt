package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.WebUrl
import io.github.hazymorning.klangbild.data.WebUrl.Companion.toWebUrlOrNull

/**
 * URL handling for Home Assistant. Users type all sorts of things ("192.168.1.5:8123",
 * "homeassistant.local", "https://xyz.ui.nabu.casa/lovelace/0"); this turns them into a
 * clean base URL and derives the WebSocket endpoint and absolute picture URLs from it.
 */
object HaUrls {

    /**
     * Normalizes user input to a base URL like `http://192.168.1.5:8123` (no trailing slash,
     * no path). Without a scheme, local-looking hosts get `http`, everything else `https`.
     * Returns null if the input cannot be a URL.
     */
    fun normalizeBase(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("ws://", ignoreCase = true)) s = "http://" + s.substring(5)
        if (s.startsWith("wss://", ignoreCase = true)) s = "https://" + s.substring(6)
        if (!s.contains("://")) {
            val hostPart = s.substringBefore('/').substringBefore(':')
            val scheme = if (LocalNetwork.looksLocal(hostPart)) "http" else "https"
            s = "$scheme://$s"
        }
        val url = s.toWebUrlOrNull() ?: return null
        if (url.host.isEmpty()) return null
        val defaultPort = (url.scheme == "http" && url.port == 80) || (url.scheme == "https" && url.port == 443)
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        return buildString {
            append(url.scheme).append("://").append(host)
            if (!defaultPort) append(':').append(url.port)
        }
    }

    /** `ws(s)://host:port/api/websocket` for a normalized base URL. */
    fun webSocketUrl(base: String): String {
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$wsBase/api/websocket"
    }

    /**
     * Resolves `entity_picture`: relative proxy paths (`/api/media_player_proxy/...`) are
     * joined with the base URL, absolute URLs pass through unchanged.
     */
    fun resolve(base: String, picture: String?): String? {
        val p = picture?.trim().orEmpty()
        if (p.isEmpty()) return null
        if (p.startsWith("http://", true) || p.startsWith("https://", true)) return p
        if (p.startsWith("//")) return (if (base.startsWith("https")) "https:" else "http:") + p
        val b = base.toWebUrlOrNull() ?: return null
        return b.resolve(if (p.startsWith("/")) p else "/$p")?.toString()
    }

    /** A picture Home Assistant serves through its media player proxy (`/api/media_player_proxy/…`). */
    fun isPlayerProxy(url: String): Boolean = url.contains("/api/media_player_proxy/")

    /**
     * Only requests to the Home Assistant host itself may carry the access token. Absolute
     * artwork URLs (a streaming service's CDN, Music Assistant) must never see it.
     */
    fun isHaHost(base: String, url: String): Boolean {
        val b: WebUrl = base.toWebUrlOrNull() ?: return false
        val u: WebUrl = url.toWebUrlOrNull() ?: return false
        return b.host.equals(u.host, ignoreCase = true) && b.port == u.port && b.scheme == u.scheme
    }
}
