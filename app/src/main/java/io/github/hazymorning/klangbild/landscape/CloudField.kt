package io.github.hazymorning.klangbild.landscape

import kotlin.math.ceil
import kotlin.math.floor

/**
 * A cloud in the sky right now: which design, its left end and its baseline (dp), its scale and
 * how much of it shows. [deck]: part of an overcast sky's ceiling, which hangs upside down with
 * its humps toward the ground. [stretch]: drawn this much wider (mist).
 */
class CloudPlace(val design: Int, val x: Float, val y: Float, val scale: Float, val alpha: Float, val deck: Boolean = false, val stretch: Float = 1f)

/**
 * The clouds: lanes at different heights, each an endless procession of torn tissue drifting
 * from west to east, which is right to left in a view looking south. The high lanes move faster;
 * the low ones (farther away) move slower and are smaller. A fair sky has long strips. As it
 * clouds over, heaps of tissue join them, and an overcast sky closes into a ceiling of large
 * heaps hung upside down along its top, its lower edge ragged with their humps. Every cloud's
 * design, place and the cover it needs come from its number, never from a random draw, and no
 * two lanes share a rhythm, so the sky never repeats. When the cover changes, clouds dissolve
 * or form where they are, as slowly as the cover itself changes; nothing appears from nowhere.
 * [places] takes the clouds' own clock, which runs faster in the wind ([speed]), so a gust
 * never makes them jump.
 */
object CloudField {
    private class Lane(val y: Float, val speed: Float, val spacing: Float, val scale: Float, val seed: Int, val deck: Boolean = false)

    // The lowest lane passes behind the summits, never just behind a saddle (a sliver of tissue there reads as a scratch).
    private val lanes = listOf(
        // The ceiling: large heaps hung from beyond the screen's top edge.
        Lane(y = -14f, speed = 4.3f, spacing = 240f, scale = 1.9f, seed = 5, deck = true),
        Lane(y = 6f, speed = 3.9f, spacing = 290f, scale = 1.5f, seed = 7, deck = true),
        Lane(y = 72f, speed = 4f, spacing = 430f, scale = 1f, seed = 11),
        Lane(y = 112f, speed = 3.05f, spacing = 380f, scale = 0.86f, seed = 23),
        Lane(y = 150f, speed = 2.3f, spacing = 340f, scale = 0.74f, seed = 37),
        Lane(y = 184f, speed = 1.66f, spacing = 300f, scale = 0.62f, seed = 51),
    )

    /** How full the sky is for a [cover] (see [Weather]): a clear day keeps a strip now and then, an overcast one is full. */
    fun density(cover: Float): Float = cover.coerceIn(0f, 1f).let { 0.07f + 0.86f * it * it }

    /** How fast the clouds' clock runs in [wind] (0..1): about the lanes' own speed in a breeze, three times that in a gale. */
    fun speed(wind: Float): Float = 0.7f + 2.5f * wind.coerceIn(0f, 1f)

    /** The clouds in the picture at the clouds' own time [seconds] for a sky [density] 0..1. */
    fun places(seconds: Double, density: Float, out: MutableList<CloudPlace> = ArrayList()): List<CloudPlace> {
        out.clear()
        val left = -PaperMotif.OVERSCAN - 900f
        val right = PaperMotif.WIDTH + PaperMotif.OVERSCAN + 20f
        // The deck closes only over an overcast sky.
        val deck = Shapes.smoothstep(0.55f, 0.92f, density)
        for (lane in lanes) {
            if (lane.deck && deck <= 0.003f) continue
            // The lane's procession has moved this far to the left.
            val shift = seconds * lane.speed
            val first = floor((left + shift) / lane.spacing).toLong() - 1
            val last = ceil((right + shift) / lane.spacing).toLong()
            for (k in first..last) {
                val h = hash(k, lane.seed)
                val need = (h and 0xFFFF) / 65535f
                // One cloud in four is a heap; heaps only come as the sky fills.
                val heap = lane.deck || ((h ushr 30) and 3) == 0
                val wanted = when {
                    lane.deck -> deck
                    heap -> Shapes.smoothstep(0.25f, 0.75f, density) * density
                    else -> density
                }
                val alpha = Shapes.smoothstep(need - 0.06f, need + 0.06f, wanted)
                if (alpha <= 0.003f) continue
                val pick = (h ushr 16) and 0xFF
                val design = if (heap) PaperBake.CLOUD_STRIPS + pick % PaperBake.CLOUD_HEAPS else pick % PaperBake.CLOUD_STRIPS
                val jitterX = (((h ushr 24) and 0x3F) / 63f - 0.5f) * lane.spacing * 0.5f
                val hy = hash(k, lane.seed + 7)
                val jitterY = ((hy and 0xFF) / 255f - 0.5f) * (if (lane.deck) 26f else 18f)
                // The ceiling's heaps differ in size, so its edge is never a straight line.
                val size = if (lane.deck) 0.82f + 0.36f * (((hy ushr 8) and 0xFF) / 255f) else if (heap) 0.9f else 1f
                val x = (k * lane.spacing + jitterX - shift).toFloat()
                if (x > right || x < left) continue
                out += CloudPlace(design, x, lane.y + jitterY, lane.scale * size, alpha, lane.deck)
            }
        }
        return out
    }

    /** A well-mixed hash of a cloud's number and its lane. */
    private fun hash(k: Long, seed: Int): Int {
        var x = k * -0x61c8864680b583ebL + seed * 0x9E3779B97F4A7C15uL.toLong()
        x = (x xor (x ushr 33)) * -0xae502812aa7333L
        x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
        return (x xor (x ushr 33)).toInt()
    }
}
