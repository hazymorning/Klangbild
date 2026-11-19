package io.github.hazymorning.klangbild.render

import androidx.compose.ui.graphics.ImageBitmap
import io.github.hazymorning.klangbild.art.SleeveArt
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.color.CoverAnalysis
import io.github.hazymorning.klangbild.color.CoverPalette
import io.github.hazymorning.klangbild.color.ScenePalette
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything the UI needs to draw one track, precomputed off the main thread. The UI only
 * draws these bitmaps; there is no image processing per frame.
 */
class ArtworkScene(
    val key: String,
    val layout: StageGrid,
    /**
     * The print at its exact on-screen pixel size (drawn 1:1, no GPU scaling): every cover fills
     * the square, a small one enlarged once with Lanczos, and so does the generated one.
     */
    val cover: ImageBitmap,
    /** The print's outline on the wall. */
    val shape: PrintShape,
    val coverGenerated: Boolean,
    /**
     * The wall around the print, scaled to the whole screen. In the dark it is the print's light
     * on black ([WallGlow]; in Cover Flow the pool of light above the cards, [FlowLight]); in the
     * light it is plain paper. There is no shadow, no frame and no line around the print.
     */
    val wall: ImageBitmap,
    val palette: ScenePalette,
    val analysis: CoverAnalysis,
    /** 48 × 48 thumbnail of the cover, kept to re-light the scene without the source. */
    val thumb: IntArray,
    /** How much of the cover's color the light wall takes (the light level setting). */
    val wallTint: Float = 1f,
    /** The print as a card of the cover flow, with its mirror image; null beside a single cover. */
    val card: FlowCard? = null,
    /**
     * The cover's colors as a field ([CoverField]). With [coverField] it is the wall behind
     * the picture; the lights always take their colors from it.
     */
    val field: FieldScene? = null,
    /** The cover's colors are the wall (the "cover colors" background) instead of [wall]. */
    val coverField: Boolean = false,
    /**
     * Where the dark background's [wall] has any light (dp on the screen, with the reach of its
     * soft scaling and a little to spare): beyond it the wall is black, as [ScenePalette.wall]
     * is. Empty where it has none, null on paper and with the cover colors.
     */
    val wallLit: RectDp? = null,
) {
    val screen: ScreenSpec get() = layout.screen

    val appearance: Appearance get() = palette.appearance

    /** Corner radius the artwork is drawn with (dp). */
    val corner: Float get() = shape.corner
}

object SceneBuilder {
    const val THUMB = 48

    /**
     * @param source decoded cover, or null (a station, missing art): then the generated cover
     *   ([SleeveArt]) with its foil from [artFoil] and its view from [artView].
     */
    fun build(
        key: String,
        source: ImageBitmap?,
        artFoil: String?,
        artView: String?,
        screen: ScreenSpec,
        appearance: Appearance = Appearance.DARK,
        wallTint: Float = 1f,
        flow: Boolean = false,
        coverField: Boolean = false,
    ): ArtworkScene {
        val layout = StageGrid(screen, flow)
        val slotPx = (layout.art * screen.density).roundToInt()
        val slotRect = layout.artRect
        val coverPixels: IntArray
        val coverSize: Int
        val thumb: IntArray
        if (source != null) {
            val side = min(source.width, source.height)
            val square = IntArray(side * side)
            source.readPixels(square, (source.width - side) / 2, (source.height - side) / 2, side, side)
            // Every cover fills the square. A small one (a station's picture) is enlarged once
            // with Lanczos, never shown as a smaller print inside a frame.
            coverPixels = ImageOps.resize(square, side, side, slotPx, slotPx)
            coverSize = slotPx
            thumb = ImageOps.thumbnail(square, side, side, THUMB)
        } else {
            coverPixels = SleeveArt.render(SleeveArt.Seed(artFoil, artView), slotPx)
            coverSize = slotPx
            thumb = ImageOps.thumbnail(coverPixels, slotPx, slotPx, THUMB)
        }
        val shape = PrintShape(slotRect, corner = layout.radius)
        val cover = imageBitmapOf(coverPixels, coverSize, coverSize)
        // On its way to the GPU now, not in the first frames of the change that shows it.
        cover.prepareToDraw()
        val analysis = CoverPalette.analyze(thumb)
        return assemble(key, layout, cover, shape, source == null, analysis, thumb, appearance, wallTint, coverField)
    }

    /**
     * A row's small cover in the queue's list: [cover] cut square and made [side] px wide with
     * Lanczos, as the stage's cover is; without one, the generated one for [artFoil] and [artView].
     */
    fun rowCover(cover: ImageBitmap?, artFoil: String?, artView: String?, side: Int): ImageBitmap {
        val pixels = if (cover == null) {
            SleeveArt.render(SleeveArt.Seed(artFoil, artView), side)
        } else {
            val s = min(cover.width, cover.height)
            val square = IntArray(s * s)
            cover.readPixels(square, (cover.width - s) / 2, (cover.height - s) / 2, s, s)
            ImageOps.resize(square, s, s, side, side)
        }
        return imageBitmapOf(pixels, side, side)
    }

    /** Re-light an existing scene (appearance, wall tint or background changed) without the source image. */
    fun relight(scene: ArtworkScene, appearance: Appearance, wallTint: Float, coverField: Boolean = scene.coverField): ArtworkScene {
        val sameWall = scene.appearance == appearance
        return assemble(
            scene.key, scene.layout, scene.cover, scene.shape, scene.coverGenerated,
            scene.analysis, scene.thumb, appearance, wallTint, coverField,
            // The card only changes with the wall (its mirror image).
            card = scene.card.takeIf { sameWall },
        )
    }

    private fun assemble(
        key: String,
        layout: StageGrid,
        cover: ImageBitmap,
        shape: PrintShape,
        generated: Boolean,
        analysis: CoverAnalysis,
        thumb: IntArray,
        appearance: Appearance,
        wallTint: Float,
        coverField: Boolean,
        card: FlowCard? = null,
    ): ArtworkScene {
        val palette = CoverPalette.derive(analysis, appearance, wallTint, coverField)
        val flow = layout.flow
        // The cover's colors: the wall itself with "cover colors", otherwise only for the lights.
        val field = CoverField.build(
            thumb, THUMB, analysis, appearance, strength = wallTint,
            layout = layout.takeIf { coverField }, quietInk = palette.inkQuiet,
        )
        // The light on the dark background, as a map over the whole screen.
        val light = when {
            coverField || appearance != Appearance.DARK -> null
            // The light above the cards takes the print's top edge.
            flow -> Light(FlowLight.field(FlowLight.topEdge(thumb, THUMB), layout), FlowLight.MAP_W, FlowLight.MAP_H)
            // The wall sees the outside of the picture: its edges.
            else -> Light(WallGlow.field(thumb, THUMB, shape, layout.screen), WallGlow.MAP_W, WallGlow.MAP_H)
        }
        // Without it, paper (in Cover Flow as around the single print), or with the cover colors
        // the plain color under the field, which is the wall.
        val wall = if (light == null) imageBitmapOf(IntArray(16) { palette.wall }, 4, 4) else imageBitmapOf(light.pixels, light.width, light.height)
        wall.prepareToDraw()
        val flowCard = if (!flow) null else card ?: FlowCards.build(
            cover = cover,
            cornerPx = layout.radius * layout.screen.density,
            geometry = FlowGeometry(layout),
            density = layout.screen.density,
            appearance = appearance,
            lightness = analysis.meanL,
        )
        return ArtworkScene(
            key = key,
            layout = layout,
            cover = cover,
            shape = shape,
            coverGenerated = generated,
            wall = wall,
            palette = palette,
            analysis = analysis,
            thumb = thumb,
            wallTint = wallTint,
            card = flowCard,
            field = field,
            coverField = coverField,
            wallLit = light?.lit(layout.screen),
        )
    }

    /** A map of the light on the dark background, [width] × [height] over the whole screen. */
    private class Light(val pixels: IntArray, val width: Int, val height: Int) {
        /** Where it isn't black ([ArtworkScene.wallLit]). */
        fun lit(screen: ScreenSpec): RectDp {
            var x0 = width
            var y0 = height
            var x1 = -1
            var y1 = -1
            for (y in 0 until height) for (x in 0 until width) {
                if (pixels[y * width + x] and 0xFFFFFF == 0) continue
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
            if (x1 < 0) return RectDp(0f, 0f, 0f, 0f)
            // Scaled softly, a texel lights the screen up to a texel beyond its center; one more to spare.
            val sx = screen.widthDp / width
            val sy = screen.heightDp / height
            val left = ((x0 - LIT_MARGIN) * sx).coerceAtLeast(0f)
            val top = ((y0 - LIT_MARGIN) * sy).coerceAtLeast(0f)
            val right = ((x1 + 1 + LIT_MARGIN) * sx).coerceAtMost(screen.widthDp)
            val bottom = ((y1 + 1 + LIT_MARGIN) * sy).coerceAtMost(screen.heightDp)
            return RectDp(left, top, right - left, bottom - top)
        }
    }

    /** Texels around the lit ones that the soft scaling still reaches, with one to spare. */
    private const val LIT_MARGIN = 2
}
