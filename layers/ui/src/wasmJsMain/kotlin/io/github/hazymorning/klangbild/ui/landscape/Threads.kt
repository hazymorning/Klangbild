package io.github.hazymorning.klangbild.ui.landscape

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun threadOfItsOwn(name: String, background: Boolean): CoroutineDispatcher = Dispatchers.Default
