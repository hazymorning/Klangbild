package io.github.hazymorning.klangbild.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [server] answered with an HTTP status that is neither success nor a refused token. */
class HttpStatusException(server: String, val code: Int) : IOException("$server answered $code")

/**
 * Runs [request] and reads its answer with [read] (off the caller's thread). Cancelable:
 * canceling the coroutine cancels the request. A blocking `execute()` would carry on to its
 * own timeouts, and a cover nobody waits for any more would still be downloaded. The whole
 * call, reading included, ends after [timeoutMs] at the latest.
 */
suspend fun <T> OkHttpClient.fetch(request: Request, timeoutMs: Long, read: (Response) -> T): T =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWith(Result.failure(e))

                override fun onResponse(call: Call, response: Response) = cont.resumeWith(runCatching { response.use(read) })
            },
        )
    }
