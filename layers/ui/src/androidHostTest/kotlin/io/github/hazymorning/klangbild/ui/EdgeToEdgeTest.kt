package io.github.hazymorning.klangbild.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import io.github.hazymorning.klangbild.art.SyntheticCover
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.ui.stage.Stage
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/**
 * The cover edge to edge reaches the screen's left, top and bottom edges wherever the screen
 * drifts against burn-in, and its corners are square: measured on the rendered pixels (1080p,
 * 2 px = 1 dp) of an almost white cover on the dark background.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class EdgeToEdgeTest {
    @get:Rule
    val compose = composeRule()

    @Test
    fun `the cover fills the screen's height at its left edge wherever the screen drifts`() {
        val track = Fixtures.track(SyntheticCover.WHITE_SPACE)
        val scene = Fixtures.scene(track, kind = StageGrid.Kind.EDGE)
        var dx by mutableStateOf(0f)
        var dy by mutableStateOf(0f)
        compose.setContent {
            Fixtures.Still {
                Box(
                    Modifier.fillMaxSize().background(Color.Black).graphicsLayer {
                        translationX = dx
                        translationY = dy
                    },
                ) {
                    Stage(Fixtures.model(track, next = Fixtures.spiegel, scene = scene))
                }
            }
        }
        // The screen drifts up to 3 dp across and 2.4 dp up and down, here a little farther.
        for ((x, y) in listOf(0 to 0, 6 to 5, -6 to -5, 6 to -5, -6 to 5)) {
            dx = x.toFloat()
            dy = y.toFloat()
            val px = compose.pixels()
            val right = (scene.layout.artRect.right * 2f).toInt() + x
            val case = "drift $x, $y"
            for (row in 0 until px.height step 7) assertThat(px.level(0, row), name = "$case: the left edge at $row").isGreaterThan(150)
            for (column in 0 until right - 2 step 7) {
                assertThat(px.level(column, 0), name = "$case: the top edge at $column").isGreaterThan(150)
                assertThat(px.level(column, px.height - 1), name = "$case: the bottom edge at $column").isGreaterThan(150)
            }
            // The cover's right edge is straight up to its corners: square, not rounded.
            for (row in listOf(0, px.height / 2, px.height - 1)) {
                assertThat(px.level(right - 3, row), name = "$case: inside its right edge at $row").isGreaterThan(150)
                assertThat(px.level(right + 3, row), name = "$case: beside its right edge at $row").isLessThan(100)
            }
        }
    }
}
