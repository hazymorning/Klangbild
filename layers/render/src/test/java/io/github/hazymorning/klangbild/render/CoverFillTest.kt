package io.github.hazymorning.klangbild.render

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.hazymorning.klangbild.color.Appearance
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Every cover fills its square on either wall, a small one too: never a frame around a smaller print. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CoverFillTest {
    @Test
    fun everyCoverFillsTheSquare() {
        val red = 0xFFC8321E.toInt()
        for (appearance in Appearance.entries) {
            for (native in listOf(120, 300, 640, 1400)) {
                val src = Bitmap.createBitmap(native, native, Bitmap.Config.ARGB_8888).apply { eraseColor(red) }
                val scene = SceneBuilder.build("k$native", src, "seed", ScreenSpec.TV_1080, appearance)
                val slot = (scene.layout.art * ScreenSpec.TV_1080.density).roundToInt()
                assertThat(scene.cover.width).isEqualTo(slot)
                // The print reaches every edge of the square: no board around it.
                for ((x, y) in listOf(1 to 1, slot / 2 to slot - 2, slot - 2 to slot / 2)) assertThat(scene.cover.getPixel(x, y)).isEqualTo(red)
            }
        }
    }
}
