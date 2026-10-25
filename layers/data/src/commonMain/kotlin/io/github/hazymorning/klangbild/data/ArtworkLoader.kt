package io.github.hazymorning.klangbild.data

import androidx.compose.ui.graphics.ImageBitmap
import io.github.hazymorning.klangbild.data.ha.ArtworkUrls
import io.github.hazymorning.klangbild.data.ha.HaAuth
import io.github.hazymorning.klangbild.data.ha.HaUrls
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.math.RecentMap
import io.ktor.client.HttpClient
import io.ktor.http.contentLength
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Turns the bytes of a picture into a bitmap, or null when they are none. Larger sources are
 * subsampled on decode: by powers of two, while both sides stay at least half of
 * [ArtworkLoader.MAX_DECODE_PX] and the longer one is above it, then further until the picture
 * holds no more pixels than a square of [ArtworkLoader.MAX_DECODE_PX].
 */
fun interface PictureDecoder {
    fun decode(bytes: ByteArray): ImageBitmap?
}

/**
 * Fetches and decodes covers. Tries the upgraded (larger) URL first, then the original, then
 * Home Assistant's own proxy. The access token is only attached for Home Assistant's own host.
 * Returns null when there is no cover at all; the scene then makes one of its own.
 */
class ArtworkLoader(
    private val http: HttpClient,
    /** For Home Assistant's own pictures, which carry the token: follows no redirect. */
    private val tokenHttp: HttpClient,
    /** How the current connection signs in to Home Assistant, if there is one. */
    private val haAuth: () -> HaAuth?,
    private val decoder: PictureDecoder,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = SynchronizedObject()
    private val cache = RecentMap<String, ImageBitmap>(MAX_DECODED)

    /** Guessed addresses that gave no picture lately, and when: they are not asked again for a while. */
    private val failed = RecentMap<String, Long>(MAX_FAILED)

    /** A few downloads and decodes at a time: a whole cover flow at once would hold every source in memory. */
    private val slots = Semaphore(MAX_LOADS)

    /**
     * The cover of [track], from the first address that gives one. Canceling stops the
     * download under way, so a skipped song's cover is not fetched to the end. A larger
     * rendition guessed from the address gets a shorter time than the address itself, and a
     * guess that failed is not tried again for [RETRY_FAILED_MS].
     */
    suspend fun load(track: TrackInfo): ImageBitmap? {
        val originals = listOfNotNull(track.artworkUrl, track.artworkFallbackUrl).toSet()
        for (url in ArtworkUrls.candidates(track.artworkUrl, track.artworkFallbackUrl)) {
            synchronized(lock) { cache[url] }?.let { return it }
            if (synchronized(lock) { failed[url] }?.let { now() - it < RETRY_FAILED_MS } == true) continue
            val timeout = if (url in originals) CALL_TIMEOUT_MS else UPGRADE_TIMEOUT_MS
            val bmp = slots.withPermit {
                val bytes = try {
                    fetch(url, timeout)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                bytes?.let { withContext(Dispatchers.Default) { decoder.decode(it) } }
            }
            if (bmp == null) {
                // Only guesses are remembered: the addresses Home Assistant gives may work again any moment.
                if (url !in originals) synchronized(lock) { failed[url] = now() }
                continue
            }
            synchronized(lock) { cache[url] = bmp }
            return bmp
        }
        return null
    }

    /** Forgets the decoded covers (the screen is gone, or memory is short). */
    fun clear() = synchronized(lock) { cache.clear() }

    private suspend fun fetch(url: String, timeoutMs: Long): ByteArray? {
        val auth = haAuth()
        val token: String?
        val client = if (auth != null && HaUrls.isHaHost(auth.baseUrl, url)) {
            token = try {
                auth.accessToken()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            tokenHttp
        } else {
            token = null
            http
        }
        return client.fetch(
            timeoutMs,
            {
                address(url)
                token?.let { bearer(it) }
            },
        ) { response ->
            if (!response.isSuccessful) return@fetch null
            // A cover is a few megabytes at most; anything far larger is not read at all.
            if ((response.contentLength() ?: -1L) > MAX_BYTES) return@fetch null
            response.bodyBytes(MAX_BYTES + 1).takeIf { it.size <= MAX_BYTES }
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
    }
}
