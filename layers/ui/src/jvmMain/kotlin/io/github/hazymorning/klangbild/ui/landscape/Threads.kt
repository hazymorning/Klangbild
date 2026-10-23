package io.github.hazymorning.klangbild.ui.landscape

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

internal actual fun threadOfItsOwn(name: String, background: Boolean): CoroutineDispatcher =
    Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { if (background) priority = Thread.MIN_PRIORITY }
    }.asCoroutineDispatcher()
