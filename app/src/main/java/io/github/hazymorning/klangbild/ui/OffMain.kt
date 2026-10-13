package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [compute] off the main thread, [on] the shared threads unless told otherwise. It makes
 * bitmaps that take tens of milliseconds (a light map, a scene), and doing that elsewhere keeps
 * fades from stuttering. Until it is done, the last result stays (null at first). In a still (a
 * test's capture) it runs right away, so the picture has it.
 */
@Composable
fun <T : Any> rememberOffMain(vararg keys: Any?, on: CoroutineDispatcher = Dispatchers.Default, compute: () -> T?): T? {
    if (LocalStill.current) return remember(*keys) { compute() }
    return produceState<T?>(initialValue = null, *keys) {
        value = withContext(on) { compute() }
    }.value
}
