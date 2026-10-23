package io.github.hazymorning.klangbild.ui.landscape

import kotlinx.coroutines.CoroutineDispatcher

/**
 * A thread of the landscape's own named [name], at a low priority if [background], for work that
 * takes seconds. Where a platform has no threads, the shared ones.
 */
internal expect fun threadOfItsOwn(name: String, background: Boolean): CoroutineDispatcher
