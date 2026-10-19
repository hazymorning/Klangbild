package io.github.hazymorning.klangbild.app

import android.util.Log
import io.github.hazymorning.klangbild.R
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.AppearanceMode
import io.github.hazymorning.klangbild.config.BackgroundMode
import io.github.hazymorning.klangbild.config.DisplayOptions
import io.github.hazymorning.klangbild.config.LaunchExtras
import io.github.hazymorning.klangbild.config.MenuRow
import io.github.hazymorning.klangbild.config.MenuSection
import io.github.hazymorning.klangbild.config.MiniPlayer
import io.github.hazymorning.klangbild.config.QuickActions
import io.github.hazymorning.klangbild.config.QuickRow
import io.github.hazymorning.klangbild.config.SessionOptions
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SettingsStore
import io.github.hazymorning.klangbild.config.SleepAction
import io.github.hazymorning.klangbild.config.LandscapeRequest
import io.github.hazymorning.klangbild.config.VolumeDisplay
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.data.NowPlayingSource
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.data.ha.HaAuth
import io.github.hazymorning.klangbild.data.ha.HaConfig
import io.github.hazymorning.klangbild.data.ha.HaSource
import io.github.hazymorning.klangbild.data.ma.MaClient
import io.github.hazymorning.klangbild.domain.ChapterPlace
import io.github.hazymorning.klangbild.domain.CoverStrip
import io.github.hazymorning.klangbild.domain.FieldClock
import io.github.hazymorning.klangbild.domain.FlipGuard
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.domain.MediaKind
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Progress
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.ScreenDecision
import io.github.hazymorning.klangbild.domain.ScreenStateMachine
import io.github.hazymorning.klangbild.domain.SmoothedPosition
import io.github.hazymorning.klangbild.domain.SongLyrics
import io.github.hazymorning.klangbild.domain.StageView
import io.github.hazymorning.klangbild.domain.SunClock
import io.github.hazymorning.klangbild.domain.Timing
import io.github.hazymorning.klangbild.domain.TrackInfo
import io.github.hazymorning.klangbild.domain.ValueText
import io.github.hazymorning.klangbild.domain.LandscapePolicy
import io.github.hazymorning.klangbild.data.LightBridge
import io.github.hazymorning.klangbild.lights.LightSync
import io.github.hazymorning.klangbild.render.ArtworkScene
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupHandler
import io.github.hazymorning.klangbild.setup.SetupInfo
import io.github.hazymorning.klangbild.setup.SetupServer
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.Localizer
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.landscape.LandscapeClock
import io.github.hazymorning.klangbild.landscape.Weather
import io.github.hazymorning.klangbild.landscape.WeatherGlide
import io.github.hazymorning.klangbild.landscape.WeatherMood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger

/** A scene that is ready to be shown for a track, with the song's [lyrics] while they take the text column. */
data class StageData(val track: TrackInfo, val scene: ArtworkScene, val lyrics: Lyrics? = null)

/** A card of the cover flow with its scene, ready to be shown. */
data class FlowEntry(val id: Long, val track: TrackInfo, val scene: ArtworkScene) {
    val stage: StageData get() = StageData(track, scene)
}

/**
 * The cover flow as far as it is ready: cards in play order, the one at [current] in the middle,
 * and the middle song's [lyrics] while they take the text column.
 */
data class FlowData(val cards: List<FlowEntry>, val current: Int, val lyrics: Lyrics? = null) {
    val middle: FlowEntry get() = cards[current]

    /** The middle card as the stage shows it: with its words. */
    val stage: StageData get() = StageData(middle.track, middle.scene, lyrics)
}

/** The settings menu while it is open: its lines and the one the cursor is on. */
data class MenuState(
    val rows: List<MenuRow>,
    val cursor: Int,
    /** Up or down is held: the list runs on, and the explanation waits until the key is let go. */
    val held: Boolean = false,
    /** The group whose settings show; null while the groups themselves show. */
    val section: MenuSection? = null,
)

/** The quick menu while it is open: its lines and the one the cursor is on. */
data class QuickState(val rows: List<QuickRow>, val cursor: Int)

/** Everything the UI renders. */
data class UiState(
    val screen: Screen = Screen.Rest(RestReason.CONNECTING),
    val keepScreenOn: Boolean = true,
    val asleep: Boolean = false,
    val sleepAction: SleepAction = SleepAction.RELEASE,
    /**
     * The latest scene that is ready; may lag behind [screen] while a new cover loads. It stays
     * through a switch to Cover Flow until the cards' first scene is made.
     */
    val stage: StageData? = null,
    /** How the stage shows what plays. */
    val view: StageView = StageView.SINGLE,
    /** The cover flow (in [StageView.FLOW]); like [stage] it may lag behind [screen], and stays through a switch away. */
    val flow: FlowData? = null,
    /** Light wall by day, dark at night (or as set). */
    val appearance: Appearance = Appearance.DARK,
    /** What the stage shows (clock, progress, album and so on) and how strong its light is. */
    val display: DisplayOptions = DisplayOptions(),
    val source: SourceState = SourceState(LinkState.CONNECTING),
    val session: SessionOptions = SessionOptions(),
    val entityId: String? = null,
    /** Address, code and QR modules while the setup screen is open. */
    val setup: SetupInfo? = null,
    /** The settings menu, null while it is closed. */
    val menu: MenuState? = null,
    /** The quick menu, null while it is closed. */
    val quick: QuickState? = null,
    /** The volume just changed: the status line shows it for a moment. */
    val volumeFlash: Boolean = false,
    /** The chapter of the book or episode on stage, while it has chapters. */
    val chapter: ChapterPlace? = null,
    /** When the sleep timer pauses the music (wall clock), while it is set. */
    val sleepAt: Long? = null,
    /** The landscape, while it has the screen. */
    val landscape: LandscapeUi? = null,
    /** The landscape shows or is on its way: its picture is baked ahead, for its label on this side. */
    val landscapeAhead: MiniPlayer? = null,
    /** The words in the language shown; null leaves them to the screen's own context. */
    val texts: Texts? = null,
    /** The values of one's own in words, as far as Home Assistant can tell them ("Outside 12 °C"). */
    val values: List<String> = emptyList(),
    /** The link of the party playing here, while its code shows where the cover stands (the quick settings' Party code). */
    val partyCode: String? = null,
    /** The quick settings have never been opened: the stage may tell how (arrow down) for a moment. */
    val downHint: Boolean = false,
)

/** The landscape while it shows: what plays (for the mini player), where the label sits, and the weather it shows. */
data class LandscapeUi(
    val track: TrackInfo?,
    val status: PlayerStatus?,
    val miniPlayer: MiniPlayer,
    val weather: WeatherGlide = WeatherGlide.still(Weather.FAIR),
)

/**
 * The app's single source of truth. It runs the Home Assistant source and the screen state
 * machine (on every update and once a second) and has the scenes (cover, palette, light) made
 * off the main thread: the next track's ahead of time, in Cover Flow the whole strip's. The
 * menus, seeking and the sleep timer are parts of their own ([MenuControl], [QuickControl],
 * [Seeking], [SleepControl]); [LightTargets] says what the lights show.
 */
class NowPlayingController(
    private val scope: CoroutineScope,
    /** For pictures from anywhere: requests carry no credentials but to Home Assistant's own host. */
    private val http: OkHttpClient,
    /** For requests that carry a token: they never follow a redirect, so a token never reaches another host. */
    private val tokenHttp: OkHttpClient,
    private val settingsStore: SettingsStore,
    /** The words in the language chosen, and where the choice is kept. */
    private val localizer: Localizer,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val settings = MutableStateFlow(Settings())
    private val session = MutableStateFlow(SessionOptions())

    /** The stored settings have been taken in: until then [settings] holds only the defaults. */
    @Volatile private var loaded = false

    /** Read from coroutines and the main thread as well; written under the lock. */
    @Volatile private var source: NowPlayingSource? = null
    private var sourceKey: String? = null
    private var sourceJob: Job? = null
    private var ticker: Job? = null
    private var settingsJob: Job? = null
    private var machine = ScreenStateMachine()
    private val smooth = SmoothedPosition()
    private var lastReportKey: Any? = null

    /** When the landscape takes the screen ([LandscapePolicy]), and the time its sky shows. */
    private val landscapePolicy = LandscapePolicy()
    val landscapeClock = LandscapeClock(now)

    /** The weather the landscape shows, while it does: the picture and the lights read the same. */
    private var landscapeWeather: WeatherGlide? = null

    /** The next chapter's start on the wall clock, so the stage turns to it on time. */
    @Volatile private var chapterEndsAt: Long? = null

    /** The color field's time, shared by the screen and the lights. */
    private val fieldClock = FieldClock(now)

    /** The lights in the cover's colors (through Home Assistant). */
    private val lightSync = LightSync(scope, now)
    private val lightTargets = LightTargets(fieldClock, landscapeClock, now)

    private val host = object : ControllerHost {
        override val lock: Any get() = this@NowPlayingController
        override val scope: CoroutineScope get() = this@NowPlayingController.scope
        override val now: () -> Long get() = this@NowPlayingController.now
        override val settings: Settings get() = this@NowPlayingController.settings.value
        override val ui: UiState get() = _ui.value
        override val source: NowPlayingSource? get() = this@NowPlayingController.source
        override val texts: Texts get() = localizer.texts.value
        override var language: AppLanguage
            get() = localizer.language
            set(value) {
                // Applies at once everywhere: the menus are built anew from the new texts.
                localizer.setLanguage(value)
            }
        override fun recompute() = this@NowPlayingController.recompute()
        override fun showMenu() = this@NowPlayingController.showMenu()
        override fun openMenu(): Unit = this@NowPlayingController.openMenu()
        override fun applySettings(updated: Settings) = this@NowPlayingController.applySettings(updated)
        override fun send(command: RemoteCommand) = this@NowPlayingController.send(command)
        override fun requestSetup(show: Boolean) = this@NowPlayingController.requestSetup(show)
        override fun showLandscape() = this@NowPlayingController.showLandscape()
        override fun hideLandscape(): Boolean = this@NowPlayingController.hideLandscape()
    }
    private val sleep = SleepControl(host)
    private val menu = MenuControl(host, sleep)
    private val quick = QuickControl(host)
    private val seeking = Seeking(host)

    /** The color field's time now (seconds): it runs while music plays, at the motion setting's speed. */
    fun fieldTime(): Double = fieldClock.now()

    /** How the current connection signs in to Home Assistant, while there is one. */
    @Volatile private var auth: HaAuth? = null

    private val artwork = ArtworkLoader(http, tokenHttp, { auth })
    private val scenes = Scenes(scope, artwork)
    private val strip = CoverStrip()

    private var viewers = 0

    private val setupHandler: SetupHandler = SetupHandler(
        settings = { settings.value },
        texts = { localizer.texts.value },
        discover = { url, credentials ->
            withContext(Dispatchers.IO) { SetupServer.discover(tokenHttp, url, credentials, localizer.texts.value) }
        },
        save = { updated ->
            // Leaving setup: the new settings start a fresh connection.
            session.value = session.value.copy(showSetup = false)
            synchronized(this@NowPlayingController) { machine.requestSetup(false) }
            takeAndWrite(updated, "setup page")
        },
        // Home Assistant knows the TV by its setup page's address; its login page sends the phone back there.
        clientId = { setupServer.origin?.let { "$it/" } },
        exchange = { url, code, clientId -> HaAuth.exchangeCode(tokenHttp, url, code, clientId) },
        revoke = { url, refreshToken -> HaAuth.revoke(tokenHttp, url, refreshToken) },
        checkMa = { url, token -> MaClient(tokenHttp, url, token).check() },
        maGuess = {
            val track = _ui.value.source.player?.track
            MaClient.addressIn(track?.artworkUrl) ?: MaClient.addressIn(track?.artworkFallbackUrl)
        },
    )
    private val setupServer: SetupServer = SetupServer(scope, setupHandler)
    private var setupJob: Job? = null

    /** A screen (activity or dream) starts showing the controller. */
    @Synchronized
    fun attach(spec: ScreenSpec) {
        viewers++
        scenes.frame = scenes.frame.copy(screen = spec)
        if (viewers == 1) start()
    }

    @Synchronized
    fun detach() {
        viewers = (viewers - 1).coerceAtLeast(0)
        if (viewers == 0) stop()
    }

    private var stoppedAt = 0L
    private var releaseJob: Job? = null

    private fun start() {
        releaseJob?.cancel()
        // A sleep timer outlives a hand-over between the app and the screensaver, not a closed app.
        if (stoppedAt > 0 && now() - stoppedAt > HANDOVER_MS) sleep.clear()
        settingsJob = scope.launch {
            settingsStore.settings.collect { s ->
                // While the menu's own changes are being written, it already shows them.
                if (localEdits.get() != 0) return@collect
                // A late echo of an older state (the very first read, overtaken by the launch's
                // extras) never undoes a connection the store holds now.
                if (!s.isConfigured && settings.value.isConfigured && settingsStore.settings.first().isConfigured) return@collect
                onSettings(s)
                loaded = true
            }
        }
        ticker = scope.launch {
            while (isActive) {
                delay(tickDelay())
                recompute()
            }
        }
        setupJob = scope.launch {
            setupServer.info.collect { info -> synchronized(this@NowPlayingController) { _ui.value = _ui.value.copy(setup = info) } }
        }
        textsJob = scope.launch { localizer.texts.collect { recompute() } }
    }

    private var textsJob: Job? = null

    private fun stop() {
        settingsJob?.cancel()
        textsJob?.cancel()
        ticker?.cancel()
        setupJob?.cancel()
        setupServer.stop()
        sourceJob?.cancel()
        // A report held back belongs to the source that sent it.
        heldJob?.cancel()
        heldJob = null
        flips = FlipGuard()
        stopSource(source)
        source = null
        sourceKey = null
        sleep.stop()
        seeking.clear()
        stoppedAt = now()
        // Covers and scenes are let go once it is clear that no screen comes straight back.
        releaseJob = scope.launch {
            delay(HANDOVER_MS)
            releaseMemory()
        }
        // The next start begins afresh: a pause from last night must not send a new launch to
        // sleep (or close it) before the music has been heard of.
        val setup = machine.isSetupRequested
        machine = ScreenStateMachine(machine.timing).also { it.requestSetup(setup) }
        lastReportKey = null
        lastVolume = null
        landscapeWeather = null
        _ui.value = _ui.value.copy(
            source = SourceState(LinkState.CONNECTING),
            screen = Screen.Rest(RestReason.CONNECTING),
            stage = null,
            flow = null,
            asleep = false,
            keepScreenOn = true,
            chapter = null,
            sleepAt = null,
        )
    }

    /** Lets go of decoded covers and scenes while no screen shows them; whether it did. */
    @Synchronized
    fun releaseMemory(): Boolean {
        if (viewers > 0) return false
        scenes.clear()
        artwork.clear()
        strip.clear()
        return true
    }

    /** Once a second, or sooner when a chapter ends before that. */
    private fun tickDelay(): Long = chapterEndsAt?.let { (it - now() + 20).coerceIn(20, 1_000) } ?: 1_000

    /** Extras from `am start` (cold start or onNewIntent). */
    @Synchronized
    fun applyLaunch(extras: Map<String, Any?>) {
        session.value = LaunchExtras.session(extras)
        machine.requestSetup(session.value.showSetup)
        when (session.value.landscape) {
            LandscapeRequest.SHOW -> showLandscape()
            LandscapeRequest.TIMELAPSE -> showLandscape(timelapse = true)
            // Asked to go: like up on the remote, it goes, and its delays start again.
            LandscapeRequest.HIDE -> hideLandscape()
            null -> Unit
        }
        if (LaunchExtras.hasStoredExtras(extras)) {
            scope.launch {
                // On a first start the stored settings may not be in yet: the extras go on top of
                // them, never on top of the defaults.
                if (!loaded) {
                    val stored = settingsStore.settings.first()
                    synchronized(this@NowPlayingController) {
                        if (!loaded) onSettings(stored)
                        loaded = true
                    }
                }
                takeAndWrite(LaunchExtras.applyTo(settings.value, extras), "launch's extras")
            }
        }
        reconcileSource()
        recompute()
    }

    @Synchronized
    fun requestSetup(show: Boolean) {
        machine.requestSetup(show)
        recompute()
    }

    // --- the landscape ---------------------------------------------------------------------

    /**
     * A key was pressed: the landscape's delays start again. One that came by itself goes, a
     * chosen one stays; the key goes on to do its work. Returns whether the landscape was
     * showing before the key.
     */
    @Synchronized
    fun interaction(): Boolean {
        val was = _ui.value.landscape != null
        landscapePolicy.interaction(now())
        if (was) recompute()
        return was
    }

    /**
     * The landscape now, whatever plays, until it is left (up, back, the quick menu, a link);
     * [timelapse] lets a day pass in two minutes (for measuring on the device).
     */
    @Synchronized
    fun showLandscape(timelapse: Boolean = false) {
        landscapePolicy.request(true)
        landscapeClock.timelapse(timelapse)
        recompute()
    }

    /** Up on the remote: the landscape comes as if chosen (it stays whatever plays), or, showing, goes. */
    @Synchronized
    fun toggleLandscape() {
        if (_ui.value.landscape != null || landscapePolicy.requested) hideLandscape() else showLandscape()
    }

    /**
     * Leaves the landscape (up, back, the quick menu, a link): the stage comes back, its sky
     * returns to the real moment, and the delays start again. Returns whether it was showing.
     */
    @Synchronized
    fun hideLandscape(): Boolean {
        val was = _ui.value.landscape != null || landscapePolicy.requested
        landscapePolicy.leave(now())
        landscapeClock.timelapse(false)
        recompute()
        return was
    }

    fun networkAvailable() {
        (source as? HaSource)?.networkAvailable()
    }

    fun send(command: RemoteCommand) {
        val volume = command == RemoteCommand.VOLUME_UP || command == RemoteCommand.VOLUME_DOWN
        val allowed = if (volume) settings.value.volumeKeys else settings.value.remoteControl
        if (!allowed) return
        // Back to the item before: that return is wanted at once.
        if (command == RemoteCommand.PREVIOUS) synchronized(this) { flips.expectReturn(now()) }
        if (volume) {
            // Answer the key at once; the new value follows from Home Assistant.
            volumeFlashUntil = now() + VOLUME_FLASH_MS
            recompute()
        }
        scope.launch { source?.send(command) }
    }

    // --- spoken word: jumps and chapters ---------------------------------------------------

    /** Left and right jump within what plays (a book, an episode) instead of changing it. */
    val spokenKeys: Boolean
        get() {
            val s = settings.value
            return s.remoteControl && s.spokenSkip && (_ui.value.screen as? Screen.Stage)?.track?.kind?.spoken == true
        }

    /** Jumps back ([direction] -1) or ahead (+1) within what plays. */
    fun skip(direction: Int) = seeking.skip(direction)

    /** Held left or right: by chapters, else to the item before or after. */
    fun jump(direction: Int) = seeking.jump(direction)

    // --- settings menu -------------------------------------------------------------------

    private val localEdits = AtomicInteger()
    private val persistLock = Mutex()

    val menuOpen: Boolean get() = menu.isOpen

    /** Up and down change the speaker's volume. */
    val volumeKeys: Boolean get() = settings.value.volumeKeys

    fun openMenu(): Unit = menu.open()

    fun closeMenu() = menu.close()

    /** Back in the menu: up a level, or out of it from the groups. Whether it is still open. */
    fun menuBack(): Boolean = menu.back()

    /** Up (-1) or down (+1) through the menu; it stops at its ends. [held]: the key repeats. */
    fun menuMove(delta: Int, held: Boolean = false) = menu.move(delta, held)

    /** The key held to run through the menu is let go. */
    fun menuRelease() = menu.release()

    /** Changes the line under the cursor: left (-1) or right (+1) along its scale, OK goes round ([wrap]); [repeat]: the key is held. */
    fun menuChange(direction: Int, wrap: Boolean, repeat: Boolean = false) = menu.change(direction, wrap, repeat)

    /** A change from one of the menus: shown at once, written behind it. */
    private fun applySettings(updated: Settings) {
        localEdits.incrementAndGet()
        onSettings(updated)
        scope.launch {
            try {
                persist()
            } finally {
                localEdits.decrementAndGet()
            }
        }
    }

    /**
     * Writes the settings as they are now. Always the newest state, one write at a time: quick
     * presses overtake each other, and a change from the setup page or the launch's extras never
     * comes back undone by a menu's write that was still on its way, or the other way round.
     */
    private suspend fun persist() {
        persistLock.withLock { settingsStore.write(settings.value) }
    }

    /**
     * Settings from the phone's setup page or the launch's extras ([from]): taken at once and
     * written, waiting for the write. They never wait for the store's own echo, which has been
     * seen to stay away on a first start (Android TV 14 emulator, right after installing) and
     * left the app on the setup page.
     */
    private suspend fun takeAndWrite(updated: Settings, from: String) {
        if (updated.isConfigured != settings.value.isConfigured) Log.i(TAG, "Settings from the $from: connection ${if (updated.isConfigured) "set" else "not set"}")
        localEdits.incrementAndGet()
        try {
            onSettings(updated)
            persist()
        } finally {
            localEdits.decrementAndGet()
        }
    }

    // --- quick settings ------------------------------------------------------------------

    val quickOpen: Boolean get() = quick.isOpen

    /** Opens the quick settings (down on the remote), on the view. */
    fun openQuick() = quick.open(menu.isOpen)

    /** Closes the quick settings (up, back, after a while alone); a view still waiting is taken now. */
    fun closeQuick() = quick.close()

    /** A key in the quick settings that changes nothing: they stay open a while longer. */
    fun touchQuick() = quick.touch()

    /** Left (-1) or right (+1) along the quick settings; it stops at their ends. */
    fun quickMove(delta: Int) = quick.move(delta)

    /** OK on the entry under the cursor: its next value, or what it says. */
    fun quickChange() = quick.change()

    @Synchronized
    private fun onSettings(s: Settings) {
        val view = s.view
        if (view != settings.value.view || _ui.value.view != view) {
            // Another view needs other scenes; the strip starts afresh. The view before stays on
            // screen until the new one's first scene is made (it goes then), so the switch never
            // waits in the dark.
            scenes.frame = scenes.frame.copy(view = view)
            strip.clear()
            _ui.value = _ui.value.copy(view = view)
        }
        settings.value = s
        val timing = timingFor(s)
        if (timing != machine.timing) {
            val setup = machine.isSetupRequested
            machine = ScreenStateMachine(timing).also { it.requestSetup(setup) }
        }
        reconcileSource()
        // The quick settings' own buttons show only while their entities are there (and their
        // conditions hold); the values in the status line follow their entities.
        source?.followEntities(QuickActions.entities(s.quickActions) + s.ownValues.map { it.entity })
        recompute()
    }

    /** Smoothed playback position for the current track, for the UI's draw phase. */
    fun positionNow(): Long? {
        val t = now()
        return smooth.display(seeking.position(t), t)
    }

    @Synchronized
    private fun reconcileSource() {
        val s = settings.value
        val key = if (s.isConfigured) listOf("ha", s.haUrl, s.haCredentials, s.entityId, s.sunEntity, s.maUrl, s.maToken, s.weatherEntities).joinToString("|") else "unconfigured"
        if (key == sourceKey || viewers == 0) return
        sourceKey = key
        Log.i(TAG, if (s.isConfigured) "Connecting to Home Assistant (${s.entityId})" else "No connection set: setup")
        sourceJob?.cancel()
        // A report held back belongs to the source that sent it.
        heldJob?.cancel()
        heldJob = null
        flips = FlipGuard()
        stopSource(source)
        // Another speaker (or another server) has its own history.
        strip.clear()
        lastLyrics = null
        val ma = if (s.hasMusicAssistant) MaClient(tokenHttp, s.maUrl!!, s.maToken!!) else null
        val newAuth = if (s.isConfigured) HaAuth(tokenHttp, s.haUrl!!, s.haCredentials!!, now) else null
        auth = newAuth
        val newSource: NowPlayingSource? =
            newAuth?.let { HaSource(tokenHttp, scope, HaConfig(it), s.entityId!!, s.sunEntity, now, ma, s.weatherEntities) }
        source = newSource
        if (newSource == null) {
            _ui.value = _ui.value.copy(source = SourceState(LinkState.UNCONFIGURED))
            return
        }
        newSource.start()
        lightSync.attach(newSource as? LightBridge)
        lastVolume = null
        sourceJob = scope.launch {
            newSource.state.collect { st -> onSource(st) }
        }
    }

    /** A new state from the source, written under the same lock as [recompute], so neither loses the other's update. */
    @Synchronized
    private fun onSource(st: SourceState) {
        val p = st.player
        val t = now()
        // A return to the item just left is usually the player catching up after a skip: it
        // waits a moment, and is shown only if it is still what plays then.
        heldJob?.cancel()
        heldJob = null
        if (!flips.admit(p?.track?.key, t)) {
            val at = flips.passesAt ?: return
            heldJob = scope.launch {
                delay((at - now()).coerceAtLeast(0))
                onSource(st)
            }
            return
        }
        val reportKey = Triple(p?.track?.key, p?.positionMs, p?.status)
        if (reportKey != lastReportKey) {
            lastReportKey = reportKey
            val raw = p?.let { Progress.positionAt(it, t, st.skewMs, st.receivedAtMs) }
            // A jump with the remote holds until Home Assistant reports the new place (or another item).
            smooth.onReport(p?.track?.key, seeking.onReport(st, raw, t), t)
        }
        // A new volume (set here, on a phone or anywhere else) is shown for a moment.
        val volume = p?.volume?.let { it to p.muted }
        if (volume != null) {
            if (lastVolume != null && volume != lastVolume) volumeFlashUntil = t + VOLUME_FLASH_MS
            lastVolume = volume
        }
        if (st.link != _ui.value.source.link) Log.i(TAG, "Link: ${st.link}")
        _ui.value = _ui.value.copy(source = st)
        recompute()
    }

    private var flips = FlipGuard()
    private var heldJob: Job? = null

    private var lastVolume: Pair<Float, Boolean>? = null
    @Volatile private var volumeFlashUntil = 0L

    /**
     * Stops [old] once the lights it was lighting have been given back through it (a few
     * seconds at most): the connection must still be there for that.
     */
    private fun stopSource(old: NowPlayingSource?) {
        if (old == null) return
        val releasing = lightSync.release()
        lightSync.attach(null)
        scope.launch {
            withTimeoutOrNull(LIGHTS_RELEASE_MS) { releasing.join() }
            old.stop()
        }
    }

    /** With the landscape on when paused, a paused cover waits for it instead of turning into the clock. */
    private fun timingFor(s: Settings) = Timing(
        sleepAfterMs = s.sleepAfterMinutes * 60_000L,
        pauseToRestMs = if (s.landscapePauseSeconds > 0) Long.MAX_VALUE / 4 else Timing().pauseToRestMs,
    )

    @Synchronized
    private fun recompute() {
        val s = settings.value
        val src = _ui.value.source
        val t = now()
        // A menu nobody uses closes by itself: it must not stand still on the OLED.
        menu.expire(t)
        quick.expire(t, menu.isOpen)
        // While a party's code shows, neither the clock nor the landscape takes its place by
        // itself (the landscape comes only when chosen): the guests can always scan the code.
        val party = quick.partyWanted(src.party)
        val decision: ScreenDecision = machine.decide(src, t, holdPause = party)
        val appearance = appearanceFor(s, src)
        val look = Look(appearance, s.intensity.factor, coverField = s.background == BackgroundMode.COVER)
        val stage = decision.screen as? Screen.Stage
        val playing = stage?.status == PlayerStatus.PLAYING && !stage.linkDown
        val speed = src.player?.speed ?: 1f
        // Spoken word: the chapter that plays, and the moment the next one begins.
        val position = stage?.let { seeking.position(t) }
        val texts = localizer.texts.value
        val chapter = stage?.let { ChapterPlace.at(it.track, position) { n -> texts.format(R.string.chapter_number, n) } }
        // (A chapter that ends before its item's end and is already over waits for nothing.)
        chapterEndsAt = if (chapter != null && position != null && playing && chapter.endMs > position) {
            t + ((chapter.endMs - position) / speed.coerceAtLeast(0.25f)).toLong()
        } else {
            null
        }
        // The sleep timer: it may want the music paused right now.
        sleep.evaluate(t, stage?.track, position, speed, playing)
        val previous = _ui.value
        val menuState = menu.state(s, appearance, stage?.track)
        // The landscape waits while the menu is open.
        val quietLongEnough = landscapePolicy.shows(
            decision.screen,
            t,
            if (party) 0L else s.landscapePauseSeconds * 1000L,
            if (party) 0L else s.landscapePlayingSeconds * 1000L,
        )
        // While the quick settings are open, the landscape stays as it was (unless they change it).
        val landscapeOn = quick.landscapeHeld ?: quietLongEnough
        val landscape = if (landscapeOn && menuState == null) {
            // The weather outside as the landscape comes, then gliding toward every change.
            val outside = WeatherMood.of(src.weather, src.cloudCoverage, src.windKmh) ?: Weather.FAIR
            val weather = landscapeWeather?.toward(outside, t) ?: WeatherGlide.still(outside)
            LandscapeUi(track = stage?.track, status = stage?.status, miniPlayer = s.miniPlayer, weather = weather)
        } else {
            null
        }
        landscapeWeather = landscape?.weather
        val lyrics = stage?.let { lyricsFor(s, src, it.track) }
        val quickState = quick.state(decision.screen, landscape != null)
        // After the clock, the setup page or the landscape, another song waits for its own scene.
        val stale = HeldStage.stale(previous, stage, landscape)
        _ui.value = previous.copy(
            screen = decision.screen,
            keepScreenOn = decision.keepScreenOn || menuState != null || quickState != null,
            asleep = decision.asleep && menuState == null && quickState == null,
            sleepAction = s.sleepAction,
            appearance = appearance,
            display = s.display,
            session = session.value,
            entityId = s.entityId,
            menu = menuState,
            quick = quickState,
            volumeFlash = s.volumeDisplay == VolumeDisplay.ON_CHANGE && t < volumeFlashUntil,
            chapter = chapter,
            sleepAt = sleep.shownPauseAt(previous.sleepAt),
            landscape = landscape,
            landscapeAhead = s.miniPlayer.takeIf { landscapePolicy.onItsWay },
            texts = texts,
            values = s.ownValues.mapNotNull { v ->
                val e = src.entities[v.entity]
                ValueText.of(v, e?.state, e?.attributes, src.precision[v.entity], src.temperatureUnit, texts, t)
            },
            partyCode = quick.partyCode.takeIf { party },
            downHint = !s.quickSeen && !s.volumeKeys && stage != null && menuState == null && quickState == null && landscape == null,
            stage = previous.stage.takeUnless { stale },
            flow = previous.flow.takeUnless { stale },
        )
        // The colors move only while music plays, on the screen and in the lights alike.
        fieldClock.run(if (playing) s.motion.speed else 0f)
        lightSync.update(lightTargets.target(s, _ui.value, stage, playing, landscape))
        if (stage != null) {
            if (s.view == StageView.FLOW) showFlow(stage, look, lyrics) else showStage(stage, look, lyrics)
        }
        // The setup page is only reachable while the setup screen is visible.
        if (decision.screen is Screen.Setup && viewers > 0) setupServer.start() else setupServer.stop()
    }

    /**
     * Only the menu's cursor moved (or the key that runs it was let go): the rest of the screen
     * stays as it is. A held key moves it twenty times a second.
     */
    @Synchronized
    private fun showMenu() {
        val shown = _ui.value.menu ?: return recompute()
        _ui.value = _ui.value.copy(menu = menu.moved(shown))
    }

    /**
     * The wall follows the setting, or automatically the sun: Home Assistant's elevation, else
     * the local clock. The last decision holds it steady near the horizon.
     */
    private var lastAppearance: Appearance? = null

    private fun appearanceFor(s: Settings, src: SourceState): Appearance {
        val a = when (s.appearance) {
            AppearanceMode.LIGHT -> Appearance.LIGHT
            AppearanceMode.DARK -> Appearance.DARK
            AppearanceMode.AUTO -> {
                val elevation = src.sunElevation ?: SunClock.elevationAt(now())
                Appearance.bySun(elevation, lastAppearance)
            }
        }
        lastAppearance = a
        return a
    }

    /** What was last known of the words of the song on stage: it stays while a moment between two songs says nothing. */
    private var lastLyrics: SongLyrics? = null

    /**
     * The words that take the text column for [track]: with Lyrics on, the synced words Music
     * Assistant has for a song whose position is known (the words follow it).
     */
    private fun lyricsFor(s: Settings, src: SourceState, track: TrackInfo): Lyrics? {
        src.lyrics?.let { lastLyrics = it }
        if (!s.lyrics || track.kind != MediaKind.MUSIC || track.durationMs == null) return null
        return lastLyrics?.takeIf { it.key == track.key }?.lyrics
    }

    /** The stage shows [stage]'s track as soon as its scene is ready (the next track's is made ahead). */
    private fun showStage(stage: Screen.Stage, look: Look, lyrics: Lyrics?) {
        scenes.retain(setOfNotNull(stage.track.sceneKey, stage.next?.sceneKey))
        val scene = scenes.ready(stage.track, look)
        if (scene == null) {
            scenes.prepare(stage.track, look) { recompute() }
            return
        }
        val shown = _ui.value.stage
        if (shown?.track != stage.track || shown.scene !== scene || shown.lyrics !== lyrics || _ui.value.flow != null) {
            // The view before (a cover flow) goes now.
            _ui.value = _ui.value.copy(stage = StageData(stage.track, scene, lyrics), flow = null)
        }
        stage.next?.let { scenes.prepare(it, look) }
    }

    /**
     * Cover Flow: the strip around [stage]'s track. Every card's scene is made (the middle one
     * first, then what comes, then what was), and the strip is shown once the middle one is
     * ready; cards still on their way join it as they come. A card whose scene is being lit
     * again keeps the one it has meanwhile. When the wall changes (light to dark), the whole
     * strip changes at once, as soon as every card it shows is ready.
     */
    private fun showFlow(stage: Screen.Stage, look: Look, lyrics: Lyrics?) {
        val src = _ui.value.source
        // Music Assistant's whole queue if it is set up, else Home Assistant's next track.
        val coming = if (src.queueKnown) src.coming.ifEmpty { listOfNotNull(stage.next) } else null
        val s = strip.update(stage.track, coming, before = src.played)
        scenes.retain(s.cards.map { it.track.sceneKey }.toSet())
        val order = listOf(s.current) + (s.current + 1 until s.cards.size) + (s.current - 1 downTo 0)
        for (i in order) {
            val track = s.cards[i].track
            if (scenes.ready(track, look) == null) scenes.prepare(track, look) { recompute() }
        }
        val middle = scenes.ready(s.middle.track, look) ?: return
        val shown = _ui.value.flow?.cards.orEmpty().associateBy { it.id }
        val newWall = shown.values.any { it.scene.appearance != look.appearance }
        if (newWall && s.cards.any { it.id in shown && scenes.ready(it.track, look) == null && scenes.pending(it.track) }) return
        val entries = s.cards.mapNotNull { c ->
            val scene = scenes.ready(c.track, look) ?: shown[c.id]?.scene?.takeIf { !newWall && it.layout == middle.layout }
            scene?.let { FlowEntry(c.id, c.track, it) }
        }
        val flow = FlowData(entries, entries.indexOfFirst { it.id == s.middle.id }, lyrics)
        // The view before (a single cover) goes now.
        if (flow != _ui.value.flow || _ui.value.stage != null) _ui.value = _ui.value.copy(flow = flow, stage = null)
    }

    companion object {
        /** How long a new volume stays in the status line. */
        const val VOLUME_FLASH_MS = 3_500L

        /** A stop shorter than this is a hand-over between the app and the screensaver. */
        const val HANDOVER_MS = 10_000L

        /** The tag of the few lines the app writes to the log: connection and settings, never a token. */
        const val TAG = "Klangbild"

        /** How long a closing connection waits for the lights to be given back. */
        const val LIGHTS_RELEASE_MS = 4_000L
    }
}
