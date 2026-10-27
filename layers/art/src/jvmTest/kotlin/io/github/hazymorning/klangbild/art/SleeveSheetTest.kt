package io.github.hazymorning.klangbild.art

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test

/** A contact sheet of the generated covers: three artists with two albums each, a station and an input (no baseline). */
class SleeveSheetTest {
    @Test fun sheet() {
        val seeds = listOf(
            SleeveArt.Seed("Clara Wendt", "Nachtfahrt"), SleeveArt.Seed("Clara Wendt", "Abendlied"),
            SleeveArt.Seed("Theo Lind", "Morning Fog"), SleeveArt.Seed("Theo Lind", "Kreis"),
            SleeveArt.Seed("Ensemble Dessau", "Bauhaus Suite"), SleeveArt.Seed("Ensemble Dessau", "Linie"),
            SleeveArt.Seed("Radio Morgenlicht", null), SleeveArt.Seed(null, null),
        )
        val size = 400
        val img = BufferedImage(size * 4 + 30, size * 2 + 10, BufferedImage.TYPE_INT_RGB)
        seeds.forEachIndexed { i, s ->
            val px = SleeveArt.render(s, size)
            val x0 = (i % 4) * (size + 10)
            val y0 = (i / 4) * (size + 10)
            for (y in 0 until size) for (x in 0 until size) img.setRGB(x0 + x, y0 + y, px[y * size + x])
        }
        java.io.File("build/screenshots/art").mkdirs()
        ImageIO.write(img, "png", java.io.File("build/screenshots/art/sleeve_sheet.png"))
    }
}
