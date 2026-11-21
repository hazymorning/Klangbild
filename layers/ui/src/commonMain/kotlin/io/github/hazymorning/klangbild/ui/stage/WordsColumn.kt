package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import io.github.hazymorning.klangbild.app.StageData
import io.github.hazymorning.klangbild.domain.ArtistBio
import io.github.hazymorning.klangbild.domain.Lyrics
import io.github.hazymorning.klangbild.render.StageGrid
import io.github.hazymorning.klangbild.ui.theme.Fonts
import kotlin.math.roundToInt

/**
 * Draws the words in a column's middle from [x] (px, the column's left edge), around [center]
 * (px, lower while a change still has them rise), at [alpha].
 */
internal fun interface ColumnWords {
    fun DrawScope.draw(x: Int, center: Float, alpha: Float)
}

/**
 * The text column with words in its middle, the song's ([LyricsText]) or the artist's
 * ([BioText]): the track's title and artist in the footer, and the slot above them while it
 * shows. Nothing stands in the middle while [words] is null, until they are set. [alpha] and
 * [rise] animate a change as for the text block: the words as the title, the slot and the footer
 * as themselves.
 */
@Composable
internal fun WordsColumn(
    model: StageModel,
    styles: StageStyles,
    modifier: Modifier,
    alpha: (TextLine) -> Float,
    rise: (TextLine) -> Float,
    textLevel: () -> Float,
    shift: () -> Float,
    words: ColumnWords?,
) {
    val layout = model.scene.layout
    val slotText = model.slotText
    val colW = (layout.columnWidth * LocalDensity.current.density).roundToInt()

    fun line(which: TextLine): Modifier = Modifier.lineMotion(which, alpha, rise)

    Layout(
        modifier = modifier.textColumn(textLevel),
        content = {
            // 0: the words
            Canvas(Modifier.fillMaxSize()) {
                val w = words ?: return@Canvas
                val a = alpha(TextLine.TITLE).coerceIn(0f, 1f)
                if (a <= 0.002f) return@Canvas
                val x = ((layout.columnLeft + shift()) * density).roundToInt()
                val center = layout.textCenter * density + (rise(TextLine.TITLE) * density).roundToInt()
                with(w) { draw(x, center, a) }
            }
            // 1: the slot above the footer, whose changes crossfade
            SlotContent(model, styles, line(TextLine.SLOT), label = "words-slot")
            // 2: the footer, which names the track itself
            val footer = rememberFooter(model.footer, styles, colW)
            if (footer != null) BasicText(footer, line(TextLine.FOOTER), style = styles.footer, maxLines = 1, overflow = TextOverflow.Ellipsis) else Box(Modifier)
        },
    ) { measurables, constraints ->
        val middle = measurables[0].measure(constraints)
        val slot = measurables[1].measure(Constraints(minWidth = colW, maxWidth = colW))
        val footer = measurables[2].measure(Constraints(maxWidth = colW))
        // The footer's descenders end where the column ends, and the slot stands on a line above it.
        val footerBaseline = footerBaseline(layout, styles)
        val slotLine = footerBaseline - (styles.footer.fontSize.toPx() * Fonts.CAP_HEIGHT).roundToInt() - (SLOT_GAP * density).roundToInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            middle.place(0, 0)
            // Read while placing: a column that glides is only placed anew.
            val x = ((layout.columnLeft + shift()) * density).roundToInt()
            if (slot.height > 0) slot.place(x, slotLine - slotBaseline(slot, slotText != null))
            if (footer.height > 0) footer.place(x, footerBaseline - footer.firstBaselineOrHeight())
        }
    }
}

/** The room the words have above and below the column's middle (dp). */
internal class WordsRoom(layout: StageGrid, styles: StageStyles) {
    private val status = layout.textCenter - (layout.statusTop + styles.label.fontSize.value * Fonts.CAP_HEIGHT)
    private val footer = layout.footerBottom - styles.footer.fontSize.value * (Fonts.DESCENT + Fonts.CAP_HEIGHT) - layout.textCenter
    // The bar's block from its top to its labels' baseline.
    private val bar = footer - SLOT_GAP - (BAR_THICKNESS + BAR_LABEL_GAP + styles.time.fontSize.value * Fonts.CAP_HEIGHT)

    /**
     * As much above the middle as below, so the lyrics fade alike up and down: to the status
     * line's baseline or to the footer's cap height, whichever is nearer, and to the bar's top
     * while it shows ([slot] 0 to 1). The artist's pages keep within it with the bar shown.
     */
    fun around(slot: Float = 0f): Float = minOf(status, footer + (bar - footer) * slot)
}

/**
 * The words of the stages a theater shows, so the words that leave in a change go from where
 * they stood: each track's lyrics ([LyricsState]), and each stage's pages about its artist
 * ([BioState]). Those are the stage's own, since the songs of one artist share the artist's bio.
 */
internal class WordsStates {
    private val lyricsStates = HashMap<Lyrics, LyricsState>()
    private val bioStates = HashMap<StageBio, BioState>()

    fun of(lyrics: Lyrics): LyricsState = lyricsStates.getOrPut(lyrics) { LyricsState(lyrics) }

    /** The pages about [bio] of the stage whose scene has the key [stage]. */
    fun of(stage: String, bio: ArtistBio): BioState = bioStates.getOrPut(StageBio(stage, bio)) { BioState(bio) }

    /** Drops the words of every stage but those [kept]. */
    fun keep(vararg kept: StageData?) {
        val stages = kept.filterNotNull()
        lyricsStates.keys.retainAll(stages.mapNotNullTo(HashSet()) { it.lyrics })
        bioStates.keys.retainAll(stages.mapNotNullTo(HashSet()) { s -> s.bio?.let { StageBio(s.scene.key, it) } })
    }

    private data class StageBio(val stage: String, val bio: ArtistBio)
}

/** The theater's words. Without one (a still), each column keeps its own. */
internal val LocalWordsStates = staticCompositionLocalOf<WordsStates?> { null }

/** From the slot's baseline to the footer's cap height under it (dp). */
private const val SLOT_GAP = 26f
