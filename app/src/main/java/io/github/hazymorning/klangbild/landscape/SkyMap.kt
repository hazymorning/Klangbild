package io.github.hazymorning.klangbild.landscape

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A star where the picture shows it (dp), its dot's radius (dp) and how bright it is (0..1). */
class StarPlace(val x: Float, val y: Float, val size: Float, val brightness: Float)

/** Where the sun, the moon and the stars stand in the picture (dp), and the moon's phase. */
class SkyPlaces(
    val sunX: Float,
    val sunY: Float,
    val moonX: Float,
    val moonY: Float,
    val moonFraction: Float,
    val stars: List<StarPlace>,
    /** Whether the moon stands above the horizon. */
    val moonUp: Boolean = false,
)

/**
 * The sky as the picture shows it: looking south, east on the left and west on the right,
 * [DP_PER_DEGREE_X] dp for every degree of azimuth. That is a very wide view, so the sun's whole
 * day crosses the picture and even a summer evening's sun sets inside it.
 *
 * Elevation is measured from the mountains' skyline, not from the valley floor: the sun rises
 * over the ridge the moment the light turns to morning and sets behind it as the evening colors
 * come, and even the low noon sun of midwinter clears the summits. Higher up the sky is drawn a
 * little tighter, so the whole dome fits above them.
 */
object SkyMap {
    const val DP_PER_DEGREE_X = 3.0f

    /** Below the skyline, the dp per degree of elevation (only seen where the land is low). */
    private const val DP_PER_DEGREE_BELOW = 5.0f

    /** The zenith's place (dp from the top) and how the dome is drawn tighter toward it. */
    private const val ZENITH_Y = 24f
    private const val DOME = 0.8

    fun x(azimuth: Float): Float = PaperMotif.WIDTH / 2f + (azimuth - 180f) * DP_PER_DEGREE_X

    /** The height in the picture (dp) of a body at [elevation] above the horizon, at [x] (dp). */
    fun y(elevation: Float, x: Float): Float {
        val ground = skyline(x)
        if (elevation <= 0f) return ground - elevation * DP_PER_DEGREE_BELOW
        val up = Math.pow((elevation / 90.0).coerceAtMost(1.0), DOME).toFloat()
        return ground - (ground - ZENITH_Y) * up
    }

    /** The places for [s], the stars turned for local sidereal time [lstDeg] at latitude [latDeg]. */
    fun places(s: SkyState, lstDeg: Double = 0.0, latDeg: Double = 51.0): SkyPlaces {
        val sunX = x(s.sunAzimuth)
        val moonX = x(s.moonAzimuth)
        return SkyPlaces(
            sunX, y(s.sunElevation, sunX), moonX, y(s.moonElevation, moonX), s.moonFraction, stars(lstDeg, latDeg),
            moonUp = s.moonElevation > 0f,
        )
    }

    /**
     * The skyline the sky's elevations start from: the far range's summits and saddles, smoothed
     * over [SKYLINE_SPAN] dp so a body's path never kinks over a summit, sampled every 4 dp.
     */
    fun skyline(x: Float): Float {
        val t = ((x - SKYLINE_FROM) / SKYLINE_STEP).coerceIn(0f, (skylineSamples.size - 1).toFloat())
        val i = t.toInt().coerceAtMost(skylineSamples.size - 2)
        val f = t - i
        return skylineSamples[i] + (skylineSamples[i + 1] - skylineSamples[i]) * f
    }

    private const val SKYLINE_FROM = -200f
    private const val SKYLINE_STEP = 4f
    private const val SKYLINE_SPAN = 140f

    private val skylineSamples: FloatArray = run {
        val n = ((PaperMotif.WIDTH + 400f) / SKYLINE_STEP).toInt() + 1
        FloatArray(n) { i ->
            val x = SKYLINE_FROM + i * SKYLINE_STEP
            // A smooth average (a cosine window): the summits' corners are gone, their height is kept roughly.
            var sum = 0f
            var weight = 0f
            var dx = -SKYLINE_SPAN / 2f
            while (dx <= SKYLINE_SPAN / 2f) {
                val w = 0.5f + 0.5f * kotlin.math.cos(Math.PI.toFloat() * dx / (SKYLINE_SPAN / 2f))
                sum += PaperMotif.skyline(x + dx) * w
                weight += w
                dx += 2f
            }
            sum / weight
        }
    }

    /**
     * A fixed field of stars on the celestial sphere (the same every night), turned to the
     * hour: they rise on the left and set on the right, as the real ones do.
     */
    fun stars(lstDeg: Double, latDeg: Double): List<StarPlace> {
        val out = ArrayList<StarPlace>(field.size)
        val lat = Math.toRadians(latDeg)
        for (st in field) {
            val ha = Math.toRadians(lstDeg - st.ra)
            val dec = Math.toRadians(st.dec)
            val sinAlt = sin(dec) * sin(lat) + cos(dec) * cos(lat) * cos(ha)
            val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
            if (alt < Math.toRadians(1.0)) continue
            val az = atan2(-cos(dec) * sin(ha), sin(dec) * cos(lat) - cos(dec) * sin(lat) * cos(ha))
            val azDeg = (Math.toDegrees(az) + 360.0) % 360.0
            val px = x(azDeg.toFloat())
            val py = y(Math.toDegrees(alt).toFloat(), px)
            if (px < -PaperMotif.OVERSCAN || px > PaperMotif.WIDTH + PaperMotif.OVERSCAN || py < -PaperMotif.OVERSCAN) continue
            out += StarPlace(px, py, st.size, st.brightness)
        }
        return out
    }

    private class Star(val ra: Double, val dec: Double, val size: Float, val brightness: Float)

    /**
     * Stars spread evenly over the sphere (a golden-angle spiral, so no clumps and no gaps),
     * mostly faint and a few bright, like the real distribution but without its constellations.
     */
    private val field: List<Star> = run {
        val n = 420
        val golden = PI * (3.0 - sqrt(5.0))
        List(n) { i ->
            val z = 1.0 - 2.0 * (i + 0.5) / n
            val theta = golden * i
            val dec = Math.toDegrees(asin(z))
            val ra = Math.toDegrees(theta) % 360.0
            // Magnitude classes by a fixed scramble of the index: 1 in 14 bright, 1 in 4 medium.
            val h = (i * 2654435761L ushr 7) % 56
            val (size, bright) = when {
                h < 4 -> 1.55f to 1f
                h < 18 -> 1.15f to 0.78f
                else -> 0.85f to 0.52f
            }
            Star(ra, dec, size, bright)
        }
    }
}
