package io.github.hazymorning.klangbild.ui.landscape

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

internal actual fun threadOfItsOwn(name: String, background: Boolean): CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r ->
        Thread({
            if (background) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, name)
    }.asCoroutineDispatcher()
