package io.github.hazymorning.klangbild.render


/**
 * The stage's grid on a 960 × 540 dp screen. The artwork is the heaviest element and sets the
 * composition's height: 368 dp, two thirds of the screen. The text block beside it is clearly
 * shorter (at most 62 % of the artwork's height, `BLOCK_SHARE` in the UI) and centered on it,
 * measured from the first line's cap height to the last baseline. Two quiet lines frame the
 * column within the artwork's straight edge beside it, a corner radius in from its top and
 * bottom edges, measured on what is seen: the status line (time, where the music plays) hangs
 * there with its cap height, and what comes next ends there with its descenders. So the column
 * never reaches past the artwork, whose own edges recede at its round corners (and on a dark
 * wall where a dark cover meets it). Every line in the column starts on one left edge.
 *
 * The group stands exactly in the middle of the screen: as much room above the artwork as
 * below it, as much left of it as right of the column (the progress bar's end).
 *
 *  ┌──────────────────────────────────────────────────────────┐
 *  │            88            368          64       352     88 │
 *  │        ╭──────────────╮                                  │  ← artwork top
 *  │        │              │      21:47 · Everywhere          │  ← a corner radius below
 *  │        │              │      Title                       │
 *  │        │   artwork    │      Artist                      │  ← artwork center
 *  │        │              │      Album · Year                │
 *  │        │              │      ━━━━━━━━━━━━──────────      │
 *  │        │              │      1:35                4:11    │
 *  │        │              │      Up next  …                  │  ← a corner radius above
 *  │        ╰──────────────╯                                  │  ← artwork bottom
 *  └──────────────────────────────────────────────────────────┘
 *
 * Cover Flow ([flow]) keeps every rule but makes room for the cards beside the middle one: the
 * middle cover is 312 dp with its center 317 dp from the left, so the strip's outer edge (245.75
 * dp beside it, see [FlowGeometry]) keeps 71.25 dp from the screen's edge. That is as much as the
 * column (from 602.75 dp, 286 dp wide) keeps on the right, and above and below the cover stay 114
 * dp. The cover stands on whole pixels at 1080p.
 *
 * With the cover edge to edge ([edge], beside a single cover only), it fills the screen's height
 * at its left edge, square and uncut, its corners square. It reaches [BLEED] past the top, the
 * bottom and the left, so the drift against burn-in never shows an edge. The column stands in the
 * middle of what is left, as far from the cover as from the screen's edge, and its frame (the
 * status line, the footer) stands where it does beside the single cover.
 */
data class StageGrid(val screen: ScreenSpec, val flow: Boolean = false, val edge: Boolean = false) {
    private val sx = screen.widthDp / 960f
    private val sy = screen.heightDp / 540f
    private val grid = when {
        flow -> FLOW
        edge -> EDGE
        else -> SINGLE
    }

    /** The cover the column's frame and a queue row's small cover take their measures from: the single cover's when the cover goes edge to edge. */
    private val frame = if (grid === EDGE) SINGLE else grid
    private val frameArt = frame.art * sy
    private val frameTop = (screen.heightDp - frameArt) / 2f

    /** Space left of the artwork. */
    val margin = grid.margin * sx

    /** Space right of the column. */
    val rightMargin = grid.rightMargin * sx

    /** Side of the artwork's square. */
    val art = grid.art * sy
    val artTop = (screen.heightDp - art) / 2f
    val artRect: RectDp get() = RectDp(margin, artTop, art, art)

    /** Space between artwork and text. */
    val gap = grid.gap * sx
    val columnLeft get() = margin + art + gap
    val columnWidth get() = screen.widthDp - rightMargin - columnLeft

    /** The artwork's side the column takes its measures from: the single cover's while the cover is edge to edge. */
    val columnArt: Float get() = frameArt

    /** Center line of the text block (from its first cap height to its last baseline). */
    val textCenter get() = artTop + art / 2f

    /** Where the status line's cap height hangs: a corner radius below the artwork's top edge. */
    val statusTop get() = frameTop + frameRadius

    /** Where the "Up next" line's descenders end: a corner radius above the artwork's bottom edge. */
    val footerBottom get() = frameTop + frameArt - frameRadius

    /** Corner radius of a square artwork (dp): a hair over 2 % of its side; none for a cover edge to edge. */
    val radius get() = if (grid === EDGE) 0f else frameRadius

    private val frameRadius get() = RADIUS * frameArt / SINGLE.art

    /**
     * The small cover of a row in the queue's list (dp). Its two lines stand within its straight
     * edge, a corner radius in from its top and bottom, as the column does beside the artwork.
     */
    val rowCover get() = ROW_COVER * frameArt / SINGLE.art

    /** Margins, artwork and gap at 960 × 540 dp: the single cover's, Cover Flow's, or the cover's edge to edge. */
    private class Grid(val margin: Float, val art: Float, val gap: Float, val rightMargin: Float)

    companion object {
        /** The grid's width (dp): every measure here is for a screen this wide, scaled to the real one. */
        const val GRID_WIDTH = 960f

        /** Between the artwork and the text (dp at 960 × 540). */
        const val ART_TEXT_GAP = 64f

        private val SINGLE = Grid(margin = 88f, art = 368f, gap = ART_TEXT_GAP, rightMargin = 88f)
        private val FLOW = Grid(margin = 161f, art = 312f, gap = 129.75f, rightMargin = 71.25f)

        /** How far a cover edge to edge reaches past the screen's edges (dp): more than the drift against burn-in. */
        const val BLEED = 4f

        /** The cover edge to edge, and the column in the middle of the 416 dp it leaves. */
        private val EDGE = Grid(margin = -BLEED, art = 540f + 2 * BLEED, gap = 64f, rightMargin = 64f)

        /** Corner radius of square artwork (dp at 960 × 540, for the single cover's 368 dp). */
        const val RADIUS = 8f

        /** A row's small cover in the queue's list (dp at 960 × 540). */
        const val ROW_COVER = 38f

    }
}
