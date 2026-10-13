package io.github.hazymorning.klangbild.landscape

/**
 * How far back a sheet stands, for the camera's slow drift. The sky, the far mountains, the
 * clearing and the near banks each move as one layer; the nearer the layer, the more it moves
 * ([parallax]).
 */
enum class Depth(val parallax: Float) { SKY(0.15f), FAR(0.35f), MID(0.65f), FRONT(1f) }

/**
 * The sheets of the diorama, back to front. Each stands at its own distance in front of what
 * lies behind it ([depth], dp: how far and how softly its shadow falls; 0 casts none). Each is a
 * little out of focus the farther it is from the clearing ([blur], dp), and its cut top edge
 * catches the light ([rim]). [occludes]: it darkens what stands close behind it. [layer]: the
 * camera layer it moves with.
 */
enum class SheetId(val kind: PaperKind, val depth: Float, val blur: Float, val rim: Float, val layer: Depth, val occludes: Boolean = true) {
    SKY(PaperKind.WASH, 0f, 0f, 0f, Depth.SKY),
    RANGE_FAR(PaperKind.CARD, 0f, 1.0f, 0.1f, Depth.FAR),
    // The caps lie flat on their mountains: their shade is baked into the range (see occludes),
    // so no shadow spills onto the sky.
    SNOW(PaperKind.WHITE, 0f, 1.0f, 0f, Depth.FAR),
    RANGE_NEAR(PaperKind.CARD, 1f, 0.7f, 0.1f, Depth.FAR),
    WOOD_FAR(PaperKind.CARD, 1f, 0.5f, 0.1f, Depth.MID),
    WOOD_NEAR(PaperKind.CARD, 1.2f, 0.25f, 0.1f, Depth.MID),
    LEAVES(PaperKind.CARD, 0f, 0.25f, 0f, Depth.MID, occludes = false),
    MEADOW(PaperKind.CARD, 1.6f, 0f, 0.1f, Depth.MID),
    // Flowers and fallen leaves lie flat on their meadow.
    BLOOMS(PaperKind.CARD, 0f, 0f, 0f, Depth.MID, occludes = false),
    // The meadow's cut-outs are sprites of their own ([Standee]) because they sway. The birches' crowns take the leaves' colors.
    STANDEES(PaperKind.CARD, 1.2f, 0f, 0.08f, Depth.MID),
    CROWNS(PaperKind.CARD, 1.2f, 0f, 0.06f, Depth.MID),
    TENT(PaperKind.CARD, 1.2f, 0f, 0.1f, Depth.MID),
    PROPS(PaperKind.CARD, 0.9f, 0f, 0.1f, Depth.MID),
    FIREWOOD(PaperKind.CARD, 0.3f, 0f, 0.12f, Depth.MID),
    MEADOW_NEAR(PaperKind.CARD, 0.8f, 0f, 0.1f, Depth.FRONT),
    BLOOMS_NEAR(PaperKind.CARD, 0f, 0f, 0f, Depth.FRONT, occludes = false),
    FRONT_LEFT(PaperKind.CARD, 2.6f, 0.6f, 0.1f, Depth.FRONT),
    FRONT_RIGHT(PaperKind.CARD, 2.6f, 0.6f, 0.1f, Depth.FRONT),
}

/**
 * A baked piece of the picture in its own box (pixels). A sheet's box is placed on the canvas,
 * which starts [PaperMotif.OVERSCAN] dp left of and above the screen; a sprite's box is placed
 * relative to its anchor. Its pixels hold ink weights, the paper's light and coverage (see
 * [Inks]). A sheet baked smaller (soft shadows) is drawn at [scale] times its size. Once the
 * screen has made an image of it, its pixels are released ([release]): only its place is needed
 * to draw it.
 */
class Sheet(val left: Int, val top: Int, val width: Int, val height: Int, pixels: IntArray, val scale: Int = 1) {
    var pixels: IntArray = pixels
        private set

    fun release() {
        pixels = IntArray(0)
    }
}

/** The season's snow, leaves and blossom: what changes the shapes, not only the colors. */
data class SeasonShapes(
    /** Snow on the ground, 0 (none) to 1 (deep winter). */
    val snow: Float = 0f,
    /** How far snow reaches down the mountains, 0 (only the highest summit) to 1 (all of them). */
    val peaks: Float = 0f,
    /** Leaves on the broad-leaved trees, 0 (bare) to 1 (full crowns). */
    val leaves: Float = 1f,
    /** Blossom, 0 to 1. It only colors the crowns ([SkyState.blossom]); the bake ignores it. */
    val blossom: Float = 0f,
    /** Flowers in the meadows (spring and early summer), 0 to 1. */
    val flowers: Float = 0f,
    /** Fallen leaves on the meadows (autumn), 0 to 1. */
    val fallen: Float = 0f,
)

/** One tongue of the campfire's flames: crumpled tissue that flickers about its foot (canvas pixels). */
class Flame(val sheet: Sheet, val footX: Float, val footY: Float, val seed: Int)

/**
 * A cut-out standing on the meadow that sways a little about its foot ([footX], [footY], canvas
 * pixels): its pieces (relative to the foot), each with the sheet whose inks it takes, the soft
 * shadow of each piece, and its own [seed] for the breeze.
 */
class Standee(val footX: Float, val footY: Float, val parts: List<Pair<SheetId, Sheet>>, val shadows: List<Sheet>, val seed: Int)

/** Everything the picture is drawn from, for one screen size, one state of the season and one label side. */
class PaperBaked(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val density: Float,
    val sheets: Map<SheetId, Sheet>,
    /** Soft shadows of the sheets that stand apart from the one behind (half resolution, coverage only). */
    val shadows: Map<SheetId, Sheet>,
    /** The tissue clouds (relative to their left end and baseline) and their shadows. */
    val clouds: List<Sheet>,
    val cloudShadows: List<Sheet>,
    /** The fire's flames, tallest first, and the fire's foot (canvas pixels). */
    val flames: List<Flame>,
    val fireX: Float,
    val fireY: Float,
    /** The meadow's cut-outs, each drawn on its own because they sway. */
    val standees: List<Standee>,
    /** The tall firs on the near banks, also sprites of their own because they sway. */
    val frontStandees: List<Standee>,
    /** The middle of the tent's door (canvas pixels), where its lantern glows at night. */
    val doorX: Float,
    val doorY: Float,
    /** Paper discs for the sun and the moon (relative to their middle), and their shadow. */
    val sun: Sheet,
    val moon: Sheet,
    val discShadow: Sheet,
    /** A soft round light (white, coverage only) for glows; drawn scaled. */
    val glow: Sheet,
    /** How strongly the broad-leaved crowns show: they thin out in autumn and come back in spring. */
    val leafAlpha: Float,
) {
    /** Canvas pixels of a screen position in dp. */
    fun px(dp: Float): Float = (dp + PaperMotif.OVERSCAN) * density

    /**
     * The rows of the canvas (pixels, from and until) that a layer's sheets and their shadows
     * cover. A layer kept as an image needs no more rows, since it is drawn and kept only there.
     */
    fun band(depth: Depth): Pair<Int, Int> {
        var top = canvasHeight
        var bottom = 0
        for ((id, s) in sheets) {
            if (id.layer != depth) continue
            top = minOf(top, s.top)
            bottom = maxOf(bottom, s.top + s.height)
            shadows[id]?.let { sh ->
                top = minOf(top, sh.top)
                bottom = maxOf(bottom, sh.top + sh.height * sh.scale)
            }
        }
        if (bottom <= top) return 0 to canvasHeight
        // A few rows to spare: the shadows are offset when drawn.
        val spare = (8f * density).toInt()
        return (top - spare).coerceAtLeast(0) to (bottom + spare).coerceAtMost(canvasHeight)
    }
}
