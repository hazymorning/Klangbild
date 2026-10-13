package io.github.hazymorning.klangbild.data.ha

import io.github.hazymorning.klangbild.data.fetch
import io.github.hazymorning.klangbild.json.string
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** How Klangbild signs in to Home Assistant. */
sealed interface HaCredentials {
    /** A long-lived access token made in the user's Home Assistant profile. */
    data class LongLived(val token: String) : HaCredentials

    /**
     * Signed in through Home Assistant's own login page (authorization code grant). The
     * [refreshToken] gets short-lived access tokens; Home Assistant only accepts it together
     * with the exact [clientId] it was made for.
     */
    data class SignedIn(val refreshToken: String, val clientId: String) : HaCredentials
}

/** Home Assistant refused the credentials: signing in again (or a new token) is the only way on. */
class HaAuthRejectedException(message: String) : IOException(message)

/** The tokens of a sign-in: the refresh token to keep and a first access token. */
data class HaTokens(val accessToken: String, val refreshToken: String?, val expiresInS: Int)

/**
 * Access tokens for one Home Assistant at [baseUrl]
 * (https://developers.home-assistant.io/docs/auth_api). A long-lived token is used as it is.
 * After a sign-in, a 30-minute access token is fetched with the refresh token and kept until
 * shortly before it expires.
 *
 * Every 4xx answer of Home Assistant's token endpoint counts there as a failed login (a
 * notification, and a ban where one is configured), so a refused refresh token is never
 * tried again: the credentials are rejected until someone signs in anew.
 */
class HaAuth(
    private val http: OkHttpClient,
    val baseUrl: String,
    val credentials: HaCredentials,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    @Volatile private var cached: String? = (credentials as? HaCredentials.LongLived)?.token
    @Volatile private var expiresAt = Long.MAX_VALUE
    @Volatile private var rejected: String? = null

    /** Whether a token that stopped working can be replaced by a fresh one. */
    val refreshable: Boolean get() = credentials is HaCredentials.SignedIn

    /**
     * An access token that is valid for at least another minute. Throws
     * [HaAuthRejectedException] when Home Assistant refused the credentials and an IOException
     * when it cannot be reached.
     */
    suspend fun accessToken(): String = lock.withLock {
        rejected?.let { throw HaAuthRejectedException(it) }
        cached?.takeIf { now() < expiresAt - MARGIN_MS }?.let { return it }
        val signedIn = credentials as? HaCredentials.SignedIn ?: return cached!!
        val tokens = tokenRequest(
            http,
            baseUrl,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", signedIn.refreshToken)
                .add("client_id", signedIn.clientId)
                .build(),
        ) { reason -> rejected = reason }
        cached = tokens.accessToken
        expiresAt = now() + tokens.expiresInS * 1000L
        tokens.accessToken
    }

    /** The last access token, if any, without asking Home Assistant (it may have expired). */
    fun lastToken(): String? = cached

    /** The access token [token] was refused: the next one is fetched anew (after a sign-in). */
    fun invalidate(token: String) {
        if (refreshable && cached == token) {
            cached = null
            expiresAt = 0L
        }
    }

    companion object {
        /** An access token is replaced this long before it expires. */
        const val MARGIN_MS = 60_000L

        /** The token endpoint's answers are small; anything larger is not one. */
        private const val MAX_ANSWER_BYTES = 64L * 1024

        /** One call to the token endpoint gets this long. */
        private const val CALL_TIMEOUT_MS = 15_000L

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Exchanges the authorization code [code] from Home Assistant's login page for tokens.
         * [clientId] must be the one the login page was opened with.
         */
        suspend fun exchangeCode(http: OkHttpClient, baseUrl: String, code: String, clientId: String): HaTokens =
            tokenRequest(
                http,
                baseUrl,
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("client_id", clientId)
                    .build(),
            ) { }

        /**
         * Revokes [refreshToken] and every access token it gave (when Klangbild signs in anew or
         * goes over to another Home Assistant). Home Assistant always answers 200.
         */
        suspend fun revoke(http: OkHttpClient, baseUrl: String, refreshToken: String) {
            val request = Request.Builder()
                .url("$baseUrl/auth/revoke")
                .post(FormBody.Builder().add("token", refreshToken).build())
                .build()
            try {
                http.fetch(request, CALL_TIMEOUT_MS) { }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort: an unreachable Home Assistant keeps the token until it expires unused.
            }
        }

        private suspend fun tokenRequest(
            http: OkHttpClient,
            baseUrl: String,
            body: FormBody,
            onRejected: (String) -> Unit,
        ): HaTokens {
            val request = Request.Builder().url("$baseUrl/auth/token").post(body).build()
            return http.fetch(request, CALL_TIMEOUT_MS) { response ->
                val text = response.body.source().let { src ->
                    if (src.request(MAX_ANSWER_BYTES)) throw IOException("Answer too large") else src.readUtf8()
                }
                if (response.code in 400..499) {
                    val reason = runCatching { (json.parseToJsonElement(text) as? JsonObject)?.string("error") }.getOrNull()
                        ?: "HTTP ${response.code}"
                    onRejected(reason)
                    throw HaAuthRejectedException(reason)
                }
                if (!response.isSuccessful) throw IOException("Home Assistant answered ${response.code}")
                val o = json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Unexpected answer")
                HaTokens(
                    accessToken = o.string("access_token") ?: throw IOException("No access token"),
                    refreshToken = o.string("refresh_token"),
                    expiresInS = (o["expires_in"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_EXPIRY_S,
                )
            }
        }

        /** Home Assistant's access tokens last 30 minutes. */
        private const val DEFAULT_EXPIRY_S = 1_800

    }
}
