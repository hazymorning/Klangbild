package io.github.hazymorning.klangbild.landscape

import io.github.hazymorning.klangbild.math.smoothstep
import kotlin.math.ceil

/**
 * How far a bake has come ([done], 0 to 1) and where the part under way ends ([upTo]): a bake
 * tells its progress only between its parts, and some parts take far longer than others.
 */
data class BakeProgress(val done: Float, val upTo: Float) {
    /** The same progress as the first [share] of a larger whole. */
    fun scaled(share: Float) = BakeProgress(done * share, upTo * share)

    companion object {
        val NONE = BakeProgress(0f, 0f)
        val DONE = BakeProgress(1f, 1f)
    }
}

/**
 * Bakes the diorama for a screen. Every piece of paper is cut once, with exact anti-aliasing and
 * the slight unevenness of a hand with scissors, and laid on its sheet. Each sheet gets its
 * paper's grain, is darkened where nearer sheets stand close in front of it, and is softened
 * where it lies far from the clearing. Colors are not baked: the light paints them every frame.
 * [Baker] does the cutting; each kind of paper is cut in a file of its own (BakeSky.kt,
 * BakeMountains.kt, BakeTrees.kt, BakeMeadow.kt, BakeCamp.kt).
 */
object PaperBake {
    /**
     * Bakes the diorama; [progress] is told how far it has come as each part starts, by the time
     * each part takes ([COST]), and where the part then starting ends.
     */
    fun bake(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        shapes: SeasonShapes = SeasonShapes(),
        label: LabelSide = LabelSide.LEFT,
        progress: (BakeProgress) -> Unit = {},
    ): PaperBaked {
        var done = 0f
        var started = 0f
        fun starting(cost: Float) {
            done += started
            started = cost
            progress(BakeProgress(done / TOTAL_COST, ((done + cost) / TOTAL_COST).coerceAtMost(1f)))
        }
        val m = PaperMotif
        val cw = ceil((m.WIDTH + 2 * m.OVERSCAN) * density).toInt().coerceAtLeast(widthPx)
        val ch = ceil((m.HEIGHT + 2 * m.OVERSCAN) * density).toInt().coerceAtLeast(heightPx)
        val b = Baker(cw, ch, density, shapes, m.foreground(label))
        val sheets = LinkedHashMap<SheetId, Sheet>()
        var standees: List<Standee> = emptyList()
        val frontStandees = ArrayList<Standee>()
        // Front to back, so that each sheet knows what stands in front of it.
        for (id in SheetId.entries.reversed()) {
            if (id == SheetId.CROWNS) continue
            starting(COST[id] ?: 0f)
            when (id) {
                SheetId.STANDEES -> standees = b.standees()
                SheetId.FRONT_LEFT, SheetId.FRONT_RIGHT -> {
                    frontStandees += b.frontFirs(left = id == SheetId.FRONT_LEFT)
                    b.cutSheet(id)?.let { sheets[id] = b.finish(it) }
                }
                else -> b.cutSheet(id)?.let { sheets[id] = b.finish(it) }
            }
        }
        val ordered = LinkedHashMap<SheetId, Sheet>()
        for (id in SheetId.entries) sheets[id]?.let { ordered[id] = it }
        starting(SHADOWS_COST)
        val shadows = ordered.filterKeys { it.depth > 0f }.mapValues { (id, s) -> b.shadow(s, shadowBlurDp(id.depth)) }
        starting(CLOUDS_COST)
        val clouds = (0 until CLOUD_DESIGNS).map { b.cloud(it) }
        starting(REST_COST)
        val baked = PaperBaked(
            cw, ch, density, ordered, shadows, clouds, clouds.map { b.shadow(it, shadowBlurDp(CLOUD_DEPTH)) },
            b.flames(), b.px(b.fg.fireX), b.px(b.fg.fireY), standees, frontStandees, b.px(b.fg.tentX), b.px(b.tentFoot - TENT_HEIGHT * 0.24f),
            b.disc(SUN_RADIUS, 77), b.disc(MOON_RADIUS, 78),
            b.shadow(b.disc(SUN_RADIUS, 79), DISC_SHADOW_DP), b.glow(), leafAlpha(shapes.leaves),
        )
        progress(BakeProgress.DONE)
        return baked
    }

    /**
     * How long each part of the bake takes, as measured at 4K (ms on a desktop's JVM; only their
     * proportions count): the sheets, then the shadows, the clouds, and the flames, discs and glow.
     */
    private val COST: Map<SheetId, Float> = mapOf(
        SheetId.FRONT_RIGHT to 504f, SheetId.FRONT_LEFT to 265f, SheetId.BLOOMS_NEAR to 24f, SheetId.MEADOW_NEAR to 128f,
        SheetId.FIREWOOD to 8f, SheetId.PROPS to 6f, SheetId.TENT to 8f, SheetId.STANDEES to 72f, SheetId.BLOOMS to 15f,
        SheetId.MEADOW to 136f, SheetId.LEAVES to 57f, SheetId.WOOD_NEAR to 439f, SheetId.WOOD_FAR to 343f,
        SheetId.RANGE_NEAR to 373f, SheetId.SNOW to 315f, SheetId.RANGE_FAR to 709f, SheetId.SKY to 478f,
    )
    private const val SHADOWS_COST = 133f
    private const val CLOUDS_COST = 728f
    private const val REST_COST = 375f
    private val TOTAL_COST = COST.values.sum() + SHADOWS_COST + CLOUDS_COST + REST_COST

    /** Cuts the sheet [id] (null where the season leaves it empty, or where its pieces are sprites). */
    private fun Baker.cutSheet(id: SheetId): Build? = when (id) {
        SheetId.SKY -> sky()
        SheetId.RANGE_FAR -> range(id, PaperMotif.farRange, PaperMotif.FAR_FOOT, seed = 2)
        SheetId.SNOW -> snow()
        SheetId.RANGE_NEAR -> range(id, PaperMotif.nearRange, PaperMotif.NEAR_FOOT, seed = 3)
        SheetId.WOOD_FAR -> woodFar()
        SheetId.WOOD_NEAR -> woodNear(leaves = false)
        SheetId.LEAVES -> if (shapes.leaves > 0.05f) woodNear(leaves = true) else null
        SheetId.MEADOW -> meadow()
        SheetId.BLOOMS -> blooms(near = false)
        SheetId.STANDEES, SheetId.CROWNS -> null
        SheetId.TENT -> tent()
        SheetId.PROPS -> props()
        SheetId.FIREWOOD -> firewood()
        SheetId.MEADOW_NEAR -> meadowNear()
        SheetId.BLOOMS_NEAR -> blooms(near = true)
        SheetId.FRONT_LEFT -> front(left = true)
        SheetId.FRONT_RIGHT -> front(left = false)
    }

    /** The leaves' strength for [leaves] (0 bare to 1 full): gone before the last ones fall, back with the first green. */
    fun leafAlpha(leaves: Float): Float = smoothstep(0.08f, 0.6f, leaves)

    /** How soft a sheet's shadow is (dp) for its [depth]. */
    fun shadowBlurDp(depth: Float): Float = 1.4f + 2.8f * depth

    /** How many cloud designs there are: long strips first, then heaps. The sky draws them in an order that never repeats. */
    const val CLOUD_STRIPS = 5
    const val CLOUD_HEAPS = 3
    const val CLOUD_DESIGNS = CLOUD_STRIPS + CLOUD_HEAPS

    /** The clouds hang this far (dp) in front of the sky. */
    const val CLOUD_DEPTH = 0.8f

    /** The radius of the sun's and the moon's discs (dp), and how soft their shadow is. */
    const val SUN_RADIUS = 15f
    const val MOON_RADIUS = 14f
    private const val DISC_SHADOW_DP = 1.2f

    /**
     * How far down (dp) each sheet is baked. Below that line, nearer sheets hide it everywhere,
     * even when the camera's layers have drifted apart by a few dp.
     */
    internal const val SKY_BOTTOM = 318f
    internal const val RANGE_BOTTOM = 350f
    internal const val WOOD_FAR_BOTTOM = 402f
    internal const val WOOD_NEAR_BOTTOM = 458f
    internal const val MEADOW_BOTTOM = 522f

    /** The tent: half its width and its height (dp). */
    const val TENT_HALF = 30f
    const val TENT_HEIGHT = 41f
}
