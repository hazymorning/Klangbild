package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.config.SecretBox
import io.github.hazymorning.klangbild.config.SettingsStore
import io.github.hazymorning.klangbild.config.ValueStore
import io.github.hazymorning.klangbild.data.Log
import io.github.hazymorning.klangbild.data.PictureDecoder
import io.github.hazymorning.klangbild.data.Releases
import io.github.hazymorning.klangbild.setup.SetupHandler
import io.github.hazymorning.klangbild.setup.SetupServer
import io.github.hazymorning.klangbild.speaker.SpeakerHub
import io.github.hazymorning.klangbild.speaker.SpeakerStore
import io.github.hazymorning.klangbild.text.LanguageStore
import io.github.hazymorning.klangbild.text.Localizer
import io.ktor.client.HttpClient
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlin.time.Clock

/** What the app needs from the platform it runs on. [AppGraph] makes the rest of it from these. */
interface Platform {
    /** For requests without a token: covers from other hosts and GitHub's releases. */
    val http: HttpClient

    /**
     * For requests that carry a token: it follows no redirects and keeps nothing, so a token only
     * ever goes to the address it was issued for.
     */
    val tokenHttp: HttpClient

    /** Where the settings are kept. */
    val settings: ValueStore

    /** Seals the tokens the settings keep. */
    val secrets: SecretBox

    /** Where Play here keeps its key and pairings, opened when it is first needed. */
    fun speakerValues(): ValueStore

    /** Where the language chosen is kept, and the system's own languages and clock. */
    val languages: LanguageStore

    /** How covers are decoded. */
    val pictures: PictureDecoder

    /** The setup page's server for [handler], running in [scope]. */
    fun setupServer(scope: CoroutineScope, handler: SetupHandler): SetupServer

    /** The version installed, as the platform names it ("2026.11.0" for a release's). */
    val version: String

    /** Installs the app [apk] holds, read as it downloads, once the person agrees; false when it can't be handed over. */
    suspend fun install(apk: ByteReadChannel): Boolean

    val log: Log
}

/** The app's parts, made once from what the platform gives. The app is small enough not to need a DI framework. */
class AppGraph(private val platform: Platform) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(platform.settings, platform.secrets)

    /** The words in the language chosen: the system's, or one from the menu. */
    val localizer = Localizer(platform.languages)

    /** This TV's speaker while it plays as one. */
    val speakerHub = SpeakerHub()

    /** Klangbild's own new versions, from its releases on GitHub. */
    private val releases = Releases(platform.http)
    private val updates = Updates(
        scope, platform.settings, platform.version,
        latest = releases::latest,
        install = { tag -> releases.download(tag, platform::install) },
        now = { Clock.System.now().toEpochMilliseconds() },
        log = platform.log,
    )

    val controller = NowPlayingController(
        scope, platform.http, platform.tokenHttp, settings, localizer, platform.pictures, platform::setupServer, platform.log,
        speaker = speakerHub.status,
        pairingToken = { speakerHub.speaker?.pairingToken },
        ownSound = { speakerHub.speaker?.sound },
        updates = updates,
    )

    /** What the speaker keeps (its key, its pairings), opened when it is first needed. */
    val speakerStore: SpeakerStore by lazy { SpeakerStore(platform.speakerValues(), platform.secrets) }

    init {
        // What an earlier version stored is brought up to date now rather than on the next change.
        scope.launch {
            try {
                settings.migrate()
            } catch (e: IOException) {
                platform.log.warn("Settings: not brought up to date (${e::class.simpleName})")
            }
        }
    }
}
