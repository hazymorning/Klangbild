package io.github.hazymorning.klangbild.render


/**
 * The stage's grid on a 960 × 540 dp screen. The artwork is the heaviest element and sets
 * the composition's height: 368 dp, two thirds of the screen. The text block beside it is
 * clearly shorter (at most about 60 % of the artwork's height) and centered on it, measured
 * from the first line's cap height to the last baseline. Two quiet lines frame the column
 * within the artwork's straight edge beside it, a corner radius in from its top and bottom
 * edges, measured on what is seen: the status line (time, where the music plays) hangs there
 * with its cap height, and what comes next ends there with its descenders. So the column never
 * reaches past the artwork, whose own edges recede at its round corners (and on a dark wall
 * where a dark cover meets it). Every line in the column starts on one left edge.
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
 *
 * The player's grid ([player]) lays the stage out as a music player does: a smaller cover
 * (290 dp) high on the left, as far from the top edge as the buttons stand from the bottom edge,
 * the column beside it as wide as the room allows, and below them the progress bar across the
 * screen from margin to margin with the buttons under its right end. While the song's words take
 * the column, the song's title and artist stand under the cover.
 *
 *  ┌──────────────────────────────────────────────────────────┐
 *  │        ╭───────────╮      21:47 · Everywhere              │  ← 44 dp from the top
 *  │        │  artwork  │      words, the line sung now         │
 *  │        ╰───────────╯      in the middle                    │
 *  │        Title                                               │
 *  │        Artist                                              │
 *  │        ━━━━━━━━━━━━━━━━━━━━━━━━━━──────────────────────     │
 *  │        1:35                                     4:11       │
 *  │                                   [ Lyrics On ][ Settings ]│  ← 44 dp from the bottom
 *  └──────────────────────────────────────────────────────────┘
 */
data class StageGrid(val screen: ScreenSpec, val flow: Boolean = false, val player: Boolean = false) {
    private val sx = screen.widthDp / 960f
    private val sy = screen.heightDp / 540f
    private val grid = when {
        flow -> FLOW
        player -> PLAYER
        else -> SINGLE
    }

    /** Left margin before the artwork, and after the column on the right. */
    val margin = grid.margin * sx
    val rightMargin = grid.rightMargin * sx

    /** Side of the artwork's square. */
    val art = grid.art * sy

    /** The artwork's top edge: centered, or in the player's grid as far from the top as the buttons from the bottom. */
    val artTop = if (player && !flow) PLAYER_EDGE * sy else (screen.heightDp - art) / 2f
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

    /** The small cover of a row in the queue's list (dp): as tall as the row's two lines, from cap height to baseline. */
    val rowCover get() = ROW_COVER * art / SINGLE.art

    /** Player: where the song's title under the cover has its cap height (dp). */
    val captionTop get() = artTop + art + CAPTION_GAP * sy

    /** Player: the progress bar's left end and its width (dp), across the screen from margin to margin. */
    val barLeft get() = margin
    val barWidth get() = screen.widthDp - margin - rightMargin

    /** Player: the buttons' row, its lower edge as far from the bottom as the artwork from the top (dp). */
    val buttonsBottom get() = screen.heightDp - PLAYER_EDGE * sy
    val buttonsTop get() = buttonsBottom - BUTTON_HEIGHT * sy

    /** Player: the baseline of the progress bar's times, above the buttons (dp). */
    val timesBaseline get() = buttonsTop - TIMES_GAP * sy

    /** Margins, artwork and gap at 960 × 540 dp: the single cover's, or Cover Flow's. */
    private class Grid(val margin: Float, val art: Float, val gap: Float, val rightMargin: Float)

    companion object {
        /** The grid's width (dp): every measure here is for a screen this wide, scaled to the real one. */
        const val GRID_WIDTH = 960f

        /** Between the artwork and the text (dp at 960 × 540). */
        const val ART_TEXT_GAP = 64f

        private val SINGLE = Grid(margin = 88f, art = 368f, gap = ART_TEXT_GAP, rightMargin = 88f)
        private val FLOW = Grid(margin = 153f, art = 300f, gap = 145f, rightMargin = 48f)
        private val PLAYER = Grid(margin = 88f, art = 290f, gap = ART_TEXT_GAP, rightMargin = 88f)

        /** Player: the artwork's room above it and the buttons' room below them (dp at 960 × 540). */
        const val PLAYER_EDGE = 44f

        /** Player: a button's height (dp at 960 × 540). */
        const val BUTTON_HEIGHT = 36f

        /** Player: from the artwork's bottom edge to the title's cap height under it (dp at 960 × 540). */
        const val CAPTION_GAP = 22f

        /** Player: from the times' baseline down to the buttons (dp at 960 × 540). */
        const val TIMES_GAP = 14f

        /** Corner radius of square artwork (dp at 960 × 540, for the single cover's 368 dp). */
        const val RADIUS = 8f

        /** A row's small cover in the queue's list (dp at 960 × 540). */
        const val ROW_COVER = 30f

    }
}
