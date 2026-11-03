package io.github.hazymorning.klangbild.data

import io.github.hazymorning.klangbild.domain.AppVersion
import io.github.hazymorning.klangbild.domain.Release
import io.github.hazymorning.klangbild.json.string
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Klangbild's releases on GitHub, asked for without credentials: the newest one published (not a
 * beta or a draft, which GitHub leaves out) with its notes, and its app. [api] and [site] are GitHub's own but
 * in tests.
 */
class Releases(private val http: HttpClient, private val api: String = API, private val site: String = SITE) {
    /** The newest release, with its notes; null when it names no version this app reads. */
    suspend fun latest(): Release? = http.fetch(CHECK_MS, {
        url("$api/repos/$REPOSITORY/releases/latest")
        header("Accept", "application/vnd.github+json")
    }) { response ->
        if (!response.status.isSuccess()) throw HttpStatusException("GitHub", response.status.value)
        val release = Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        val tag = release?.string("tag_name")?.takeIf { AppVersion.parse(it) != null }
        tag?.let { Release(it, release.string("body")?.take(MAX_NOTES)) }
    }

    /**
     * Downloads the app of the release [tag] (one [latest] named) and hands it to [install] as it
     * comes; false when GitHub doesn't have it or [install] can't take it.
     */
    suspend fun download(tag: String, install: suspend (ByteReadChannel) -> Boolean): Boolean {
        require(AppVersion.parse(tag) != null) { "not a release's tag: $tag" }
        return http.fetch(DOWNLOAD_MS, {
            url("$site/$REPOSITORY/releases/download/$tag/$APK")
            // Not kept with the covers in the platform's cache.
            header("Cache-Control", "no-store")
        }, readTimeoutMs = READ_MS) { response ->
            response.status.isSuccess() && install(response.bodyAsChannel())
        }
    }

    private companion object {
        const val API = "https://api.github.com"
        const val SITE = "https://github.com"
        const val REPOSITORY = "hazymorning/Klangbild"

        /** A release's notes are kept up to this long: far more than they ever are. */
        const val MAX_NOTES = 20_000

        /** Every release's app has this name. */
        const val APK = "klangbild.apk"

        /** The question may take this long (ms)... */
        const val CHECK_MS = 20_000L

        /** ...the download this long, and wait this long for its next bytes. */
        const val DOWNLOAD_MS = 600_000L
        const val READ_MS = 30_000L
    }
}
