package io.github.hazymorning.klangbild.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.hazymorning.klangbild.data.ha.ArtworkUrls
import io.github.hazymorning.klangbild.data.ha.HaAuth
import io.github.hazymorning.klangbild.data.ha.HaUrls
import io.github.hazymorning.klangbild.domain.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.LinkedHashMap

/**
 * Fetches and decodes covers. Tries the upgraded (larger) URL first, then the original, then
 * Home Assistant's own proxy. The access token is only attached for Home Assistant's own host.
 * Returns null when there is no cover at all; the scene then paints a Chladni figure.
 */
class ArtworkLoader(
    private val http: OkHttpClient,
    /** For Home Assistant's own pictures, which carry the token: follows no redirect. */
    private val tokenHttp: OkHttpClient,
    /** How the current connection signs in to Home Assistant, if there is one. */
    private val haAuth: () -> HaAuth?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > MAX_DECODED
    }

    /** Guessed addresses that gave no picture lately, and when: they are not asked again for a while. */
    private val failed = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_FAILED
    }

    /** A few downloads and decodes at a time: a whole cover flow at once would hold every source in memory. */
    private val slots = Semaphore(MAX_LOADS)

    /**
     * The cover of [track], from the first address that gives one. Canceling stops the
     * download under way, so a skipped song's cover is not fetched to the end. A larger
     * rendition guessed from the address gets a shorter time than the address itself, and a
     * guess that failed is not tried again for [RETRY_FAILED_MS].
     */
    suspend fun load(track: TrackInfo): Bitmap? {
        val originals = listOfNotNull(track.artworkUrl, track.artworkFallbackUrl).toSet()
        for (url in ArtworkUrls.candidates(track.artworkUrl, track.artworkFallbackUrl)) {
            synchronized(cache) { cache[url] }?.let { return it }
            if (synchronized(failed) { failed[url] }?.let { now() - it < RETRY_FAILED_MS } == true) continue
            val timeout = if (url in originals) CALL_TIMEOUT_MS else UPGRADE_TIMEOUT_MS
            val bmp = slots.withPermit {
                val bytes = try {
                    fetch(url, timeout)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                bytes?.let { withContext(Dispatchers.Default) { decode(it) } }
            }
            if (bmp == null) {
                // Only guesses are remembered: the addresses Home Assistant gives may work again any moment.
                if (url !in originals) synchronized(failed) { failed[url] = now() }
                continue
            }
            synchronized(cache) { cache[url] = bmp }
            return bmp
        }
        return null
    }

    /** Forgets the decoded covers (the screen is gone, or memory is short). */
    fun clear() = synchronized(cache) { cache.clear() }

    private suspend fun fetch(url: String, timeoutMs: Long): ByteArray? {
        val builder = Request.Builder().url(url)
        val auth = haAuth()
        val client = if (auth != null && HaUrls.isHaHost(auth.baseUrl, url)) {
            val token = try {
                auth.accessToken()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            token?.let { builder.header("Authorization", "Bearer $it") }
            tokenHttp
        } else {
            http
        }
        return client.fetch(builder.build(), timeoutMs) { response ->
            if (!response.isSuccessful) return@fetch null
            // A cover is a few megabytes at most; anything far larger is not read at all.
            if (response.body.contentLength() > MAX_BYTES) return@fetch null
            val source = response.body.source()
            if (source.request(MAX_BYTES + 1)) return@fetch null
            source.readByteArray()
        }
    }

    companion object {
        /** Larger sources are subsampled on decode; nothing on screen needs more. */
        const val MAX_DECODE_PX = 2048

        /** The most a cover download may weigh (bytes). */
        const val MAX_BYTES = 16L * 1024 * 1024

        /** One address gets this long, reading included. */
        const val CALL_TIMEOUT_MS = 8_000L

        /** A larger rendition guessed from the address gets this long, so the address itself still has time within the scene's wait. */
        const val UPGRADE_TIMEOUT_MS = 2_500L

        /** An address that gave no picture is not asked again for this long. */
        const val RETRY_FAILED_MS = 10 * 60_000L

        /** Decoded covers kept, failed addresses remembered, downloads at once. */
        private const val MAX_DECODED = 4
        private const val MAX_FAILED = 64
        private const val MAX_LOADS = 2

        fun decode(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val w = bounds.outWidth
            val h = bounds.outHeight
            var sample = 1
            while (minOf(w, h) / (sample * 2) >= MAX_DECODE_PX / 2 && maxOf(w, h) / sample > MAX_DECODE_PX) sample *= 2
            // However odd its shape, no picture takes more memory than a square of MAX_DECODE_PX.
            while ((w / sample).toLong() * (h / sample) > MAX_DECODE_PX.toLong() * MAX_DECODE_PX) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
}
