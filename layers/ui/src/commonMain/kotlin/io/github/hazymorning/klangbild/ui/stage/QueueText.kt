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
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import io.github.hazymorning.klangbild.app.QueueList
import io.github.hazymorning.klangbild.color.Appearance
import io.github.hazymorning.klangbild.domain.PlayerStatus
import io.github.hazymorning.klangbild.domain.TimeText
import io.github.hazymorning.klangbild.domain.TrackText
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import io.github.hazymorning.klangbild.ui.Motion
import io.github.hazymorning.klangbild.ui.text.BalancedBreaker
import io.github.hazymorning.klangbild.ui.theme.Face
import io.github.hazymorning.klangbild.ui.theme.Figures
import io.github.hazymorning.klangbild.ui.theme.Fonts
import io.github.hazymorning.klangbild.ui.theme.faces
import io.github.hazymorning.klangbild.ui.theme.rememberFigures
import io.github.hazymorning.klangbild.ui.theme.textType
import kotlin.math.roundToInt

/*
 * The queue as a list in the text column (the setting Queue: List), beside the cover, which does
 * not move. The song stands at the top, hung under the status line, its title a step smaller than
 * on its own and its artist, album and year on one line, then its bar as everywhere. Under it "Up
 * next" says how many tracks follow and how long they play, and the rows follow. Each quiet line
 * stands as far from what it leads as the other (the status line from the song, "Up next" from the
 * first row), and the song and the list stand further apart than anything within either, so the
 * bar plainly belongs to the song. Every row is alike and as tall as the next: its small cover,
 * its title over its artists, its length; so the list keeps one even step. Only whole rows show,
 * all in the same inks; how many follow, "Up next" says. When the bar comes or goes (a key brings
 * it for a moment), the list makes room for it or closes up as it fades, and a row that no longer
 * fits goes before it would pass the column's foot.
 */

/** One song of the list as the column shows it. */
@Immutable
data class QueueRowModel(
    /** Stays the same while the song moves up the queue, so a row can glide to its new place. */
    val id: String,
    val title: String,
    /** The artists, on a second line. */
    val artists: String?,
    /** "5:12"; null without a length. */
    val time: String?,
    /** The small cover; null while it is still being made. */
    val cover: ImageBitmap?,
)

/** The list beside the cover, its rows in order. */
@Immutable
data class QueueModel(val rows: List<QueueRowModel>) {
    companion object {
        /** The list as the column shows [list], in the words of [t]. */
        fun of(list: QueueList, t: Texts): QueueModel {
            val others = t[Strings.artistsOthers]
            val rows = list.upNext.rows.map { row ->
                val text = TrackText.from(row.track, others)
                QueueRowModel(
                    id = row.id,
                    title = text.version?.let { "${text.title} ($it)" } ?: text.title,
                    artists = text.shortArtists,
                    time = row.track.durationMs?.let { TimeText.formatClock(it) },
                    cover = list.covers[row.id],
                )
            }
            return QueueModel(rows)
        }
    }
}

/** One row as measured: where its cap height stands (px from the screen's top) and its texts. */
internal class PlannedRow(
    val id: String,
    val top: Float,
    val title: TextLayoutResult,
    val artists: TextLayoutResult?,
    val time: TextLayoutResult?,
    /** The small cover as a brush for its rounded square ([bitmap] its picture), and its side (px). */
    val cover: ShaderBrush?,
    val bitmap: ImageBitmap?,
    val coverPx: Int,
)

/**
 * The column with the list, measured once per model: the song's lines, and where every part
 * stands (px from the screen's top: the cap heights of the work, the title, the version, the line
 * of artist, album and year, the bar's top or the cap height of a line in its place, and "Up
 * next"'s cap height), then the rows. "Up next" and the rows stand lower by the [room] the bar
 * takes; the rows are those that fit without it.
 */
@Stable
internal class QueuePlan(
    val styles: StageStyles,
    val work: String?,
    /** The title on its balanced lines: set in [titleStyle], exactly [titleWidth] px wide. */
    val title: AnnotatedString,
    val titleStyle: TextStyle,
    val titleLines: Int,
    val titleWidth: Int,
    val version: String?,
    val versionStyle: TextStyle,
    val meta: AnnotatedString?,
    val metaStyle: TextStyle,
    val workTop: Float?,
    val titleTop: Float,
    val versionTop: Float?,
    val metaTop: Float?,
    /** Where the bar's top stands, or the cap height of a line in its place. */
    val slotTop: Float,
    val headingTop: Float,
    val rows: List<PlannedRow>,
    /** Where the rows begin (px). */
    val rowsTop: Float,
    /** The room the bar takes above "Up next", as it comes and goes. */
    val room: SlotRoom,
    /** Where the column ends (px), and how far a row's artists hang below its small cover. */
    val foot: Float,
    val rowDescent: Float,
    val appearance: Appearance,
    val columnLeft: Float,
    val columnWidth: Int,
    val markWidth: Float,
    val rowCap: Float,
)

/**
 * The column's list for [model], measured; null while no list shows. A theater passes its
 * [rooms], so a stage that leaves keeps the bar's room as it stood.
 */
@Composable
internal fun rememberQueuePlan(model: StageModel, rooms: QueueRooms? = null): QueuePlan? {
    val queue = model.queue ?: return null
    if (model.lyrics != null) return null
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val faces = faces()
    val styles = remember(model.scene.palette, faces) { StageStyles(model.scene.palette, faces) }
    val figures = rememberFigures(styles.time, Face.TITLE)
    // The room the bar (or a line in its place) takes, gliding as it comes and goes.
    val target = slotRoom(bottomOf(model), styles, density)
    val room = remember(rooms, model.scene.key) { rooms?.of(model.scene.key, target) ?: SlotRoom(target) }
    LaunchedEffect(room, target) { room.to(target) }
    return remember(model.text, model.year, model.chapter, queue, model.scene.layout, model.scene.palette, density, figures, room) {
        planQueue(model, queue, styles, figures, measurer, density, room)
    }
}

/**
 * The bar's room of each stage a theater shows, by its scene: a stage that leaves keeps the room
 * it stood with, even while it was gliding, so its lines leave from where they were.
 */
internal class QueueRooms {
    private val rooms = HashMap<String, SlotRoom>()

    fun of(key: String, target: Float): SlotRoom = rooms.getOrPut(key) { SlotRoom(target) }

    /** Lets go of the rooms of stages no longer shown. */
    fun keep(keys: Set<String>) {
        rooms.keys.retainAll(keys)
    }
}

/** The room [slot] takes above "Up next" (px): the bar's block or a line's cap height, with the gap above it. */
private fun slotRoom(slot: String?, styles: StageStyles, density: Density): Float {
    val d = density.density
    return when (slot) {
        null -> 0f
        // The bar's block, from its top to its labels' baseline, as the bar measures it.
        BAR -> BAR_GAP * d + (BAR_THICKNESS * d).roundToInt() + (BAR_LABEL_GAP * d).roundToInt() + (with(density) { styles.time.fontSize.toPx() } * Fonts.CAP_HEIGHT).roundToInt()
        else -> BAR_GAP * d + with(density) { styles.label.fontSize.toPx() } * Fonts.CAP_HEIGHT
    }
}

/**
 * The room the bar takes above "Up next" (px). When the bar or a line in its place comes or
 * goes, the room glides to its new size in [Motion.LINE_FADE_MS] as the bar fades.
 */
@Stable
internal class SlotRoom(initial: Float) {
    private val room = Animatable(initial)
    private var from by mutableFloatStateOf(initial)

    /** The room now. */
    val now: Float get() = room.value

    /** The room the glide started from, and the one it goes to. */
    val start: Float get() = from
    val target: Float get() = room.targetValue

    /** How far the glide has come (0 to 1). */
    val progress: Float get() = if (target == from) 1f else ((room.value - from) / (target - from)).coerceIn(0f, 1f)

    /**
     * How much of what stands in the bar's place may show: only while its room is mostly open, so
     * it comes once "Up next" has made way and goes before the list closes up, never over it. A
     * glide turned back halfway counts from where the room is wanted again.
     */
    private val open: Float get() {
        val need = if (target > 0f) target else from
        if (need <= 0f) return 1f
        return Motion.phase(room.value / need, 0.6f, 1f, Motion.Dimmer)
    }

    /** Whether the place comes or goes as a whole (to or from nothing), not one thing for another. */
    private val whole: Boolean get() = from <= 0f || target <= 0f

    /** The bar's place as a whole, while it comes or goes. */
    val line: Float get() = if (whole) open else 1f

    /**
     * The bar alone, while it takes the place of a line of words (or comes back halfway through
     * leaving): that line cross-fades as it would, and the bar comes once its room is open.
     */
    val bar: Float get() = if (whole) 1f else open

    suspend fun to(px: Float) {
        if (px == room.targetValue) return
        from = room.value
        room.animateTo(px, tween(Motion.LINE_FADE_MS, easing = Motion.Dimmer))
    }
}

private fun planQueue(model: StageModel, queue: QueueModel, styles: StageStyles, figures: Figures, measurer: TextMeasurer, density: Density, room: SlotRoom): QueuePlan {
    val layout = model.scene.layout
    val d = density.density
    val colW = (layout.columnWidth * d).roundToInt()
    fun cap(style: TextStyle) = with(density) { style.fontSize.toPx() } * Fonts.CAP_HEIGHT
    fun measure(text: AnnotatedString, style: TextStyle, width: Int = colW, lines: Int = 1) =
        measurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = lines, constraints = Constraints(maxWidth = width))
    fun height(r: TextLayoutResult) = r.lastBaseline - r.firstBaseline

    val t = model.text
    val cjk = isMostlyCjk(t.title)
    val title = EmojiText.annotate(t.title, dimTo = styles.ink.copy(alpha = 0.62f))
    val breaker = BalancedBreaker(measurer)
    val choice = pickTitleSize(title, { step -> styles.title(step, cjk) }, breaker, colW, { Float.MAX_VALUE }, d, queueTitleLadder)
        ?: queueTitleLadder.last().let { step ->
            val style = styles.title(step, cjk)
            TitleChoice(step, breaker.title(fitLines(breaker, title, style, colW, step.lines), style, colW, step.lines))
        }
    val titleStyle = choice.layout.layoutInput.style
    val versionStyle = styles.version(choice.step.size)
    val metaStyle = styles.faces.textType(17f, 400, styles.quiet, lineHeight = 23f, tracking = 0.005f)
        .merge(TextStyle(textDirection = TextDirection.Content, textAlign = TextAlign.Left))
    val meta = metaLine(t, model.chapter ?: t.album, model.year, styles, metaStyle, measurer, colW)

    // From the status line's baseline down, each part's cap height after the one before.
    val statusBaseline = (layout.statusTop * d).roundToInt() + (with(density) { styles.label.fontSize.toPx() } * Fonts.CAP_HEIGHT).roundToInt()
    var y = statusBaseline + LABEL_GAP * d
    var workTop: Float? = null
    val work = t.work
    if (work != null) {
        workTop = y
        y += cap(styles.work) + height(measure(AnnotatedString(work), styles.work)) + Gaps.WORK * d
    }
    val titleTop = y
    y += cap(titleStyle) + height(choice.layout)
    var versionTop: Float? = null
    if (t.version != null) {
        versionTop = y + Gaps.VERSION * d
        y = versionTop + cap(versionStyle)
    }
    var metaTop: Float? = null
    if (meta != null) {
        metaTop = y + META_GAP * d
        y = metaTop + cap(metaStyle)
    }
    // The bar (or a line in its place) under them, then "Up next", lower by the room the bar takes.
    val slotTop = y + BAR_GAP * d
    val headingTop = y + PART_GAP * d
    val rowsTop = headingTop + cap(styles.next) + LABEL_GAP * d

    // The rows, from under "Up next" as far as whole ones fit within the column without the bar.
    val foot = layout.footerBottom * d
    val coverPx = (layout.rowCover * d).roundToInt()
    val markWidth = coverPx + MARK_GAP * d
    val rowTitle = styles.faces.textType(17f, 500, styles.muted, lineHeight = 23f).merge(TextStyle(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left))
    val rowArtists = styles.label.merge(TextStyle(textDirection = TextDirection.Ltr, textAlign = TextAlign.Left))
    val titleCap = cap(rowTitle)
    val descent = with(density) { rowArtists.fontSize.toPx() } * Fonts.DESCENT
    val rows = ArrayList<PlannedRow>(queue.rows.size)
    var top = rowsTop
    for (row in queue.rows) {
        // Every row as tall as its small cover, its artists' descenders within the column.
        if (top + coverPx + descent > foot) break
        val time = row.time?.let { measure(figures.of(it), styles.time) }
        val textWidth = colW - markWidth.roundToInt()
        val titleWidth = textWidth - (time?.let { it.size.width + (TIME_GAP * d).roundToInt() } ?: 0)
        rows += PlannedRow(
            id = row.id,
            top = top,
            title = measure(isolated(fitWords(measurer, row.title, rowTitle, titleWidth)), rowTitle, width = titleWidth),
            artists = row.artists?.let { measure(isolated(fitWords(measurer, it, rowArtists, textWidth)), rowArtists, width = textWidth) },
            time = time,
            cover = row.cover?.let { ShaderBrush(ImageShader(it)) },
            bitmap = row.cover,
            coverPx = coverPx,
        )
        top += coverPx + ROW_GAP * d
    }
    return QueuePlan(
        styles = styles,
        work = t.work,
        title = choice.layout.layoutInput.text,
        titleStyle = titleStyle,
        titleLines = choice.step.lines,
        titleWidth = choice.layout.size.width,
        version = t.version,
        versionStyle = versionStyle,
        meta = meta,
        metaStyle = metaStyle,
        workTop = workTop,
        titleTop = titleTop,
        versionTop = versionTop,
        metaTop = metaTop,
        slotTop = slotTop,
        headingTop = headingTop,
        rows = rows,
        rowsTop = rowsTop,
        room = room,
        foot = foot,
        rowDescent = descent,
        appearance = model.scene.appearance,
        columnLeft = layout.columnLeft * d,
        columnWidth = colW,
        markWidth = markWidth,
        rowCap = titleCap,
    )
}

/**
 * The song's one line under its title: the artists in the artist's ink and weight, then the
 * album (or the chapter) and the year, quiet. The year leaves first, then the album, then the
 * artists take a shorter form ("and others"); the last one left is cut.
 */
private fun metaLine(t: TrackText, album: String?, year: Int?, styles: StageStyles, style: TextStyle, measurer: TextMeasurer, width: Int): AnnotatedString? {
    val artists = t.artistOptions().ifEmpty { listOf(null) }
    val forms = artists.flatMap { a -> listOf(listOfNotNull(album, year?.toString()), listOfNotNull(album), emptyList()).distinct().map { a to it } }
    fun line(artist: String?, rest: List<String>): AnnotatedString? {
        if (artist == null && rest.isEmpty()) return null
        return buildAnnotatedString {
            if (artist != null) withStyle(SpanStyle(fontWeight = FontWeight(500), color = styles.muted)) { append(if (isRightToLeft(artist)) "\u2067$artist\u2069" else artist) }
            for ((i, part) in rest.withIndex()) {
                if (artist != null || i > 0) append(TimeText.SEPARATOR)
                append(if (isRightToLeft(part)) "\u2067$part\u2069" else part)
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
private fun fitLines(breaker: BalancedBreaker, text: AnnotatedString, style: TextStyle, width: Int, lines: Int): AnnotatedString {
    fun fits(s: AnnotatedString) = breaker.lineEnds(s, style, width).size <= lines
    if (fits(text)) return text
    val plain = text.text
    var end = plain.length
    while (true) {
        end = plain.lastIndexOfAny(charArrayOf(' ', '\u00A0'), end - 1)
        if (end <= 0) return text
        var head = plain.substring(0, end)
        if (head.count { it == '(' } > head.count { it == ')' }) head = head.substring(0, head.lastIndexOf('('))
        // Never end on a dash or a separator: they are trimmed before the ellipsis is added.
        head = head.trimEnd(' ', '\u00A0', '–', '·', ',', ':', ';')
        if (head.isEmpty()) continue
        val cut = buildAnnotatedString {
            append(text.subSequence(0, head.length))
            append(" …")
        }
        if (fits(cut)) return cut
    }
}

/** A line isolated if it is written right to left, so the dot and the times keep their places. */
private fun isolated(text: String) = AnnotatedString(if (isRightToLeft(text)) "\u2067$text\u2069" else text)

/**
 * The column with the list ([TextLayer] while [StageModel.queue] is set): the song at the top
 * with its bar, and "Up next" over the rows. [rows] draws the rows too (a still); a theater
 * draws them itself ([QueueRowsLayer]), so they can move through a change. [alpha] and [rise]
 * animate a change line by line as the text block's; "Up next" goes as what comes next.
 */
@Composable
internal fun QueueText(
    model: StageModel,
    plan: QueuePlan,
    modifier: Modifier,
    alpha: (TextLine) -> Float,
    rise: (TextLine) -> Float,
    dim: () -> Float,
    shift: () -> Float,
    rows: Boolean,
    pictureDim: () -> Float,
) {
    val styles = plan.styles
    val playing = model.status == PlayerStatus.PLAYING || model.status == PlayerStatus.BUFFERING
    val bottomLine = model.line
    fun line(which: TextLine): Modifier = Modifier.lineMotion(which, alpha, rise)
    val lead = model.nextLead ?: io.github.hazymorning.klangbild.ui.texts()[Strings.stageNext]

    Box(modifier.fillMaxSize()) {
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = dim().coerceIn(0f, 1f) },
            content = {
                if (plan.work != null) BasicText(plan.work, line(TextLine.WORK), style = styles.work, maxLines = 1, overflow = TextOverflow.Ellipsis) else Box(Modifier)
                BasicText(plan.title, line(TextLine.TITLE), style = plan.titleStyle, maxLines = plan.titleLines, overflow = TextOverflow.Ellipsis)
                if (plan.version != null) BasicText(plan.version, line(TextLine.VERSION), style = plan.versionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) else Box(Modifier)
                if (plan.meta != null) BasicText(plan.meta, line(TextLine.ARTIST), style = plan.metaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) else Box(Modifier)
                BottomLine(
                    bottomOf(model), model, styles, playing,
                    line(TextLine.BOTTOM).graphicsLayer { this.alpha = plan.room.line },
                    label = "queue-bottom-line",
                    barAlpha = { plan.room.bar },
                )
                NextLine(model.next, lead, styles, line(TextLine.NEXT))
            },
        ) { measurables, constraints ->
            val colW = plan.columnWidth
            val c = Constraints(maxWidth = colW)
            val work = measurables[0].measure(c)
            // The title as wide as its balanced lines were set for.
            val title = measurables[1].measure(Constraints.fixedWidth(plan.titleWidth))
            val version = measurables[2].measure(c)
            val meta = measurables[3].measure(c)
            val bottom = measurables[4].measure(Constraints(minWidth = colW, maxWidth = colW))
            val heading = measurables[5].measure(c)
            fun Placeable.first(): Int = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: height
            fun capOf(style: TextStyle) = (style.fontSize.toPx() * Fonts.CAP_HEIGHT).roundToInt()
            layout(constraints.maxWidth, constraints.maxHeight) {
                val x = (plan.columnLeft + shift() * density).roundToInt()
                // Each line by its cap height, where the plan has it.
                fun Placeable.at(top: Float?, style: TextStyle, dx: Int = 0) {
                    if (top == null || height == 0) return
                    place(x + dx, top.roundToInt() - (first() - capOf(style)))
                }
                work.at(plan.workTop, styles.work)
                title.at(plan.titleTop, plan.titleStyle, (opticalIndent(plan.titleStyle.fontSize.value) * density).roundToInt())
                version.at(plan.versionTop, plan.versionStyle)
                meta.at(plan.metaTop, plan.metaStyle)
                // The bar stays in its place while it fades; "Up next" makes room for it as it comes.
                if (bottom.height > 0) {
                    if (bottomLine != null) bottom.at(plan.slotTop, styles.label) else bottom.place(x, plan.slotTop.roundToInt())
                }
                heading.at(plan.headingTop + plan.room.now, styles.next)
            }
        }
        if (rows) QueueRowsLayer(plan, null, RowsMotion.Still, text = dim, picture = pictureDim, shift = shift)
    }
}

/**
 * How the rows move through a change: the rows of the list before ([old], rising by [oldRise]
 * dp) and of the new one ([new], [newRise]). With [matched], a row in both glides from its old
 * place to its new one as [glide] runs from 0 to 1, and only the others fade; without, each list
 * comes or goes as a whole. The small covers of the rows that go stand at [oldCover] (on another
 * wall they stay whole under the new ones, as the cover does), and a small cover made meanwhile
 * comes in on a row that glides at [cover].
 */
internal class RowsMotion(
    val matched: Boolean,
    val glide: () -> Float,
    val old: () -> Float,
    val oldRise: () -> Float,
    val new: () -> Float,
    val newRise: () -> Float,
    val oldCover: () -> Float = old,
    val cover: () -> Float = new,
) {
    companion object {
        /** A list at rest. */
        val Still = RowsMotion(false, { 1f }, { 0f }, { 0f }, { 1f }, { 0f })
    }
}

/**
 * The rows of the list [cur], and while a change runs those of the list before ([prev]), moving as
 * [motion] says. Their words are dimmed by [text] (a pause), their small covers like the cover by
 * [picture]. Rows that keep their order glide; one whose order changed goes and comes again at
 * its new place, so no two rows ever pass through each other.
 */
@Composable
internal fun QueueRowsLayer(
    cur: QueuePlan?,
    prev: QueuePlan?,
    motion: RowsMotion,
    text: () -> Float,
    picture: () -> Float,
    shift: () -> Float = { 0f },
) {
    if (cur == null && prev == null) return
    val density = LocalDensity.current.density
    val filters = remember { HashMap<Long, ColorFilter>() }
    val gliding = remember(prev, cur, motion.matched) {
        if (!motion.matched || prev == null || cur == null) return@remember emptyMap<String, PlannedRow>()
        val glide = gliding(prev.rows.map { it.id }, cur.rows.map { it.id })
        prev.rows.filter { it.id in glide }.associateBy { it.id }
    }
    Canvas(Modifier.fillMaxSize()) {
        val textDim = text().coerceIn(0f, 1f)
        val coverDim = picture().coerceIn(0f, 1f)
        fun filterFor(appearance: Appearance): ColorFilter {
            val step = (coverDim * 50f).roundToInt()
            return filters.getOrPut(step.toLong() * 2 + appearance.ordinal) { coverFilter(step / 50f, appearance) }
        }
        translate(shift() * density, 0f) {
            val g = motion.glide().coerceIn(0f, 1f)
            if (prev != null) {
                val a = motion.old()
                val r = motion.oldRise() * density
                val y0 = prev.room.now + r
                for (row in prev.rows) {
                    if (row.id in gliding) continue
                    val shown = prev.shown(row)
                    drawRow(prev, row, row.top + y0, a * shown, textDim, filterFor(prev.appearance), coverAlpha = motion.oldCover() * shown)
                }
            }
            if (cur != null) {
                val a = motion.new()
                val r = motion.newRise() * density
                for (row in cur.rows) {
                    val before = gliding[row.id]
                    if (before != null && prev != null) {
                        // A row in both lists glides to its new place.
                        val from = before.top + prev.room.now
                        val y = from + (row.top + cur.room.now - from) * g
                        val shown = prev.shown(before) + (cur.shown(row) - prev.shown(before)) * g
                        // A small cover made meanwhile comes over the one before (or in its empty place).
                        val newCover = before.bitmap !== row.bitmap
                        if (newCover && before.cover != null) drawCover(prev, before, y, shown, filterFor(prev.appearance))
                        drawRow(cur, row, y, shown, textDim, filterFor(cur.appearance), coverAlpha = shown * if (newCover) motion.cover() else 1f)
                    } else {
                        drawRow(cur, row, row.top + cur.room.now + r, a * cur.shown(row), textDim, filterFor(cur.appearance))
                    }
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
internal fun gliding(before: List<String>, now: List<String>): Set<String> {
    val was = now.map { before.indexOf(it) }
    // The longest run of rows whose places before rise as they do now.
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
 * How much of [row] shows as the bar's room glides: whole while it fits within the column. A row
 * that no longer fits goes in the first part of the glide, before it would pass the column's foot;
 * one that comes into room comes in the last part, once its place is there.
 */
private fun QueuePlan.shown(row: PlannedRow): Float {
    fun fits(room: Float) = row.top + room + row.coverPx + rowDescent <= foot + 0.5f
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
 * One row with its cap height at [y] (px from the screen's top), as strong as [alpha]: its words
 * dimmed by [textDim], its small cover through [filter], as the cover is.
 */
private fun DrawScope.drawRow(plan: QueuePlan, row: PlannedRow, y: Float, alpha: Float, textDim: Float, filter: ColorFilter, coverAlpha: Float = alpha) {
    if (alpha <= 0.003f) return
    val words = alpha * textDim
    val x = plan.columnLeft
    val baseline = y + plan.rowCap
    drawCover(plan, row, y, coverAlpha, filter)
    drawText(row.title, topLeft = Offset(x + plan.markWidth, baseline - row.title.firstBaseline), alpha = words)
    row.artists?.let { drawText(it, topLeft = Offset(x + plan.markWidth, y + row.coverPx - it.firstBaseline), alpha = words) }
    row.time?.let { drawText(it, topLeft = Offset(x + plan.columnWidth - it.size.width, baseline - it.firstBaseline), alpha = words) }
}

/** [row]'s small cover with its top at [y], on whole pixels so the small print stays sharp. */
private fun DrawScope.drawCover(plan: QueuePlan, row: PlannedRow, y: Float, alpha: Float, filter: ColorFilter) {
    val brush = row.cover ?: return
    if (alpha <= 0.003f) return
    translate(plan.columnLeft.roundToInt().toFloat(), y.roundToInt().toFloat()) {
        val side = row.coverPx.toFloat()
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

/** From a quiet line (the status line, "Up next") to the cap height of what it leads (dp): a step more than between two rows. */
private const val LABEL_GAP = 18f

/** From the title's baseline to the cap height of the artist, album and year (dp). */
private const val META_GAP = 12f

/** From the artist, album and year to the bar (dp). */
private const val BAR_GAP = 20f

/** From the bar's times to "Up next" (dp): half again the room above the bar, so the bar belongs to the song. */
private const val PART_GAP = 30f

/** Between two rows, from one's small cover to the next one's (dp). */
private const val ROW_GAP = 16f

/** From a row's small cover to its title (dp). */
private const val MARK_GAP = 12f

/** At least this much room between a row's title and its length (dp). */
private const val TIME_GAP = 16f

/** A row's small cover has its corners rounded a little (dp). */
private const val ROW_COVER_CORNER = 2.5f
