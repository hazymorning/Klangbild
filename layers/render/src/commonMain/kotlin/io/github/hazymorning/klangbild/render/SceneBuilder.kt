package io.github.hazymorning.klangbild.render

import androidx.compose.ui.graphics.ImageBitmap
import io.github.hazymorning.klangbild.art.ChladniArt
import io.github.hazymorning.klangbild.art.stableHash
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
     * the square, a small one enlarged once with Lanczos; a generated plate is round and
     * transparent around the disc.
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
) {
    val screen: ScreenSpec get() = layout.screen

    val appearance: Appearance get() = palette.appearance

    /** Corner radius the artwork is drawn with (dp); none for a round plate. */
    val corner: Float get() = if (shape.round) 0f else shape.corner
}

object SceneBuilder {
    const val THUMB = 48

    /**
     * @param source decoded cover or null (radio, missing art) → generated Chladni figure.
     */
    fun build(
        key: String,
        source: ImageBitmap?,
        artSeed: String,
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
        val shape: PrintShape
        if (source != null) {
            val side = min(source.width, source.height)
            val square = IntArray(side * side)
            source.readPixels(square, (source.width - side) / 2, (source.height - side) / 2, side, side)
            // Every cover fills the square. A small one (a station's picture) is enlarged once
            // with Lanczos, never shown as a smaller print inside a frame.
            coverPixels = ImageOps.resize(square, side, side, slotPx, slotPx)
            coverSize = slotPx
            thumb = ImageOps.thumbnail(square, side, side, THUMB)
            shape = PrintShape(slotRect, corner = layout.radius)
        } else {
            coverPixels = ChladniArt.render(ChladniArt.specFor(artSeed), slotPx, seed = stableHash(artSeed))
            coverSize = slotPx
            thumb = ImageOps.thumbnail(coverPixels, slotPx, slotPx, THUMB)
            val r = ChladniArt.PLATE * slotRect.width / 2f
            shape = PrintShape(RectDp(slotRect.centerX - r, slotRect.centerY - r, 2 * r, 2 * r), round = true)
        }
        val cover = imageBitmapOf(coverPixels, coverSize, coverSize)
        val analysis = CoverPalette.analyze(thumb)
        return assemble(key, layout, cover, shape, source == null, analysis, thumb, appearance, wallTint, coverField)
    }

    /**
     * A row's small cover in the queue's list: [cover] cut square and made [side] px wide with
     * Lanczos, as the stage's cover is; without one, the generated figure for [seed].
     */
    fun rowCover(cover: ImageBitmap?, seed: String, side: Int): ImageBitmap {
        val pixels = if (cover == null) {
            ChladniArt.render(ChladniArt.specFor(seed), side, seed = stableHash(seed))
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
        val palette = CoverPalette.derive(analysis, appearance, wallTint, cover = coverField)
        val flow = layout.flow
        // The cover's colors: the wall itself with "cover colors", otherwise only for the lights.
        val field = CoverField.build(
            thumb, THUMB, analysis, appearance, strength = wallTint,
            layout = layout.takeIf { coverField }, quietInk = palette.inkQuiet,
        )
        val wall = when {
            // The field is the wall; the plain one is only its color underneath.
            coverField -> imageBitmapOf(IntArray(16) { palette.wall }, 4, 4)
            flow && appearance == Appearance.DARK -> {
                // The light above the cards takes the print's top edge.
                imageBitmapOf(FlowLight.field(FlowLight.topEdge(thumb, THUMB), layout), FlowLight.MAP_W, FlowLight.MAP_H)
            }
            appearance == Appearance.DARK -> {
                // The wall sees the outside of the picture: its edges.
                imageBitmapOf(WallGlow.field(thumb, THUMB, shape, layout.screen), WallGlow.MAP_W, WallGlow.MAP_H)
            }
            // Paper, in Cover Flow as around the single print.
            else -> imageBitmapOf(IntArray(16) { palette.wall }, 4, 4)
        }
        val flowCard = if (!flow) null else card ?: FlowCards.build(
            cover = cover,
            round = shape.round,
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
        )
    }
}
