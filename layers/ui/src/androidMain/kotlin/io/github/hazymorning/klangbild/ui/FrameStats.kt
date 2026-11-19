package io.github.hazymorning.klangbild.ui

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The frames of each second ([FrameSecond]) from the platform's FrameMetrics, for [DebugOverlay]. */
object FrameStats {
    private val _stats = MutableStateFlow(FrameSecond(0, 0f, 0, null))
    val stats: StateFlow<FrameSecond> = _stats.asStateFlow()

    private var frames = 0
    private var worst = 0f
    private var worstGpu = 0f
    private var janky = 0
    private var secondStart = 0L

    /** One thread for the whole app, started the first time it is needed. */
    private val handler by lazy { Handler(HandlerThread("frame-stats").apply { start() }.looper) }

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000f
        val now = System.nanoTime()
        if (secondStart == 0L) secondStart = now
        frames++
        if (ms > worst) worst = ms
        if (ms > FRAME_BUDGET_MS) janky++
        // The total leaves out the GPU's own work, which a weak GPU at 4K spends most on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worstGpu = maxOf(worstGpu, metrics.getMetric(FrameMetrics.GPU_DURATION) / 1_000_000f)
        if (now - secondStart >= 1_000_000_000L) {
            // The platform tells the GPU's time from Android 12 on.
            _stats.value = FrameSecond(frames, worst, janky, worstGpu.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S })
            frames = 0
            worst = 0f
            worstGpu = 0f
            janky = 0
            secondStart = now
        }
    }

    /** Counts [window]'s frames until [detach], only while the overlay is asked for. */
    fun attach(window: Window) {
        window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    /** Stops counting [window]'s frames; nothing keeps the window afterwards. */
    fun detach(window: Window) {
        try {
            window.removeOnFrameMetricsAvailableListener(listener)
        } catch (e: IllegalArgumentException) {
            // The window never held the listener: there is nothing to stop.
        }
    }

    /** A frame at 60 Hz has this long (ms). */
    private const val FRAME_BUDGET_MS = 1000f / 60
}
