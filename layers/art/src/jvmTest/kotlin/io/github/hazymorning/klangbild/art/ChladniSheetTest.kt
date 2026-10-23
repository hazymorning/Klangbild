package io.github.hazymorning.klangbild.art

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test

/** A contact sheet of the generated artwork: nine stations, one plate each (no baseline). */
class ChladniSheetTest {
    @Test fun sheet() {
        val seeds = listOf("Radio Morgenlicht", "Deutschlandfunk Kultur", "BR-Klassik", "WDR 3", "FIP", "SWR2", "Lokale Datei", "Jazzradio", "Klangbild")
        val size = 400
        val img = BufferedImage(size * 3 + 20, size * 3 + 20, BufferedImage.TYPE_INT_RGB)
        val t0 = System.nanoTime()
        seeds.forEachIndexed { i, s ->
            val px = ChladniArt.render(ChladniArt.specFor(s), size, seed = i + 1)
            val x0 = (i % 3) * (size + 10)
            val y0 = (i / 3) * (size + 10)
            for (y in 0 until size) for (x in 0 until size) img.setRGB(x0 + x, y0 + y, px[y * size + x])
        }
        val msPerImage = (System.nanoTime() - t0) / 1e6 / seeds.size
        java.io.File("build/screenshots/art").mkdirs()
        ImageIO.write(img, "png", java.io.File("build/screenshots/art/chladni_sheet.png"))
        // Generated once per station in the background, but it should stay quick.
        assertThat(msPerImage).isLessThan(2_000.0)
        // Different stations, different figures.
        assertThat(seeds.map { ChladniArt.specFor(it) }.toSet().size).isEqualTo(seeds.size)
    }
}
