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
 * Cover Flow ([flow]) keeps every rule but makes room for the cards beside the
 * middle one: the middle cover is 300 dp with its center 303 dp from the left, so the strip's
 * outer edge (255 dp beside it, see [FlowGeometry]) keeps 48 dp from the screen's edge. That is
 * as much as the column (from 598 dp) keeps on the right.
 */
data class StageGrid(val screen: ScreenSpec, val flow: Boolean = false) {
    private val sx = screen.widthDp / 960f
    private val sy = screen.heightDp / 540f
    private val grid = if (flow) FLOW else SINGLE

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

    /** Center line of the text block (from its first cap height to its last baseline). */
    val textCenter get() = artTop + art / 2f

    /** Where the status line's cap height hangs: a corner radius below the artwork's top edge. */
    val statusTop get() = artTop + radius

    /** Where the "Up next" line's descenders end: a corner radius above the artwork's bottom edge. */
    val footerBottom get() = artTop + art - radius

    /** Corner radius of a square artwork (dp): a hair over 2 % of its side. */
    val radius get() = RADIUS * art / SINGLE.art

    /**
     * The small cover of a row in the queue's list (dp). Its two lines stand within its straight
     * edge, a corner radius in from its top and bottom, as the column does beside the artwork.
     */
    val rowCover get() = ROW_COVER * art / SINGLE.art

    /** Margins, artwork and gap at 960 × 540 dp: the single cover's, or Cover Flow's. */
    private class Grid(val margin: Float, val art: Float, val gap: Float, val rightMargin: Float)

    companion object {
        /** The grid's width (dp): every measure here is for a screen this wide, scaled to the real one. */
        const val GRID_WIDTH = 960f

        /** Between the artwork and the text (dp at 960 × 540). */
        const val ART_TEXT_GAP = 64f

        private val SINGLE = Grid(margin = 88f, art = 368f, gap = ART_TEXT_GAP, rightMargin = 88f)
        private val FLOW = Grid(margin = 153f, art = 300f, gap = 145f, rightMargin = 48f)

        /** Corner radius of square artwork (dp at 960 × 540, for the single cover's 368 dp). */
        const val RADIUS = 8f

        /** A row's small cover in the queue's list (dp at 960 × 540). */
        const val ROW_COVER = 38f

    }
}
