package io.github.hazymorning.klangbild.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [compute] on [Dispatchers.Default], for bitmaps that take tens of milliseconds (a light
 * map, a scene), so fades don't stutter. Until it finishes, the last result stays (null at
 * first). In a still (a test's capture) it runs at once, so the picture has the result.
 */
@Composable
fun <T : Any> rememberOffMain(vararg keys: Any?, compute: () -> T?): T? {
    if (LocalStill.current) return remember(*keys) { compute() }
    return produceState<T?>(initialValue = null, *keys) {
        value = withContext(Dispatchers.Default) { compute() }
    }.value
}
