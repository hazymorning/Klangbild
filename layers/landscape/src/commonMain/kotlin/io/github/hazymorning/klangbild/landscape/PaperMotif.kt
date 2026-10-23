package io.github.hazymorning.klangbild.landscape

/** Where the music's label lies in the picture: the near bank on that side is cut wide and calm for it. */
enum class LabelSide { LEFT, RIGHT, NONE }

/**
 * A mountain cut from one piece of card and folded: its outline from foot to foot over the
 * summit (dp), and the creases running from the summit down to points on its foot. One crease
 * gives a face to the left and one to the right; two give a third face between them, turned
 * toward the viewer.
 */
class Mountain(val knots: List<Knot>, val summitX: Float, val summitY: Float, val creases: FloatArray)

/** A fir standing in the picture: its foot (dp) and its height. */
class Fir(val x: Float, val footY: Float, val height: Float)

/** A boulder of folded gray card: its middle on the ground (dp), its width and height. */
class Rock(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * The one picture: a diorama of paper on the 960 × 540 dp screen (and [OVERSCAN] beyond, so
 * the slow drift never shows an edge), looking south across a clearing in the woods to the
 * mountains. Every contour is placed by hand; the cutting itself (a slightly uneven hand, the
 * fibers of the paper) is added when the picture is baked.
 *
 * From the back: sky; the far range, folded card with snow caps of torn white paper; the near
 * range, lower and darker; two strips of forest cut as one silhouette each, the nearer one dipping
 * into the valley; the meadow of the clearing with a campfire and a few firs standing
 * on it as cut-outs with a white margin; a strip of nearer meadow; and the near bank with the
 * tall firs that frame the view. The bank under the music's label is cut wide and nearly level
 * ([Foreground]), so the label lies on calm, dark paper in every light.
 */
object PaperMotif {
    const val WIDTH = 960f
    const val HEIGHT = 540f
    const val OVERSCAN = 18f

    /** Left and right end of every contour: beyond the overscan on both sides. */
    private const val L = -OVERSCAN - 2f
    private const val R = WIDTH + OVERSCAN + 2f

    /** Below the canvas: where outlines close. */
    const val BOTTOM = HEIGHT + OVERSCAN + 4f

    /** Where the ranges' creases reach their foot (dp): hidden behind what stands in front. */
    const val FAR_FOOT = 372f
    const val NEAR_FOOT = 390f

    private fun k(x: Float, y: Float, corner: Boolean = false) = Knot(x, y, corner)

    /** The far range, back to front: five summits, the highest left of the middle. */
    val farRange = listOf(
        Mountain(listOf(k(-80f, 372f), k(-20f, 300f), k(40f, 262f), k(84f, 236f), k(112f, 216f, true), k(150f, 240f), k(196f, 262f), k(250f, 300f), k(320f, 372f)), 112f, 216f, floatArrayOf(124f)),
        Mountain(listOf(k(430f, 372f), k(480f, 290f), k(520f, 236f), k(548f, 200f), k(568f, 180f, true), k(598f, 202f), k(636f, 226f), k(690f, 268f), k(760f, 372f)), 568f, 180f, floatArrayOf(586f)),
        Mountain(listOf(k(830f, 372f), k(880f, 280f), k(912f, 240f), k(934f, 216f, true), k(966f, 236f), k(1010f, 262f), k(1060f, 372f)), 934f, 216f, floatArrayOf(944f)),
        Mountain(listOf(k(610f, 372f), k(660f, 290f), k(706f, 236f), k(732f, 206f), k(750f, 192f, true), k(782f, 210f), k(820f, 230f), k(870f, 262f), k(940f, 320f), k(990f, 372f)), 750f, 192f, floatArrayOf(742f, 822f)),
        Mountain(
            listOf(
                k(150f, 372f), k(206f, 300f), k(256f, 240f), k(296f, 194f), k(328f, 160f), k(352f, 134f, true), k(376f, 150f), k(404f, 170f),
                k(436f, 190f), k(470f, 212f), k(520f, 250f), k(590f, 314f), k(640f, 372f),
            ),
            352f, 134f, floatArrayOf(318f, 410f),
        ),
    )

    /** The near range, lower and darker, in front of the far one's feet. */
    val nearRange = listOf(
        Mountain(listOf(k(-70f, 390f), k(-10f, 318f), k(40f, 288f), k(74f, 266f, true), k(110f, 280f), k(160f, 300f), k(220f, 336f), k(270f, 390f)), 74f, 266f, floatArrayOf(88f)),
        Mountain(listOf(k(500f, 390f), k(556f, 320f), k(596f, 292f), k(626f, 274f, true), k(664f, 290f), k(716f, 310f), k(780f, 350f), k(820f, 390f)), 626f, 274f, floatArrayOf(638f)),
        Mountain(listOf(k(740f, 390f), k(792f, 318f), k(826f, 292f), k(852f, 270f, true), k(890f, 286f), k(934f, 302f), k(1000f, 340f), k(1040f, 390f)), 852f, 270f, floatArrayOf(860f)),
        Mountain(listOf(k(170f, 390f), k(230f, 328f), k(270f, 302f), k(300f, 284f, true), k(340f, 298f), k(392f, 318f), k(450f, 352f), k(490f, 390f)), 300f, 284f, floatArrayOf(312f)),
    )

    /** The far forest's ground: a gentle line all across, its firs small. */
    val woodFar = listOf(k(L, 330f), k(110f, 322f), k(250f, 334f), k(400f, 328f), k(520f, 338f), k(690f, 330f), k(830f, 336f), k(R, 326f))

    /** The near forest's ground: high at both sides, dipping into the valley in the middle. */
    val woodNear = listOf(
        k(L, 330f), k(90f, 344f), k(200f, 360f), k(320f, 378f), k(430f, 390f), k(520f, 394f), k(620f, 388f), k(730f, 372f), k(840f, 352f), k(R, 334f),
    )

    /** The clearing's meadow, and a nearer strip of it. */
    val meadow = listOf(k(L, 452f), k(140f, 440f), k(300f, 428f), k(440f, 420f), k(560f, 418f), k(700f, 424f), k(840f, 436f), k(R, 446f))
    val meadowNear = listOf(
        k(L, 494f), k(120f, 488f), k(260f, 480f), k(380f, 476f), k(470f, 478f), k(560f, 488f), k(660f, 496f), k(760f, 492f), k(880f, 484f), k(R, 488f),
    )

    /** The far range's skyline at [x] (dp): the upper edge of all its mountains, where the sky's elevations start. */
    fun skyline(x: Float): Float {
        var y = FAR_FOOT
        for (m in farLines) y = minOf(y, m.yAt(x))
        return y
    }

    private val farLines by lazy { farRange.map { Shapes.line(it.knots) } }

    /**
     * What stands in the clearing and the near banks for a label on [side]: the bank under it is cut
     * wide and stays nearly level; the fire burns on the other side of the clearing. On the
     * right, the whole foreground is the left one mirrored.
     */
    fun foreground(side: LabelSide): Foreground = when (side) {
        LabelSide.LEFT -> LEFT
        LabelSide.NONE -> Foreground(frontLeftLow, LEFT.frontRight, LEFT.standees, LEFT.birches, LEFT.frontFirs, LEFT.fireX, LEFT.fireY, LEFT.tentX, LEFT.rocks)
        LabelSide.RIGHT -> LEFT.mirrored()
    }

    /** The near bank on the left, wide: a long, low swell, level under the label, falling away late. */
    private val frontLeftWide = listOf(
        k(L, 432f), k(70f, 428f), k(170f, 427f), k(270f, 429f), k(350f, 433f), k(404f, 438f), k(438f, 452f), k(462f, 476f), k(476f, 506f), k(484f, 540f), k(486f, BOTTOM),
    )

    /** The near bank on the left, low: rising only into the corner. */
    private val frontLeftLow = listOf(
        k(L, 448f), k(60f, 452f), k(140f, 464f), k(206f, 482f), k(254f, 506f), k(282f, 532f), k(292f, BOTTOM),
    )

    /** The near bank on the right, low, under the tall firs. */
    private val frontRightLow = listOf(k(R, 470f), k(930f, 474f), k(880f, 484f), k(840f, 500f), k(812f, 522f), k(802f, 548f), k(800f, BOTTOM))

    private val LEFT = Foreground(
        frontLeft = frontLeftWide,
        frontRight = frontRightLow,
        standees = listOf(352f to 74f, 382f to 50f, 300f to 98f, 756f to 92f, 818f to 54f),
        birches = listOf(258f to 78f, 788f to 80f),
        frontFirs = listOf(Fir(6f, 450f, 232f), Fir(982f, 482f, 382f), Fir(906f, 490f, 300f)),
        fireX = 612f,
        fireY = 458f,
        tentX = 690f,
        rocks = listOf(Rock(489f, 451f, 27f, 15f), Rock(512f, 455f, 13f, 7f), Rock(734f, 459f, 14f, 8f)),
    )

    /**
     * Where the label lies (dp): its left edge on either side, its bottom, its height and the
     * widest it may be. The wide bank's top keeps clear of it by at least [LABEL_CLEARANCE].
     */
    const val LABEL_LEFT_X = 64f
    const val LABEL_RIGHT_X = 560f
    const val LABEL_BOTTOM = 508f
    const val LABEL_HEIGHT = 56f
    const val LABEL_MAX_WIDTH = 336f
    const val LABEL_CLEARANCE = 12f

    /** The near banks (contours from the screen's side down to the bottom) and what stands in the clearing. */
    class Foreground(
        val frontLeft: List<Knot>,
        val frontRight: List<Knot>,
        /** Firs standing on the meadow as cut-outs: x and height (dp); their feet on the meadow's contour. */
        val standees: List<Pair<Float, Float>>,
        /** Birches standing on the meadow the same way: x and height (dp). */
        val birches: List<Pair<Float, Float>>,
        /** The tall firs on the near banks. */
        val frontFirs: List<Fir>,
        /** The campfire's foot (dp). */
        val fireX: Float,
        val fireY: Float,
        /** The tent's middle by the fire (dp); it stands a little behind the fire. */
        val tentX: Float,
        /** Boulders on the meadows. */
        val rocks: List<Rock>,
    ) {
        /** The same, mirrored about the screen's middle. */
        fun mirrored(): Foreground {
            fun m(knots: List<Knot>) = knots.map { Knot(WIDTH - it.x, it.y, it.corner) }
            return Foreground(
                m(frontRight), m(frontLeft), standees.map { (x, h) -> WIDTH - x to h }, birches.map { (x, h) -> WIDTH - x to h },
                frontFirs.map { Fir(WIDTH - it.x, it.footY, it.height) }, WIDTH - fireX, fireY, WIDTH - tentX,
                rocks.map { Rock(WIDTH - it.x, it.y, it.w, it.h) },
            )
        }
    }
}
