package io.github.hazymorning.klangbild.speaker

import io.github.hazymorning.klangbild.data.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The speaker's client: [okHttp], which pings the server every [SpeakerConnection.PING_MS]. */
fun speakerClient(okHttp: OkHttpClient): HttpClient =
    httpClient(OkHttp.create { preconfigured = okHttp.newBuilder().pingInterval(SpeakerConnection.PING_MS, TimeUnit.MILLISECONDS).build() })
