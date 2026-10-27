package io.github.hazymorning.klangbild.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isTrue
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.render.QrCode
import io.github.hazymorning.klangbild.render.ScreenSpec
import io.github.hazymorning.klangbild.setup.SetupInfo
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test

/** The setup's QR code is its scene's cover just as it was made, so its modules stay on whole pixels. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SetupArtTest {
    private fun ImageBitmap.pixels() = IntArray(width * height).also { readPixels(it) }

    @Test
    fun theCardIsNeverResampled() {
        val qr = QrCode.matrix("http://192.168.1.23:8765/?code=482913")
        val info = SetupInfo(url = "http://192.168.1.23:8765", code = "482913", qr = qr)
        // On a 720p screen the cover's side is 490.67 px.
        for (screen in listOf(ScreenSpec(1280, 720, 1.33125f), ScreenSpec.TV_1080)) {
            val cover = SetupArt.scene(info, screen).cover
            assertThat(cover.pixels().contentEquals(QrCard.of(qr, cover.width, Appearance.DARK).pixels()), name = "${screen.heightPx}p").isTrue()
        }
    }
}
