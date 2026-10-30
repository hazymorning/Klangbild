package io.github.hazymorning.klangbild.app

import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.config.AppearanceMode
import io.github.hazymorning.klangbild.config.BackgroundMode
import io.github.hazymorning.klangbild.config.LaunchExtras
import io.github.hazymorning.klangbild.config.QueueDisplay
import io.github.hazymorning.klangbild.config.QuickActions
import io.github.hazymorning.klangbild.config.RoomLightBrightness
import io.github.hazymorning.klangbild.config.SessionOptions
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.config.SettingsStore
import io.github.hazymorning.klangbild.config.VolumeDisplay
import io.github.hazymorning.klangbild.data.ArtworkLoader
import io.github.hazymorning.klangbild.data.LightBridge
import io.github.hazymorning.klangbild.data.Log
import io.github.hazymorning.klangbild.data.NowPlayingSource
import io.github.hazymorning.klangbild.data.PictureDecoder
import io.github.hazymorning.klangbild.data.Push
import io.github.hazymorning.klangbild.data.RemoteCommand
import io.github.hazymorning.klangbild.data.ha.HaAuth
import io.github.hazymorning.klangbild.data.ha.HaCredentials
import io.github.hazymorning.klangbild.data.ha.HaDevice
import io.github.hazymorning.klangbild.data.ha.HaSource
import io.github.hazymorning.klangbild.data.ma.MaClient
import io.github.hazymorning.klangbild.data.ma.MilkdropSound
import io.github.hazymorning.klangbild.domain.ChapterPlace
import io.github.hazymorning.klangbild.domain.FieldClock
import io.github.hazymorning.klangbild.domain.FlipGuard
import io.github.hazymorning.klangbild.domain.LinkState
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.Presence
import io.github.hazymorning.klangbild.domain.Progress
import io.github.hazymorning.klangbild.domain.RestReason
import io.github.hazymorning.klangbild.domain.Screen
import io.github.hazymorning.klangbild.domain.ScreenDecision
import io.github.hazymorning.klangbild.domain.ScreenStateMachine
import io.github.hazymorning.klangbild.domain.SmoothedPosition
import io.github.hazymorning.klangbild.domain.SourceState
import io.github.hazymorning.klangbild.domain.SpeakerLink
import io.github.hazymorning.klangbild.domain.SpeakerStatus
import io.github.hazymorning.klangbild.domain.StatusMessage
import io.github.hazymorning.klangbild.domain.SunClock
import io.github.hazymorning.klangbild.domain.Timing
import io.github.hazymorning.klangbild.domain.UpdateInfo
import io.github.hazymorning.klangbild.domain.ValueText
import io.github.hazymorning.klangbild.lights.LightSync
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.DiscoverResult
import io.github.hazymorning.klangbild.setup.SetupHandler
import io.github.hazymorning.klangbild.setup.SetupServer
import io.github.hazymorning.klangbild.sound.SoundFeed
import io.github.hazymorning.klangbild.text.AppLanguage
import io.github.hazymorning.klangbild.text.Localizer
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import io.ktor.client.HttpClient
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Clock

/**
 * The app's single source of truth. It runs the Home Assistant source and the screen state
 * machine (on every update and once a second) and has the scenes (cover, palette, light) made
 * off the main thread: the next track's ahead of time, in Cover Flow the whole strip's. The
 * menus, seeking and the sleep timer are parts of their own ([MenuControl], [QuickControl],
 * [Seeking], [SleepControl]); [LightTargets] says what the lights show.
 */
class NowPlayingController(
    private val scope: CoroutineScope,
    /** For requests without a token: covers from other hosts. */
    private val http: HttpClient,
    /** For requests that carry a token: they never follow a redirect, so a token never reaches another host. */
    private val tokenHttp: HttpClient,
    private val settingsStore: SettingsStore,
    /** The words in the language chosen, and where the choice is kept. */
    private val localizer: Localizer,
    /** How the platform decodes the covers. */
    pictures: PictureDecoder,
    /** The setup page's server on the platform, for the handler given. */
    setupServerFor: (CoroutineScope, SetupHandler) -> SetupServer,
    private val log: Log,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** This TV as a speaker in Music Assistant, while its service runs. */
    private val speaker: StateFlow<SpeakerStatus> = MutableStateFlow(SpeakerStatus()),
    /** The code Music Assistant pairs this TV with, while it plays as a speaker (for the setup page). */
    private val pairingToken: () -> String? = { null },
    /** What this TV plays, as a sound feed, while it plays as a speaker. */
    ownSound: () -> SoundFeed? = { null },
    /** Klangbild's own new versions; none where nothing looks for them. */
    private val updates: Updates? = null,
    /** This TV as Home Assistant lists it once it is sent notifications; none where it can't be. */
    private val device: HaDevice? = null,
) {
    // In the language shown from the first frame, the clock as the system has it.
    private val _ui = MutableStateFlow(UiState(texts = localizer.texts.value))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** What changes together changes under this lock (the menus' parts take it too). */
    private val lock = SynchronizedObject()

    private val soundSelection = SoundSelection(ownSound)

    /** What the music shown does, moment by moment, for the visualizer to come (today the debug overlay's meter). */
    val sound: SoundFeed get() = soundSelection

    private val settings = MutableStateFlow(Settings())
    private val session = MutableStateFlow(SessionOptions())

    /** The stored settings, read once ([load]): from then on the controller owns them and writes every change ([save]). */
    private val stored = scope.async(start = CoroutineStart.LAZY) { settingsStore.settings.first() }

    /** The stored settings are in. Until then [settings] holds the defaults, and no change is saved. */
    @Volatile private var loaded = false

    /** Read from coroutines and the main thread as well; written under the lock. */
    @Volatile private var source: NowPlayingSource? = null
    private var sourceKey: String? = null
    private var sourceJob: Job? = null
    private var ticker: Job? = null
    private var settingsJob: Job? = null
    private var machine = ScreenStateMachine()

    /** Whether someone is in the room, by the presence sensors chosen on the phone and the keys pressed. */
    private var presence = Presence()

    /** The notification the status line shows, from Home Assistant. */
    private val messages = StatusMessage()
    private var pushJob: Job? = null

    /** The connection this TV last registered for notifications with: once per connection. */
    private var registeredWith: NowPlayingSource? = null
    @Volatile private var notifyFailed = false
    private val smooth = SmoothedPosition()
    private val volumeFlash = VolumeFlash(now)
    private var lastReportKey: Any? = null

    /** The next chapter's start on the wall clock, so the stage turns to it on time. */
    @Volatile private var chapterEndsAt: Long? = null

    /** The color field's time, shared by the screen and the lights. */
    private val fieldClock = FieldClock(now)

    /** The lights in the cover's colors (through Home Assistant). */
    private val lightSync = LightSync(scope, now)
    private val lightTargets = LightTargets(fieldClock, now)

    private val host = object : ControllerHost {
        override val lock: SynchronizedObject get() = this@NowPlayingController.lock
        override val scope: CoroutineScope get() = this@NowPlayingController.scope
        override val now: () -> Long get() = this@NowPlayingController.now
        override val settings: Settings get() = this@NowPlayingController.settings.value
        override val ui: UiState get() = _ui.value
        override val source: NowPlayingSource? get() = this@NowPlayingController.source
        override val texts: Texts get() = localizer.texts.value
        override val log: Log get() = this@NowPlayingController.log
        override val speaker: SpeakerLink get() = this@NowPlayingController.speaker.value.link
        override val update: UpdateInfo get() = updates?.info?.value ?: UpdateInfo()
        override val notifyFailed: Boolean get() = this@NowPlayingController.notifyFailed
        override fun update() {
            updates?.ok()
        }
        override var language: AppLanguage
            get() = localizer.language
            set(value) {
                // Applies at once everywhere: the menus are built anew from the new texts.
                localizer.setLanguage(value)
            }
        override fun recompute() = this@NowPlayingController.recompute()
        override fun showMenu() = this@NowPlayingController.showMenu()
        override fun openMenu(): Unit = menu.open()
        override fun applySettings(updated: Settings) {
            save(updated)
        }
        override fun send(command: RemoteCommand) = this@NowPlayingController.send(command)
        override fun requestSetup(show: Boolean) = this@NowPlayingController.requestSetup(show)
    }
    private val sleep = SleepControl(host)
    internal val menu: MenuControl = MenuControl(host, sleep)
    internal val quick: QuickControl = QuickControl(host)
    internal val seeking: Seeking = Seeking(host)

    /** The queue's way just chosen, until the screen shows it. */
    private val queueSwitch = QueueSwitch()

    /** The color field's time now (seconds): it runs while music plays, at the motion setting's speed. */
    fun fieldTime(): Double = fieldClock.now()

    /** How the current connection signs in to Home Assistant, while there is one. */
    @Volatile private var auth: HaAuth? = null

    private val stageBuilder = StageBuilder(scope, ArtworkLoader(http, tokenHttp, { auth }, pictures), log, now) { recompute() }

    private var viewers = 0

    private val setupHandler: SetupHandler = SetupHandler(
        settings = { settings.value },
        texts = { localizer.texts.value },
        discover = { url, credentials ->
            SetupServer.discover(tokenHttp, url, credentials, localizer.texts.value).also { r ->
                if (r is DiscoverResult.Failed && r.refused) refused(credentials)
            }
        },
        save = { updated ->
            // Leaving setup: the new settings start a fresh connection.
            synchronized(lock) { machine.requestSetup(false) }
            take("setup page") { updated }
        },
        // Home Assistant knows the TV by its setup page's address; its login page sends the phone back there.
        clientId = { setupServer.origin?.let { "$it/" } },
        exchange = { url, code, clientId -> HaAuth.exchangeCode(tokenHttp, url, code, clientId) },
        revoke = { url, refreshToken -> HaAuth.revoke(tokenHttp, url, refreshToken) },
        checkMa = { url, token -> MaClient(tokenHttp, url, token).check() },
        ownSpeaker = { _ui.value.source.ownSpeaker },
        pairingToken = pairingToken,
        maGuess = {
            val track = _ui.value.source.player?.track
            MaClient.addressIn(track?.artworkUrl) ?: MaClient.addressIn(track?.artworkFallbackUrl)
        },
    )
    private val setupServer: SetupServer = setupServerFor(scope, setupHandler)
    private var setupJob: Job? = null

    /** A screen (activity or dream) starts showing the controller. */
    fun attach(spec: ScreenSpec): Unit = synchronized(lock) {
        viewers++
        stageBuilder.screen(spec)
        if (viewers == 1) start()
    }

    fun detach(): Unit = synchronized(lock) {
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
            // The first start reads the stored settings; a later one starts the source again on them.
            if (loaded) onSettings(settings.value) else load()
            // A screen started showing: ask for a new version, unless that is turned off.
            if (settings.value.updateCheck) updates?.checkIfDue()
        }
        ticker = scope.launch {
            while (isActive) {
                delay(tickDelay())
                recompute()
            }
        }
        setupJob = scope.launch {
            setupServer.info.collect { info -> synchronized(lock) { _ui.value = _ui.value.copy(setup = info) } }
        }
        textsJob = scope.launch { localizer.texts.collect { recompute() } }
        speakerJob = scope.launch {
            speaker.collect { status -> synchronized(lock) { soundSelection.update(_ui.value.source, status) } }
        }
        updates?.let { u -> updatesJob = scope.launch { u.info.collect { recompute() } } }
    }

    private var textsJob: Job? = null
    private var speakerJob: Job? = null
    private var updatesJob: Job? = null

    private fun stop() {
        settingsJob?.cancel()
        textsJob?.cancel()
        speakerJob?.cancel()
        updatesJob?.cancel()
        ticker?.cancel()
        setupJob?.cancel()
        setupServer.stop()
        sourceJob?.cancel()
        pushJob?.cancel()
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
        presence = Presence()
        lastReportKey = null
        volumeFlash.reset()
        _ui.value = _ui.value.copy(
            source = SourceState(LinkState.CONNECTING),
            screen = Screen.Rest(RestReason.CONNECTING),
            stage = null,
            flow = null,
            queuePending = null,
            asleep = false,
            dark = false,
            chapter = null,
            sleepAt = null,
        )
    }

    /** Lets go of decoded covers and scenes while no screen shows them. */
    fun releaseMemory(): Unit = synchronized(lock) {
        if (viewers > 0) return
        stageBuilder.clear()
    }

    /** Once a second, or sooner when a chapter ends before that. */
    private fun tickDelay(): Long = chapterEndsAt?.let { (it - now() + 20).coerceIn(20, 1_000) } ?: 1_000

    /** Extras from `am start` (cold start or onNewIntent). */
    fun applyLaunch(extras: Map<String, Any?>): Unit = synchronized(lock) {
        session.value = LaunchExtras.session(extras)
        machine.requestSetup(session.value.showSetup)
        if (LaunchExtras.hasStoredExtras(extras)) {
            scope.launch { take("launch's extras") { LaunchExtras.applyTo(it, extras) } }
        }
        reconcileSource()
        recompute()
    }

    fun requestSetup(show: Boolean): Unit = synchronized(lock) {
        machine.requestSetup(show)
        recompute()
    }

    /**
     * A key was pressed: someone is in the room. True while the screen was dark for no one being
     * there: then the key only brings the picture back.
     */
    fun keyPressed(): Boolean = synchronized(lock) {
        val dark = _ui.value.dark
        presence.keyPressed(now())
        if (dark) recompute()
        dark
    }

    fun networkAvailable() {
        (source as? HaSource)?.networkAvailable()
    }

    /** Up and down change the speaker's volume. */
    val volumeKeys: Boolean get() = settings.value.volumeKeys

    fun send(command: RemoteCommand) {
        val volume = command == RemoteCommand.VOLUME_UP || command == RemoteCommand.VOLUME_DOWN
        val allowed = if (volume) settings.value.volumeKeys else settings.value.remoteControl
        if (!allowed) return
        // Back to the item before: that return is wanted at once.
        if (command == RemoteCommand.PREVIOUS) synchronized(lock) { flips.expectReturn(now()) }
        if (volume) {
            volumeFlash.keyPressed()
            recompute()
        }
        scope.launch { source?.send(command) }
    }

    // --- settings ------------------------------------------------------------------------

    private val persistLock = Mutex()

    /** Applies the stored settings, once, before anything changes them. */
    private suspend fun load() {
        val s = try {
            stored.await()
        } catch (e: IOException) {
            log.warn("Settings: not read (${e::class.simpleName})")
            return
        }
        synchronized(lock) {
            if (loaded) return
            onSettings(s)
            loaded = true
        }
    }

    /**
     * Applies [updated] at once and writes it; the job ends once it is written. Ignored until the
     * stored settings are in, so the defaults never overwrite them.
     */
    private fun save(updated: Settings): Job? = synchronized(lock) {
        if (!loaded) return null
        onSettings(updated)
        scope.launch { persist() }
    }

    /**
     * Writes the settings as they are now. Always the newest state, one write at a time, so quick
     * changes overtake each other and an older state is never written last.
     */
    private suspend fun persist() {
        try {
            persistLock.withLock { settingsStore.write(settings.value) }
        } catch (e: IOException) {
            log.warn("Settings: not written (${e::class.simpleName})")
        }
    }

    /**
     * Applies [change] from the setup page or the launch's extras ([from]) to the stored settings,
     * and waits until the result is written.
     */
    private suspend fun take(from: String, change: (Settings) -> Settings) {
        load()
        val written = synchronized(lock) {
            val updated = change(settings.value)
            if (updated.isConfigured != settings.value.isConfigured) {
                log.info("Settings from the $from: connection ${if (updated.isConfigured) "set" else "not set"}")
            }
            save(updated)
        }
        written?.join()
    }

    private fun onSettings(s: Settings): Unit = synchronized(lock) {
        stageBuilder.queueDisplay(s.queue == QueueDisplay.FLOW, onStage = _ui.value.stage?.track ?: _ui.value.flow?.middle?.track)
        if (_ui.value.queue != s.queue) {
            queueSwitch.chosen(s.queue)
            _ui.value = _ui.value.copy(queue = s.queue)
        }
        // Turned on in the menu: ask at once.
        if (loaded && s.updateCheck && !settings.value.updateCheck) updates?.checkIfDue()
        settings.value = s
        val timing = timingFor(s)
        if (timing != machine.timing) {
            val setup = machine.isSetupRequested
            machine = ScreenStateMachine(timing).also { it.requestSetup(setup) }
        }
        reconcileSource()
        // The quick settings' own buttons show only while their entities are there (and their
        // conditions hold); the values in the status line follow their entities.
        source?.followEntities(QuickActions.entities(s.quickActions) + s.ownValues.map { it.entity } + s.presenceSensors)
        // Music from other rooms shows while nothing plays here.
        source?.followOthers(s.shownOtherRooms)
        notify(s)
        recompute()
    }

    /**
     * Notifications: received with the stored registration while they are on; without one, this
     * TV registers once the connection stands, once per connection.
     */
    private fun notify(s: Settings) {
        val src = source ?: return
        src.notifyTo(s.notifyWebhook.takeIf { s.notifications })
        if (!s.notifications || s.notifyWebhook != null || registeredWith === src || _ui.value.source.link != LinkState.CONNECTED) return
        val me = device ?: return
        registeredWith = src
        val id = s.notifyDeviceId ?: Random.nextBytes(DEVICE_ID_BYTES).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        scope.launch {
            try {
                val webhook = src.register(me, id)
                notifyFailed = false
                log.info("Notifications: registered")
                take("notifications") { it.copy(notifyWebhook = webhook, notifyDeviceId = id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Notifications: not registered (${e::class.simpleName})")
                notifyFailed = true
                recompute()
            }
        }
    }

    /** What arrives for this TV's notifications. */
    private fun onPush(push: Push): Unit = synchronized(lock) {
        when (push) {
            is Push.Message -> if (settings.value.notifications) {
                messages.arrive(push.title, push.message, now())
                recompute()
            }
            // The registration is gone (deleted in Home Assistant, or another user's): it is made
            // anew, unless it was made on this connection already.
            Push.Refused -> {
                log.info("Notifications: Home Assistant refused the registration")
                if (registeredWith === source) notifyFailed = true
                val s = settings.value
                if (s.notifyWebhook != null) save(s.copy(notifyWebhook = null))
            }
        }
    }

    /**
     * [key]'s smoothed position for the UI's draw phase, or null once the player reports another
     * song (its report arrives a moment before the screen changes to it). The report is read
     * once, so the position always belongs to the song it names.
     */
    fun positionOf(key: String): Long? {
        val source = _ui.value.source
        if (source.player?.track?.key != key) return null
        val t = now()
        return smooth.display(key, seeking.position(t, source), t)
    }

    private fun reconcileSource(): Unit = synchronized(lock) {
        val s = settings.value
        val key = when {
            s.isConfigured -> listOf("ha", s.haUrl, s.haCredentials, s.entityId, s.sunEntity, s.maUrl, s.maToken).joinToString("|")
            s.haRefused -> "refused"
            else -> "unconfigured"
        }
        if (key == sourceKey || viewers == 0) return
        sourceKey = key
        log.info(if (s.isConfigured) "Connecting to Home Assistant (${s.entityId})" else "No connection set: setup")
        sourceJob?.cancel()
        // A report held back belongs to the source that sent it.
        heldJob?.cancel()
        heldJob = null
        flips = FlipGuard()
        stopSource(source)
        stageBuilder.newSource()
        val ma = if (s.hasMusicAssistant) MaClient(tokenHttp, s.maUrl!!, s.maToken!!) else null
        soundSelection.useMilkdrop(if (s.hasMusicAssistant) MilkdropSound(tokenHttp, scope, s.maUrl!!, s.maToken!!) else null)
        val newAuth = if (s.isConfigured) HaAuth(tokenHttp, s.haUrl!!, s.haCredentials!!, now) else null
        auth = newAuth
        val newSource: NowPlayingSource? =
            newAuth?.let { HaSource(tokenHttp, scope, it, s.entityId!!, s.sunEntity, now, ma, speaker = speaker) }
        source = newSource
        if (newSource == null) {
            _ui.value = _ui.value.copy(source = SourceState(if (s.haRefused) LinkState.AUTH_FAILED else LinkState.UNCONFIGURED))
            return
        }
        newSource.start()
        lightSync.attach(newSource as? LightBridge)
        volumeFlash.reset()
        sourceJob = scope.launch {
            newSource.state.collect { st -> onSource(st) }
        }
        pushJob?.cancel()
        pushJob = scope.launch { newSource.notifications.collect { onPush(it) } }
    }

    /**
     * Home Assistant refused [credentials]. When they are the stored ones, they are dropped
     * ([Settings.refused]), so they are never sent again: Home Assistant counts every attempt as a
     * failed login.
     */
    private fun refused(credentials: HaCredentials) {
        synchronized(lock) {
            if (settings.value.haCredentials != credentials) return
            log.warn("Home Assistant refused the credentials: dropped")
            save(settings.value.refused())
        }
    }

    /** A new state from the source, written under the same lock as [recompute], so neither loses the other's update. */
    private fun onSource(st: SourceState): Unit = synchronized(lock) {
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
        volumeFlash.reported(st, _ui.value.source.speaker, t)
        val linked = st.link != _ui.value.source.link
        if (linked) {
            log.info("Link: ${st.link}")
            // Dropped after this report is taken in, since dropping them stops the source that sent it.
            if (st.link == LinkState.AUTH_FAILED) auth?.credentials?.let { scope.launch { refused(it) } }
        }
        _ui.value = _ui.value.copy(source = st)
        // Connected: this TV may register for notifications now.
        if (linked && st.link == LinkState.CONNECTED) notify(settings.value)
        soundSelection.update(st, speaker.value)
        recompute()
    }

    private var flips = FlipGuard()
    private var heldJob: Job? = null

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

    private fun timingFor(s: Settings) = Timing(sleepAfterMs = s.sleepAfterMinutes * 60_000L)

    private fun recompute(): Unit = synchronized(lock) {
        val s = settings.value
        val src = _ui.value.source
        val t = now()
        // A menu nobody uses closes by itself: it must not stand still on the OLED.
        menu.expire(t)
        quick.expire(t, menu.isOpen)
        // While a party's code shows, the clock doesn't take its place by itself: the guests can
        // always scan the code.
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
        val chapter = stage?.let { ChapterPlace.at(it.track, position) { n -> texts.format(Strings.chapterNumber, n) } }
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
        val quickState = quick.state(decision.screen)
        // No one in the room, as the presence sensors tell: the screen goes dark and the music
        // plays on, never on the setup screen or under a menu. An empty room lets the screen
        // sleep as no music does, after the same time.
        val sensors = if (s.darkWhenNobody) s.presenceSensors.map { src.entities[it]?.state } else emptyList()
        val nobody = presence.nobodyThere(sensors, t)
        val dark = nobody && decision.screen !is Screen.Setup && menuState == null && quickState == null
        val nobodyLong = presence.nobodySince?.let { t - it >= machine.timing.sleepAfterMs } == true
        // A notification shows where it can be seen: on the stage or the rest screen, with no menu over it.
        val seen = !dark && decision.screen !is Screen.Setup && menuState == null && quickState == null
        val message = messages.line(seen && s.notifications, t)
        // After the clock or the setup page, another song (or the same one on a new wall, or to or
        // from Cover Flow) waits for its own scene.
        val stale = HeldStage.stale(previous, s.queue == QueueDisplay.FLOW, look::fits, stage)
        val decided = previous.copy(
            screen = decision.screen,
            asleep = (decision.asleep || nobodyLong) && menuState == null && quickState == null,
            dark = dark,
            message = message,
            sleepAction = s.sleepAction,
            appearance = appearance,
            display = s.display,
            session = session.value,
            entityId = s.entityId,
            menu = menuState,
            quick = quickState,
            volumeFlash = s.volumeDisplay == VolumeDisplay.ON_CHANGE && volumeFlash.shown(t),
            chapter = chapter,
            sleepAt = sleep.shownPauseAt(previous.sleepAt),
            texts = texts,
            values = s.ownValues.mapNotNull { v ->
                val e = src.entities[v.entity]
                ValueText.of(v, e?.state, e?.attributes, src.precision[v.entity], src.temperatureUnit, texts, t)
            },
            partyCode = quick.partyCode.takeIf { party },
            downHint = !s.quickSeen && !s.volumeKeys && stage != null && menuState == null && quickState == null,
            stage = previous.stage.takeUnless { stale },
            flow = previous.flow.takeUnless { stale },
        )
        // The colors move only while music plays, on the screen and in the lights alike.
        fieldClock.run(if (playing) s.motion.speed else 0f)
        val ready = if (stage == null) decided else stageBuilder.build(decided, s, stage, look, previous.stage)
        // Meanwhile the clock stays, never the dark, and the keys and the lights act on what shows;
        // the quick settings' Queue still names the choice made. One state is published, never one
        // with nothing to show in between.
        val shown = HeldStage.shown(previous, ready)
        val published = if (shown !== ready) shown.copy(quick = quick.state(shown.screen)) else ready
        // The queue display just chosen stays pending, with dots after its value in the quick
        // settings and the menu, until this state shows it.
        _ui.value = published.copy(queuePending = queueSwitch.onItsWay(ready, stage != null, s.queue, quick.pending))
        // The lights follow what shows, a scene that is ready at once included; lights no longer
        // chosen, or switched off, go back at once, and so do all of them while the screen is dark.
        val chosen = if (dark || s.roomLightBrightness == RoomLightBrightness.OFF) emptySet() else LightSync.slots(s.roomLights).keys
        lightSync.update(lightTargets.target(s, _ui.value, stage, playing).takeUnless { dark }, chosen)
        // The setup page is only reachable while the setup screen is visible.
        if (decision.screen is Screen.Setup && viewers > 0) setupServer.start() else setupServer.stop()
    }

    /**
     * Only the menu's cursor moved (or the key that runs it was let go): the rest of the screen
     * stays as it is. A held key moves it twenty times a second.
     */
    private fun showMenu(): Unit = synchronized(lock) {
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

    companion object {
        /** A stop shorter than this is a hand-over between the app and the screensaver. */
        const val HANDOVER_MS = 10_000L

        /** How long a closing connection waits for the lights to be given back. */
        const val LIGHTS_RELEASE_MS = 4_000L

        /** How long the id is that Home Assistant knows this TV by (bytes, written in hex). */
        const val DEVICE_ID_BYTES = 8
    }
}
