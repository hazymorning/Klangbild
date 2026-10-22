package io.github.hazymorning.klangbild

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.provider.Settings.Global
import io.github.hazymorning.klangbild.domain.SpeakerStatus
import io.github.hazymorning.klangbild.speaker.AudioTrackSink
import io.github.hazymorning.klangbild.speaker.Speaker
import io.github.hazymorning.klangbild.speaker.SpeakerConnection
import io.github.hazymorning.klangbild.speaker.SpeakerIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** This TV's speaker while its service runs: its state for the rest of the app, and the speaker itself. */
class SpeakerHub {
    private val _status = MutableStateFlow(SpeakerStatus())
    val status: StateFlow<SpeakerStatus> = _status.asStateFlow()

    @Volatile var speaker: Speaker? = null
        internal set

    internal fun publish(status: SpeakerStatus) {
        _status.value = status
    }
}

/**
 * This TV as a speaker in Music Assistant: a foreground service of the media playback kind, so
 * the speaker keeps playing while Klangbild's screen is gone (the screensaver, another app, the
 * app closed). It runs while the setting is on and Music Assistant is set up, from the moment
 * Klangbild is opened (Android lets no media service start by itself after a reboot), and ends
 * itself when the setting goes off.
 *
 * While music plays it holds the TV's sound (audio focus): another app taking it for a moment
 * silences the speaker in time underneath, taking it for good makes the speaker leave its group.
 * Its notification is a plain one, not tied to the media session, so newer Android versions do
 * not end the service after a while without music.
 */
class SpeakerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var media: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    @Volatile private var wanted: String? = null

    private val hub get() = graph.speakerHub

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Woken up (the TV came on): no waiting for the next try.
                Intent.ACTION_SCREEN_ON -> hub.speaker?.retryNow()
                // An HDMI device came or went: the output's timing starts anew.
                AudioManager.ACTION_HDMI_AUDIO_PLUG -> hub.speaker?.outputChanged()
            }
        }
    }

    private val focusChanged = AudioManager.OnAudioFocusChangeListener { change ->
        val speaker = hub.speaker ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> speaker.soundTaken(false)
            AudioManager.AUDIOFOCUS_LOSS -> {
                speaker.soundTaken(true, lasting = true)
                abandonFocus()
            }
            else -> speaker.soundTaken(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        media = MediaSession(this, "Klangbild").apply { setPlaybackState(state(PlaybackState.STATE_STOPPED)) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(events, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(events, filter)
        scope.launch {
            graph.settings.settings.map { s -> if (s.speakerOn) s.maUrl else null }
                .distinctUntilChanged()
                .onEach { wanted = it }
                .collectLatest { url -> speak(url) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start through startForegroundService is answered in the foreground at once.
        goForeground()
        // After the system ended it, the speaker waits for Klangbild to be opened again.
        return START_NOT_STICKY
    }

    /** One speaker for Music Assistant at [maUrl]; none (and the service ends) without one. */
    private suspend fun speak(maUrl: String?) {
        val url = maUrl?.let(SpeakerConnection::urlFor)
        if (url == null) {
            stopSelf()
            return
        }
        val speaker = Speaker(
            scope = scope,
            http = graph.tokenHttp,
            store = graph.speakerStore,
            url = url,
            identity = identity(),
            sinkFactory = { routed -> AudioTrackSink({ System.nanoTime() / 1_000 }, routed) },
        )
        hub.speaker = speaker
        speaker.start()
        try {
            speaker.status.collect { status ->
                hub.publish(status)
                follow(status)
            }
        } finally {
            // Switched off: Music Assistant lets the speaker go. Anything else: it keeps it ready.
            speaker.stop(if (wanted == null) "shutdown" else "restart")
            hub.speaker = null
            hub.publish(SpeakerStatus())
            follow(SpeakerStatus())
        }
    }

    /** The media session and the TV's sound follow whether music plays here (and nothing once the service ends). */
    @Synchronized
    private fun follow(status: SpeakerStatus) {
        val m = media ?: return
        if (status.playing == m.isActive) return
        m.setPlaybackState(state(if (status.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED))
        m.isActive = status.playing
        if (status.playing) takeFocus() else abandonFocus()
    }

    private fun takeFocus() {
        if (focus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            // Ducking a speaker that plays in time with others would sound wrong: it goes quiet instead.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusChanged)
            .build()
        focus = request
        val granted = getSystemService(AudioManager::class.java).requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hub.speaker?.soundTaken(!granted)
    }

    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request)
    }

    private fun state(state: Int) = PlaybackState.Builder()
        .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .build()

    /** Named as the TV is, in its settings; else its model. */
    private fun identity() = SpeakerIdentity(
        name = Global.getString(contentResolver, Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL,
        productName = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        softwareVersion = "Klangbild ${BuildConfig.VERSION_NAME}",
    )

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.speaker_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.speaker_notification))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION, notification)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(events)
        scope.cancel()
        synchronized(this) {
            abandonFocus()
            media?.release()
            media = null
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "speaker"
        private const val NOTIFICATION = 1
    }
}
