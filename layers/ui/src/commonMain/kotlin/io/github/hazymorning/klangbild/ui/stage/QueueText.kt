package io.github.hazymorning.klangbild.ui.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import io.github.hazymorning.klangbild.app.QueueList
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.domain.TrackText
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.stage.Rhythm.descent
import io.github.hazymorning.klangbild.ui.stage.Rhythm.entryGap
import io.github.hazymorning.klangbild.ui.stage.Rhythm.innerGap
import io.github.hazymorning.klangbild.ui.stage.Rhythm.outerGap
import io.github.hazymorning.klangbild.ui.text.LineBalancer
import io.github.hazymorning.klangbild.ui.text.lineBalancer
import io.github.hazymorning.klangbild.ui.theme.Face
import io.github.hazymorning.klangbild.ui.theme.Figures
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.rememberFigures
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * The queue as a list in the text column (Queue: List in the menu). The current track stands at
 * the top, centered between the status line and the first row, with a smaller title and its
 * artist, album and year on one line, then its slot. Every row has the same height: a row cover,
 * the title over the artists, and the length, all in the same inks. Gaps grow from within a row
 * to between rows to around the track, so the bar plainly belongs to the track. Only whole rows
 * show, spaced so the last one ends at the column's foot ([rowSpacing]). When the slot comes or
 * goes (a key brings the bar for a moment), the rows make room or close up as it fades, and a row
 * that no longer fits fades out before it would pass the foot.
 */

/** One queue item as its row shows it. */
@Immutable
internal data class QueueRowModel(
    /** Stays the same while the item moves up the queue, so its row can glide to its new place. */
    val id: String,
    val title: String,
    /** The artists, on a second line. */
    val artists: String?,
    /** The length ("5:12"), or null without one. */
    val length: String?,
    /** The row cover, or null while it is still being made. */
    val cover: ImageBitmap?,
)

/** The list beside the cover, its rows in order. */
@Immutable
internal data class QueueModel(val rows: List<QueueRowModel>) {
    companion object {
        /** The list as the column shows [list], in the language of [t]. */
        fun of(list: QueueList, t: Texts): QueueModel {
            val others = t[Strings.artistsOthers]
            val rows = list.upNext.rows.map { row ->
                val text = TrackText.from(row.track, others)
                QueueRowModel(
                    id = row.id,
                    title = text.version?.let { "${text.title} ($it)" } ?: text.title,
                    artists = text.shortArtists,
                    length = row.track.durationMs?.let { TimeText.duration(it) },
                    cover = list.covers[row.id],
                )
            }
            return QueueModel(rows)
        }
    }
}

/** One row as measured: where its cover's top stands (px from the screen's top) and its texts. */
internal class PlannedRow(
    val id: String,
    val top: Float,
    val title: TextLayoutResult,
    val artists: TextLayoutResult?,
    val length: TextLayoutResult?,
    /** The row cover as a brush for its rounded square. */
    val cover: ShaderBrush?,
    /** The row cover's bitmap, which tells a cover made meanwhile from the one before. */
    val bitmap: ImageBitmap?,
)

/**
 * The column with the list, measured once per model: the track's lines and where each stands (px
 * from the screen's top: the cap heights of the work, the title, the version and the line of
 * artist, album and year, then the bar's top or the slot text's cap height), then the rows. The
 * rows stand lower by the [room] the slot takes, and are set for the room it takes at rest.
 */
@Stable
internal class QueuePlan(
    val styles: StageStyles,
    val work: String?,
    /** The title as it is set on its balanced lines: in [titleStyle], within [titleConstraints]. */
    val title: AnnotatedString,
    val titleStyle: TextStyle,
    val titleLines: Int,
    val titleConstraints: Constraints,
    val version: String?,
    val versionStyle: TextStyle,
    val meta: AnnotatedString?,
    val metaStyle: TextStyle,
    val workTop: Float?,
    val titleTop: Float,
    val versionTop: Float?,
    val metaTop: Float?,
    /** Where the bar's top stands, or the slot text's cap height. */
    val slotTop: Float,
    val rows: List<PlannedRow>,
    /** The room the slot takes above the rows, as it comes and goes. */
    val room: SlotRoom,
    /** Where the column ends (px). */
    val foot: Float,
    /** How far a row's artists hang below their baseline (px). */
    val rowDescent: Float,
    val appearance: Appearance,
    val columnLeft: Float,
    val columnWidth: Int,
    /** Where a row's title and artists start (px from the column's left). */
    val rowTextLeft: Float,
    val rowCap: Float,
    /** A row cover's side (px). */
    val rowCoverPx: Int,
)

/**
 * The column's list for [model], measured, or null while no list shows. A theater passes its
 * [rooms], so a stage that leaves keeps the slot's room as it stood.
 */
@Composable
internal fun rememberQueuePlan(model: StageModel, rooms: QueueRooms? = null): QueuePlan? {
    val queue = model.queue ?: return null
    if (model.lyrics != null) return null
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val styles = rememberStageStyles(model.scene.palette)
    val figures = rememberFigures(styles.time, Face.TITLE)
    // The room the slot takes, gliding as it comes and goes.
    val target = slotRoom(slotOf(model), styles, density)
    val room = remember(rooms, model.scene.key) { rooms?.of(model.scene.key, target) ?: SlotRoom(target) }
    LaunchedEffect(room, target) { room.to(target) }
    // The room it takes at rest, which the rows are set for: none for a bar that comes only for a moment.
    val rest = slotRoom(slotOf(model)?.takeIf { it !is Slot.Bar || model.barStays }, styles, density)
    return remember(model.text, model.year, model.chapter, queue, model.scene.layout, model.scene.palette, density, figures, room, rest) {
        planQueue(model, queue, styles, figures, measurer, density, room, rest)
    }
}

/**
 * The slot's room of each stage a theater shows, by its scene: a stage that leaves keeps the room
 * it stood with, even while it was gliding, so its lines leave from where they were.
 */
internal class QueueRooms {
    private val rooms = HashMap<String, SlotRoom>()

    fun of(key: String, target: Float): SlotRoom = rooms.getOrPut(key) { SlotRoom(target) }

    /** Drops the rooms of the scenes not in [keys]. */
    fun keep(keys: Set<String>) {
        rooms.keys.retainAll(keys)
    }
}

/** The room [slot] takes above the rows (px): the bar's block or the slot text's cap height, with the gap above it. */
private fun slotRoom(slot: Slot?, styles: StageStyles, density: Density): Float = when (slot) {
    null -> 0f
    is Slot.Bar -> barSpace(styles, density) + density.barBlock(styles.time)
    is Slot.Text -> barSpace(styles, density) + with(density) { styles.label.fontSize.toPx() } * Fonts.CAP_HEIGHT
}

/**
 * From the track's last baseline to the bar (px): the descenders of its line of artist, album and
 * year (set as the album is), then the [Rhythm.outerGap] at the list's largest title. So the bar's
 * room is known before the title's size and stays a step beyond the title's gaps at any size.
 */
private fun barSpace(styles: StageStyles, density: Density): Float =
    with(density) { descent(styles.album) + outerGap(styles.title(queueTitleLadder.first()), styles.album) }

/**
 * The room the slot takes above the rows (px). When the slot comes or goes, the room glides to
 * its new size over [Motion.LINE_FADE_MS] as the slot fades.
 */
@Stable
internal class SlotRoom(initial: Float) {
    private val room = Animatable(initial)
    private var from by mutableFloatStateOf(initial)

    val now: Float get() = room.value

    /** The room the glide started from. */
    val start: Float get() = from

    /** The room the glide goes to. */
    val target: Float get() = room.targetValue

    /** How far the glide has come (0 to 1). */
    val progress: Float get() = if (target == from) 1f else ((room.value - from) / (target - from)).coerceIn(0f, 1f)

    /**
     * How much of what stands in the slot may show: only while its room is mostly open (from 60%),
     * so it comes once the rows have made way and goes before the list closes up, never over it.
     * A glide turned back halfway counts from where the room is wanted again.
     */
    private val open: Float get() {
        val need = if (target > 0f) target else from
        if (need <= 0f) return 1f
        return Motion.phase(room.value / need, 0.6f, 1f, Motion.Dimmer)
    }

    /** Whether the slot comes or goes as a whole (to or from nothing), not one thing for another. */
    private val whole: Boolean get() = from <= 0f || target <= 0f

    /** The slot's alpha as a whole, while it comes or goes. */
    val slotAlpha: Float get() = if (whole) open else 1f

    /**
     * The bar's own alpha, while it takes the place of the slot text (or comes back halfway
     * through leaving): the text crossfades as it would, and the bar comes once its room is open.
     */
    val barAlpha: Float get() = if (whole) 1f else open

    /** Glides the room to [px]. */
    suspend fun to(px: Float) {
        if (px == room.targetValue) return
        from = room.value
        room.animateTo(px, tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer))
    }
}

private fun planQueue(model: StageModel, queue: QueueModel, styles: StageStyles, figures: Figures, measurer: TextMeasurer, density: Density, room: SlotRoom, rest: Float): QueuePlan {
    val layout = model.scene.layout
    val d = density.density
    val colW = (layout.columnWidth * d).roundToInt()
    fun cap(style: TextStyle) = with(density) { style.fontSize.toPx() } * Fonts.CAP_HEIGHT
    fun measure(text: AnnotatedString, style: TextStyle, width: Int = colW, lines: Int = 1) =
        measurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = lines, constraints = Constraints(maxWidth = width))
    fun height(r: TextLayoutResult) = r.lastBaseline - r.firstBaseline

    val t = model.text
    val cjk = isMostlyCjk(t.title)
    val title = EmojiText.annotate(t.title, styles.ink)
    val balancer = lineBalancer(measurer)
    val choice = pickTitleSize(title, { step -> styles.title(step, cjk) }, balancer, colW, { Float.MAX_VALUE }, d, queueTitleLadder)
        ?: queueTitleLadder.last().let { step ->
            val style = styles.title(step, cjk)
            TitleChoice(step, balancer.layout(fitLines(balancer, title, style, colW, step.lines), style, colW, step.lines, TextOverflow.Ellipsis))
        }
    val titleStyle = choice.layout.layoutInput.style
    // The gaps around the title follow its step in Latin letters ([TextPlan.titleRhythm]).
    val titleRhythm = styles.title(choice.step)
    val versionStyle = styles.version(choice.step.size)
    // The line of artist, album and year is set as the album is.
    val metaStyle = styles.album
    val meta = metaLine(t, model.chapter ?: t.album, model.year, styles, metaStyle, measurer, colW)

    // From the track's first cap height down, each line's cap height after the one before, the
    // gap above it ([Rhythm]) from the descenders of the line before. The lines are set in their
    // place once the rows are known.
    var y = 0f
    var workTop: Float? = null
    val work = t.work
    if (work != null) {
        workTop = y
        y += cap(styles.work) + height(measure(AnnotatedString(work), styles.work)) + with(density) { descent(styles.work) + innerGap(styles.work, titleRhythm) }
    }
    val titleTop = y
    y += cap(titleStyle) + height(choice.layout)
    var versionTop: Float? = null
    if (t.version != null) {
        versionTop = y + with(density) { descent(titleRhythm) + innerGap(titleRhythm, versionStyle) }
        y = versionTop + cap(versionStyle)
    }
    // The title's entry ends a title's descent below its last line, also after a version.
    var metaTop: Float? = null
    if (meta != null) {
        metaTop = y + with(density) { descent(titleRhythm) + entryGap(titleRhythm, metaStyle) }
        y = metaTop + cap(metaStyle)
    }
    // The slot under them.
    val slotTop = y + barSpace(styles, density)

    // The rows under the track, lower by the room the slot takes: as many whole ones as fit with
    // the room it takes at rest, every one as tall as its row cover, the last one ending on the
    // column's foot. The track stands in the middle between the status line and the first row.
    val statusBaseline = (layout.statusTop * d).roundToInt() + (with(density) { styles.label.fontSize.toPx() } * Fonts.CAP_HEIGHT).roundToInt()
    val foot = layout.footerBottom * d
    val coverPx = (layout.rowCover * d).roundToInt()
    val rowTextLeft = coverPx + ROW_TEXT_GAP * d
    val rowTitle = styles.rowTitle
    val rowArtists = styles.rowArtists
    val titleCap = cap(rowTitle)
    val descent = with(density) { rowArtists.fontSize.toPx() } * Fonts.DESCENT
    val spacing = rowSpacing(foot - statusBaseline - (y + rest), coverPx.toFloat(), d)
    val blockTop = statusBaseline + spacing.blockGap
    val rowsTop = blockTop + y + spacing.blockGap
    val rows = ArrayList<PlannedRow>(spacing.count)
    var top = rowsTop
    val textWidth = colW - rowTextLeft.roundToInt()
    for (row in queue.rows.take(spacing.count)) {
        val length = row.length?.let { measure(figures.of(it), styles.time) }
        val titleWidth = textWidth - (length?.let { it.size.width + (LENGTH_GAP * d).roundToInt() } ?: 0)
        rows += PlannedRow(
            id = row.id,
            top = top,
            title = measure(AnnotatedString(isolateRightToLeft(fitWords(measurer, row.title, rowTitle, titleWidth))), rowTitle, width = titleWidth),
            artists = row.artists?.let { measure(AnnotatedString(isolateRightToLeft(fitWords(measurer, it, rowArtists, textWidth))), rowArtists, width = textWidth) },
            length = length,
            cover = row.cover?.let { ShaderBrush(ImageShader(it)) },
            bitmap = row.cover,
        )
        top += coverPx + spacing.gap
    }
    return QueuePlan(
        styles = styles,
        work = t.work,
        title = choice.layout.layoutInput.text,
        titleStyle = titleStyle,
        titleLines = choice.step.lines,
        titleConstraints = choice.layout.layoutInput.constraints,
        version = t.version,
        versionStyle = versionStyle,
        meta = meta,
        metaStyle = metaStyle,
        workTop = workTop?.plus(blockTop),
        titleTop = blockTop + titleTop,
        versionTop = versionTop?.plus(blockTop),
        metaTop = metaTop?.plus(blockTop),
        slotTop = blockTop + slotTop,
        rows = rows,
        room = room,
        foot = foot,
        rowDescent = descent,
        appearance = model.scene.appearance,
        columnLeft = layout.columnLeft * d,
        columnWidth = colW,
        rowTextLeft = rowTextLeft,
        rowCap = titleCap,
        rowCoverPx = coverPx,
    )
}

/**
 * How many rows the list shows and how far apart (px, from one row cover to the next), and the
 * gap above and below the text block ([blockGap]: from the status line's baseline to its first cap
 * height, and from its last baseline to the first row cover).
 */
private class RowSpacing(val count: Int, val gap: Float, val blockGap: Float)

/**
 * The rows for [room] px from the status line's baseline to the column's foot, less the text
 * block's own height (the slot's room at rest counted), each row [row] px tall, its row cover's
 * side: as many whole rows as fit [ROW_GAP_MIN] apart with the text block [BLOCK_GAP_SHARE]
 * times as far from the status line and from the first row, the gaps grown alike up to
 * [ROW_GAP_MAX] between rows, and what is left shared above and below the text block. The count
 * follows the room, not the queue, so the track keeps its place as the queue runs short.
 */
private fun rowSpacing(room: Float, row: Float, density: Float): RowSpacing {
    val min = ROW_GAP_MIN * density
    val count = floor((room + min - 2 * BLOCK_GAP_SHARE * min) / (row + min)).toInt().coerceAtLeast(0)
    if (count == 0) return RowSpacing(0, min, room / 2)
    val gap = ((room - count * row) / (count - 1 + 2 * BLOCK_GAP_SHARE)).coerceIn(min, ROW_GAP_MAX * density)
    return RowSpacing(count, gap, (room - count * row - (count - 1) * gap) / 2)
}

/**
 * The track's one line under its title: the artists in the artist's ink and weight, then the
 * album (or the chapter) and the year, quiet. The year leaves first, then the album, then the
 * artists take a shorter form ("and others"). The last form left is cut.
 */
private fun metaLine(t: TrackText, album: String?, year: Int?, styles: StageStyles, style: TextStyle, measurer: TextMeasurer, width: Int): AnnotatedString? {
    val artists = t.artistOptions().ifEmpty { listOf(null) }
    val forms = artists.flatMap { a -> listOf(listOfNotNull(album, year?.toString()), listOfNotNull(album), emptyList()).distinct().map { a to it } }
    fun line(artist: String?, rest: List<String>): AnnotatedString? {
        if (artist == null && rest.isEmpty()) return null
        return buildAnnotatedString {
            if (artist != null) withStyle(SpanStyle(fontWeight = FontWeight(500), color = styles.muted)) { append(isolateRightToLeft(artist)) }
            for ((i, part) in rest.withIndex()) {
                if (artist != null || i > 0) append(TimeText.SEPARATOR)
                append(isolateRightToLeft(part))
            }
        }
    }
    var last: AnnotatedString? = null
    for ((artist, rest) in forms) {
        val text = line(artist, rest) ?: continue
        last = text
        if (measurer.measure(text, style, maxLines = 1, softWrap = false).size.width <= width) return text
    }
    return last
}

/**
 * [text] in at most [lines] lines of [width], cut after a whole word if it must be, never inside
 * one, and before a parenthesis it would leave open.
 */
private fun fitLines(balancer: LineBalancer, text: AnnotatedString, style: TextStyle, width: Int, lines: Int): AnnotatedString {
    fun fits(s: AnnotatedString) = balancer.lineEnds(s, style, width).size <= lines
    if (fits(text)) return text
    val plain = text.text
    var end = plain.length
    while (true) {
        end = plain.lastIndexOfAny(charArrayOf(' ', NBSP), end - 1)
        if (end <= 0) return text
        var head = plain.substring(0, end)
        if (head.count { it == '(' } > head.count { it == ')' }) head = head.substring(0, head.lastIndexOf('('))
        head = trimBeforeEllipsis(head)
        if (head.isEmpty()) continue
        // The ellipsis stays with the last word, as in [fitWords].
        val cut = buildAnnotatedString {
            append(text.subSequence(0, head.length))
            append("$NBSP…")
        }
        if (fits(cut)) return cut
    }
}

/**
 * The column with the list ([TextLayer] while [StageModel.queue] is set): the track at the top
 * with its slot. The rows under it are a layer of their own ([QueueRowsLayer]), so they can move
 * through a change. [alpha] and [rise] animate a change line by line as the text block's.
 */
@Composable
internal fun QueueText(
    model: StageModel,
    plan: QueuePlan,
    modifier: Modifier,
    alpha: (TextLine) -> Float,
    rise: (TextLine) -> Float,
    textLevel: () -> Float,
) {
    val styles = plan.styles
    val slotText = model.slotText
    fun line(which: TextLine): Modifier = Modifier.lineMotion(which, alpha, rise)

    Layout(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = textLevel().coerceIn(0f, 1f) },
        content = {
            if (plan.work != null) BasicText(plan.work, line(TextLine.WORK), style = styles.work, maxLines = 1, overflow = TextOverflow.Ellipsis) else Box(Modifier)
            BasicText(plan.title, line(TextLine.TITLE), style = plan.titleStyle, maxLines = plan.titleLines, overflow = TextOverflow.Ellipsis)
            if (plan.version != null) BasicText(plan.version, line(TextLine.VERSION), style = plan.versionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) else Box(Modifier)
            if (plan.meta != null) BasicText(plan.meta, line(TextLine.ARTIST), style = plan.metaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) else Box(Modifier)
            SlotContent(
                model,
                styles,
                line(TextLine.SLOT).graphicsLayer { this.alpha = plan.room.slotAlpha },
                label = "queue-slot",
                barAlpha = { plan.room.barAlpha },
            )
        },
    ) { measurables, constraints ->
        val colW = plan.columnWidth
        val c = Constraints(maxWidth = colW)
        val work = measurables[0].measure(c)
        // The title within the constraints it was set in.
        val title = measurables[1].measure(plan.titleConstraints)
        val version = measurables[2].measure(c)
        val meta = measurables[3].measure(c)
        val slot = measurables[4].measure(Constraints(minWidth = colW, maxWidth = colW))
        fun capOf(style: TextStyle) = (style.fontSize.toPx() * Fonts.CAP_HEIGHT).roundToInt()
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = plan.columnLeft.roundToInt()
            // Each line by its cap height, where the plan has it.
            fun Placeable.at(top: Float?, style: TextStyle, dx: Int = 0) {
                if (top == null || height == 0) return
                place(x + dx, top.roundToInt() - (firstBaselineOrHeight() - capOf(style)))
            }
            work.at(plan.workTop, styles.work)
            title.at(plan.titleTop, plan.titleStyle, (opticalIndent(plan.titleStyle.fontSize.value) * density).roundToInt())
            version.at(plan.versionTop, plan.versionStyle)
            meta.at(plan.metaTop, plan.metaStyle)
            // The slot stays in its place while it fades, and the rows make room for it as it comes.
            if (slot.height > 0) {
                if (slotText != null) slot.at(plan.slotTop, styles.label) else slot.place(x, plan.slotTop.roundToInt())
            }
        }
    }
}

/**
 * How the rows move through a change: the rows of the list before ([oldAlpha], rising by
 * [oldRise] dp) and of the new one ([newAlpha], [newRise]). With [matched], a row in both glides
 * from its old place to its new one as [glide] runs from 0 to 1, and only the others fade.
 * Without, each list comes or goes as a whole. The row covers of the rows that go stand at
 * [oldCoverAlpha] (on another background they stay whole under the new ones, as the cover does),
 * and a row cover made meanwhile comes in on a row that glides at [lateCoverAlpha].
 */
internal class RowsMotion(
    val matched: Boolean,
    val glide: () -> Float,
    val oldAlpha: () -> Float,
    val oldRise: () -> Float,
    val newAlpha: () -> Float,
    val newRise: () -> Float,
    val oldCoverAlpha: () -> Float = oldAlpha,
    val lateCoverAlpha: () -> Float = newAlpha,
) {
    companion object {
        /** A list at rest. */
        val Still = RowsMotion(matched = false, glide = { 1f }, oldAlpha = { 0f }, oldRise = { 0f }, newAlpha = { 1f }, newRise = { 0f })
    }
}

/**
 * The rows of the list [cur], and while a change runs those of the list before ([prev]), moving
 * as [motion] says. Their text is dimmed by [textLevel] (a pause), and their row covers by
 * [coverLevel] as the cover is. Rows that keep their order glide, and one whose order changed
 * goes and comes again at its new place, so no two rows ever pass through each other.
 */
@Composable
internal fun QueueRowsLayer(
    cur: QueuePlan?,
    prev: QueuePlan?,
    motion: RowsMotion,
    textLevel: () -> Float = { 1f },
    coverLevel: () -> Float = { 1f },
) {
    if (cur == null && prev == null) return
    val density = LocalDensity.current.density
    val filters = remember { CoverFilters() }
    val glidingRows = remember(prev, cur, motion.matched) {
        if (!motion.matched || prev == null || cur == null) return@remember emptyMap<String, PlannedRow>()
        val ids = glidingIds(prev.rows.map { it.id }, cur.rows.map { it.id })
        prev.rows.filter { it.id in ids }.associateBy { it.id }
    }
    Canvas(Modifier.fillMaxSize()) {
        val textNow = textLevel().coerceIn(0f, 1f)
        val coverNow = coverLevel()
        fun filterFor(appearance: Appearance): ColorFilter = filters.at(coverNow, appearance)
        val g = motion.glide().coerceIn(0f, 1f)
        if (prev != null) {
            val a = motion.oldAlpha()
            val r = motion.oldRise() * density
            val y0 = prev.room.now + r
            // Index loops: nothing allocated per frame.
            for (i in prev.rows.indices) {
                val row = prev.rows[i]
                if (row.id in glidingRows) continue
                val shown = prev.shown(row)
                drawRow(prev, row, row.top + y0, a * shown, textNow, filterFor(prev.appearance), coverAlpha = motion.oldCoverAlpha() * shown)
            }
        }
        if (cur != null) {
            val a = motion.newAlpha()
            val r = motion.newRise() * density
            for (i in cur.rows.indices) {
                val row = cur.rows[i]
                val before = glidingRows[row.id]
                if (before != null && prev != null) {
                    // A row in both lists glides to its new place.
                    val from = before.top + prev.room.now
                    val y = from + (row.top + cur.room.now - from) * g
                    val shown = prev.shown(before) + (cur.shown(row) - prev.shown(before)) * g
                    // A row cover made meanwhile comes over the one before (or in its empty place).
                    val lateCover = before.bitmap !== row.bitmap
                    if (lateCover && before.cover != null) drawCover(prev, before, y, shown, filterFor(prev.appearance))
                    drawRow(cur, row, y, shown, textNow, filterFor(cur.appearance), coverAlpha = shown * if (lateCover) motion.lateCoverAlpha() else 1f)
                } else {
                    drawRow(cur, row, row.top + cur.room.now + r, a * cur.shown(row), textNow, filterFor(cur.appearance))
                }
            }
        }
    }
}

/**
 * Of the rows [now] (their ids in order), those that glide from their places [before]: the most
 * that keep their order, so none passes another on its way. A row put in, or one whose order
 * changed, goes and comes again instead.
 */
internal fun glidingIds(before: List<String>, now: List<String>): Set<String> {
    val was = now.map { before.indexOf(it) }
    // The longest increasing subsequence of the old indexes: the most rows that keep their order.
    val length = IntArray(was.size)
    val link = IntArray(was.size) { -1 }
    for (i in was.indices) {
        if (was[i] < 0) continue
        length[i] = 1
        for (j in 0 until i) {
            if (was[j] in 0 until was[i] && length[j] + 1 > length[i]) {
                length[i] = length[j] + 1
                link[i] = j
            }
        }
    }
    var i = length.indices.maxByOrNull { length[it] } ?: return emptySet()
    val ids = HashSet<String>()
    while (i >= 0 && length[i] > 0) {
        ids += now[i]
        i = link[i]
    }
    return ids
}

/**
 * How much of [row] shows as the slot's room glides: whole while it fits within the column (half
 * a pixel over counts, for rounding). A row that no longer fits goes in the first 40% of the
 * glide, before it would pass the column's foot, and one that comes into room comes in the
 * second half, once its place is there.
 */
private fun QueuePlan.shown(row: PlannedRow): Float {
    fun fits(room: Float) = row.top + room + rowCoverPx <= foot + 0.5f
    val now = fits(room.target)
    val was = fits(room.start)
    return when {
        now && was -> 1f
        now -> Motion.phase(room.progress, 0.5f, 1f, Motion.Dimmer)
        was -> 1f - Motion.phase(room.progress, 0f, 0.4f, Motion.Dimmer)
        else -> 0f
    }
}

/**
 * One row with its cover's top at [y] (px from the screen's top), as strong as [alpha]: its text
 * dimmed by [textLevel], and its cover drawn through [filter] at [coverAlpha], as the cover is.
 */
private fun DrawScope.drawRow(plan: QueuePlan, row: PlannedRow, y: Float, alpha: Float, textLevel: Float, filter: ColorFilter, coverAlpha: Float = alpha) {
    drawCover(plan, row, y, coverAlpha, filter)
    if (alpha <= 0.003f) return
    val textAlpha = alpha * textLevel
    val x = plan.columnLeft
    // The two lines stand within the row cover's straight edge, a corner radius in from its top and bottom.
    val inset = ROW_COVER_CORNER * density
    val baseline = y + inset + plan.rowCap
    drawText(row.title, topLeft = Offset(x + plan.rowTextLeft, baseline - row.title.firstBaseline), alpha = textAlpha)
    row.artists?.let { drawText(it, topLeft = Offset(x + plan.rowTextLeft, y + plan.rowCoverPx - inset - plan.rowDescent - it.firstBaseline), alpha = textAlpha) }
    row.length?.let { drawText(it, topLeft = Offset(x + plan.columnWidth - it.size.width, baseline - it.firstBaseline), alpha = textAlpha) }
}

/** [row]'s cover with its top at [y], on whole pixels so it stays sharp. */
private fun DrawScope.drawCover(plan: QueuePlan, row: PlannedRow, y: Float, alpha: Float, filter: ColorFilter) {
    val brush = row.cover ?: return
    if (alpha <= 0.003f) return
    translate(plan.columnLeft.roundToInt().toFloat(), y.roundToInt().toFloat()) {
        val side = plan.rowCoverPx.toFloat()
        drawRoundRect(brush, size = Size(side, side), cornerRadius = CornerRadius(ROW_COVER_CORNER * density), alpha = alpha, colorFilter = filter)
    }
}

/** The title in the list: from 34 sp down, two lines at most, so the rows always keep their room. */
private val queueTitleLadder = listOf(
    TitleStep(34f, 2, 420, 1.14f, -0.005f),
    TitleStep(30f, 2, 420, 1.15f, -0.005f),
    TitleStep(26f, 2, 430, 1.16f, 0f),
    TitleStep(24f, 2, 430, 1.16f, 0f),
)

/**
 * The smallest gap between two row covers (dp), so the gap from one row's artists to the next
 * row's title stays well over twice the gap within a row.
 */
private const val ROW_GAP_MIN = 12f

/** The largest gap between two row covers (dp), so the rows still read as one list. */
private const val ROW_GAP_MAX = 22f

/**
 * The gap above the text block (from the status line) and below it (from its bar's times to the
 * first row cover), as a share of the gap between two rows: the widest gaps in the column, well
 * over the one above the bar, so the bar belongs to the text block.
 */
private const val BLOCK_GAP_SHARE = 3f

/** From a row cover to its title (dp). */
private const val ROW_TEXT_GAP = 12f

/** At least this much room between a row's title and its length (dp). */
private const val LENGTH_GAP = 16f

/** A row cover's corners are rounded a little (dp). */
internal const val ROW_COVER_CORNER = 2.5f
