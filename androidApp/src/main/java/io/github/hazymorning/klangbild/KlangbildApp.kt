package io.github.hazymorning.klangbild

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.core.content.FileProvider
import io.github.hazymorning.klangbild.app.AppGraph
import io.github.hazymorning.klangbild.app.Platform
import io.github.hazymorning.klangbild.config.KeystoreSecretBox
import io.github.hazymorning.klangbild.config.ValueStore
import io.github.hazymorning.klangbild.config.settingsValues
import io.github.hazymorning.klangbild.data.AndroidLog
import io.github.hazymorning.klangbild.data.AndroidPictureDecoder
import io.github.hazymorning.klangbild.data.ha.CleartextGuard
import io.github.hazymorning.klangbild.data.httpClient
import io.github.hazymorning.klangbild.setup.SetupHandler
import io.github.hazymorning.klangbild.setup.SocketSetupServer
import io.github.hazymorning.klangbild.speaker.speakerClient
import io.github.hazymorning.klangbild.speaker.speakerValues
import io.github.hazymorning.klangbild.text.PlatformLanguageStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What the app needs from Android: OkHttp's connections, DataStore, the keystore and the rest.
 * [KlangbildApp] makes one per process, since each DataStore file may be opened only once.
 */
class AndroidPlatform(context: Context) : Platform {
    private val app = context.applicationContext

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        // Covers come again (the album's next song, the cover flow): kept on disk as the servers allow.
        .cache(Cache(File(app.cacheDir, "http"), HTTP_CACHE_BYTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // No ping interval: Home Assistant's own pings keep its WebSocket alive, and OkHttp
        // answers a server's pings itself.
        .addInterceptor(CleartextGuard())
        // The guard checks every redirect hop, too: a picture link must not lead to plain HTTP outside the home.
        .addNetworkInterceptor(CleartextGuard())
        .build()

    /** For requests that carry a token: no redirects, so a token never goes to another host than its own. */
    private val tokenOkHttp: OkHttpClient = okHttp.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        // What a token fetches is not written to disk.
        .cache(null)
        .build()

    override val http: HttpClient = httpClient(
        OkHttp.create {
            preconfigured = okHttp
            // Ktor's engine turns OkHttp's redirects off; pictures follow them, the guard checking each hop.
            config {
                followRedirects(true)
                followSslRedirects(true)
            }
        },
    )

    override val tokenHttp: HttpClient = httpClient(
        OkHttp.create {
            preconfigured = tokenOkHttp
            // A WebSocket given up (Home Assistant's after a missed pong) still closes normally; a
            // server that doesn't answer is cut off after a second instead of OkHttp's minute.
            config { webSocketCloseTimeout(1, TimeUnit.SECONDS) }
        },
    )

    /** Play here's connection, a token's too, made when it is first needed. */
    val speakerHttp: HttpClient by lazy { speakerClient(tokenOkHttp) }

    override val settings: ValueStore = settingsValues(app)

    override val secrets = KeystoreSecretBox()

    private val speaker by lazy { speakerValues(app) }

    override fun speakerValues(): ValueStore = speaker

    override val languages = PlatformLanguageStore(app)

    override val pictures = AndroidPictureDecoder

    override fun setupServer(scope: CoroutineScope, handler: SetupHandler) = SocketSetupServer(scope, handler)

    override val version: String = BuildConfig.VERSION_NAME

    /** The new version, kept until Android's installer has read it. */
    private val update = File(File(app.cacheDir, "updates"), "klangbild.apk")

    init {
        // A download left from an earlier day goes.
        if (System.currentTimeMillis() - update.lastModified() > UPDATE_KEPT_MS) update.delete()
    }

    /**
     * Writes the new version where the file provider hands it to Android's installer, which asks
     * the person (and, the first time, for leave to install apps from Klangbild).
     */
    override suspend fun install(apk: ByteReadChannel): Boolean {
        withContext(Dispatchers.IO) {
            update.parentFile?.mkdirs()
            update.outputStream().use { apk.copyTo(it) }
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(app, "${app.packageName}.updates", update), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return withContext(Dispatchers.Main) {
            try {
                app.startActivity(view)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }

    override val log = AndroidLog
}

class KlangbildApp : Application() {
    lateinit var platform: AndroidPlatform
        private set

    lateinit var graph: AppGraph
        private set

    /**
     * A new language (chosen in the system's settings, or the system's own), and the system's
     * 12 or 24 hour clock: the texts are made anew, on the screen and on the setup page.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        graph.localizer.refresh()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Nothing shows in the background: the covers and the scenes go, and are made again on return.
        if (level >= TRIM_MEMORY_UI_HIDDEN) graph.controller.releaseMemory()
    }

    override fun onCreate() {
        super.onCreate()
        platform = AndroidPlatform(this)
        graph = AppGraph(platform)
        val refresh = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = graph.localizer.refresh()
        }
        // Both are protected broadcasts that only the system sends. Not ContextCompat: below API 33
        // it needs a signature permission of the app's own (see the manifest).
        val system = IntentFilter().apply {
            // Sent when the system's 12 or 24 hour setting changes, too.
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(refresh, system, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(refresh, system)
        }
        // When the network comes back (Wi-Fi hiccup), reconnect right away instead of
        // waiting for the backoff timer.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    graph.controller.networkAvailable()
                    graph.speakerHub.speaker?.retryNow()
                }
            },
        )
    }
}

val Context.app: KlangbildApp get() = applicationContext as KlangbildApp

val Context.graph: AppGraph get() = app.graph

/** Room for covers on disk. */
private const val HTTP_CACHE_BYTES = 32L * 1024 * 1024

/** A new version downloaded this long ago (ms) has been installed or turned down. */
private const val UPDATE_KEPT_MS = 24 * 3_600_000L
