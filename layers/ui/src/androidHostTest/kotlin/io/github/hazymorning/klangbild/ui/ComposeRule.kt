package io.github.hazymorning.klangbild.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * The compose rule of the render tests. It is the first version of the rule, which runs work
 * at once: the stills and the frame sequences were made for that, and the second version
 * queues work instead.
 */
@Suppress("DEPRECATION") // The second version would change when work runs in every render test.
fun composeRule(): ComposeContentTestRule = androidx.compose.ui.test.junit4.createComposeRule()
