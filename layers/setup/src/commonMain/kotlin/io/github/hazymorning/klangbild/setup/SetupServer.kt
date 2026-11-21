package io.github.hazymorning.klangbild.setup

import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.coroutines.flow.StateFlow

/** What the TV shows while setup is open. */
data class SetupInfo(
    /** Address to type in, e.g. `http://192.168.1.23:8765`; null without network. */
    val url: String?,
    val code: String,
    /** QR modules (true = dark), square. Encodes [url] with the code. */
    val qr: Array<BooleanArray>?,
)

/**
 * Serves the setup page on the local network while the setup screen is visible. Requests must
 * name the page's own address as their host (no other site can rebind its name to the TV), a call
 * to the API from another origin is refused, and so is one that is not JSON (a form on another page
 * cannot send it unasked). Without an address of its own (no home network) nothing is served. A
 * request that has arrived is answered even when serving stops meanwhile: saving ends the setup,
 * and its answer must still reach the phone. Once [stop] has returned, the port takes no
 * connection.
 */
interface SetupServer {
    val info: StateFlow<SetupInfo?>

    /** The page's address while it is served (`http://192.168.1.23:8765`). */
    val origin: String?

    /**
     * Serves the page, with a new code. Without an address of its own there is no page to serve:
     * the TV says there is no network, and the next call tries again.
     */
    fun start()

    /**
     * Stops serving: the listening socket closes, and so does every connection whose request is
     * still arriving. A request that has arrived is still answered (within its deadline), and a
     * sign-in never saved is revoked. A new code no longer reaches the screen.
     */
    fun stop()

    companion object {
        /** The ports the page is served on, the first that is free; else any. */
        val PORTS = listOf(8765, 8766, 8767, 8768, 8769)

        /**
         * Why [req] is refused, or null: it must be for the page's own address [own]; a call to the
         * API must come from the page itself and be JSON.
         */
        internal fun refusal(req: MiniHttp.Request, own: String, t: Texts): MiniHttp.Response? {
            val forbidden = MiniHttp.Response.text(403, t[Strings.setupErrorNotAllowed])
            if (req.headers["host"] != own.removePrefix("http://")) return forbidden
            if (req.method == "POST") {
                val from = req.headers["origin"]
                if (from != null && from != own) return forbidden
                if (req.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") return forbidden
            }
            return null
        }
    }
}
