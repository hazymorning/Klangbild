package io.github.hazymorning.klangbild.landscape

import io.github.hazymorning.klangbild.math.degrees
import io.github.hazymorning.klangbild.math.radians
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the sun and the moon stand and how much of the moon is lit, ported from commons-suncalc
 * 3.11 (Copyright Richard "Shred" Körber, Apache License 2.0), which follows Montenbruck and
 * Pfleger's "Astronomy on the Personal Computer". Only what the landscape uses is here, with the
 * library's operations in the library's order, so on the JVM every result is the library's to the
 * last bit.
 */
internal object Sky {

    /**
     * Where a body stands (degrees): [altitude] above the horizon, raised by the air's refraction,
     * and [azimuth] from north through east.
     */
    class Position(val altitude: Double, val azimuth: Double)

    /**
     * The sun at [epochMs] over [latitude] and [longitude] (degrees, north and east positive).
     * [dayOfYear] is the day of the year at [epochMs] in the system's time zone (1 on January 1),
     * which the library takes the sun's distance from.
     */
    fun sun(epochMs: Long, dayOfYear: Int, latitude: Double, longitude: Double): Position {
        val mjd = modifiedJulianDate(epochMs)
        return horizontal(sunVector(mjd, dayOfYear), mjd, latitude, longitude)
    }

    /**
     * The moon at [epochMs] over [latitude] and [longitude] (degrees). Like the library, it leaves
     * out the parallax, so this is the moon as seen from the earth's center.
     */
    fun moon(epochMs: Long, latitude: Double, longitude: Double): Position {
        val mjd = modifiedJulianDate(epochMs)
        return horizontal(moonVector(mjd), mjd, latitude, longitude)
    }

    /** How much of the moon is lit at [epochMs] (0 new, 1 full), the same from everywhere. [dayOfYear] as for [sun]. */
    fun moonFraction(epochMs: Long, dayOfYear: Int): Double {
        val mjd = modifiedJulianDate(epochMs)
        val s = sunVector(mjd, dayOfYear)
        val m = moonVector(mjd)
        return (1.0 + cos(PI - acos(m.dot(s) / (m.r * s.r)))) / 2.0
    }

    private const val PI2 = PI * 2.0

    /** Arc seconds in a radian. */
    private val ARCS = 3600.0 * degrees(1.0)

    /** The sun's mean distance (km). */
    private const val SUN_DISTANCE = 149598000.0

    private fun modifiedJulianDate(epochMs: Long) = epochMs / 86_400_000.0 + 40587.0

    /** Julian centuries since noon on January 1, 2000. */
    private fun centuries(mjd: Double) = (mjd - 51544.5) / 36525.0

    /** The fractional part of [a], with the sign of [a]. */
    private fun frac(a: Double) = a % 1.0

    /** Greenwich mean sidereal time (radians). */
    private fun siderealTime(mjd: Double): Double {
        val mjd0 = floor(mjd)
        val ut = (mjd - mjd0) * 86400.0
        val t0 = centuries(mjd0)
        val t = centuries(mjd)
        val gmst = 24110.54841 + 8640184.812866 * t0 + 1.0027379093 * ut + (0.093104 - 6.2e-6 * t) * t * t
        return PI2 / 86400.0 * (gmst % 86400.0)
    }

    /** The sun from the earth's center (equatorial, km). */
    private fun sunVector(mjd: Double, dayOfYear: Int): Vector {
        val t = centuries(mjd)
        // The mean anomaly and the ecliptic longitude (radians).
        val m = PI2 * frac(0.993133 + 99.997361 * t)
        val l = PI2 * frac(0.7859453 + m / PI2 + (6893.0 * sin(m) + 72.0 * sin(2.0 * m) + 6191.2 * t) / 1296.0e3)
        // The distance follows a rough true anomaly, taken from the day of the year.
        val anomaly = PI2 * frac((dayOfYear - 5.0) / 365.256363)
        return equatorial(polar(l, 0.0, SUN_DISTANCE * (1.0 - 0.016718 * cos(anomaly))), t)
    }

    /** The moon from the earth's center (equatorial, km). */
    private fun moonVector(mjd: Double): Vector {
        val t = centuries(mjd)
        // The moon's mean longitude (turns), the moon's and the sun's mean anomalies, the moon's
        // mean elongation and its mean distance from the ascending node (radians).
        val l0 = frac(0.606433 + 1336.855225 * t)
        val l = PI2 * frac(0.374897 + 1325.552410 * t)
        val ls = PI2 * frac(0.993133 + 99.997361 * t)
        val d = PI2 * frac(0.827361 + 1236.853086 * t)
        val f = PI2 * frac(0.259086 + 1342.227825 * t)
        val d2 = 2.0 * d
        val l2 = 2.0 * l
        val f2 = 2.0 * f
        // The perturbations of the longitude and the latitude (arc seconds).
        val dL = 22640.0 * sin(l) - 4586.0 * sin(l - d2) + 2370.0 * sin(d2) + 769.0 * sin(l2) - 668.0 * sin(ls) -
            412.0 * sin(f2) - 212.0 * sin(l2 - d2) - 206.0 * sin(l + ls - d2) + 192.0 * sin(l + d2) -
            165.0 * sin(ls - d2) - 125.0 * sin(d) - 110.0 * sin(l + ls) + 148.0 * sin(l - ls) - 55.0 * sin(f2 - d2)
        val s = f + (dL + 412.0 * sin(f2) + 541.0 * sin(ls)) / ARCS
        val h = f - d2
        val n = -526.0 * sin(h) + 44.0 * sin(l + h) - 31.0 * sin(-l + h) - 23.0 * sin(ls + h) +
            11.0 * sin(-ls + h) - 25.0 * sin(-l2 + f) + 21.0 * sin(-l + f)
        val longitude = PI2 * frac(l0 + dL / 1296.0e3)
        val latitude = (18520.0 * sin(s) + n) / ARCS
        val distance = 385000.5584 - 20905.3550 * cos(l) - 3699.1109 * cos(d2 - l) - 2955.9676 * cos(d2) -
            569.9251 * cos(l2)
        return equatorial(polar(longitude, latitude, distance), t)
    }

    /**
     * [v] turned from ecliptic to equatorial coordinates by the tilt of the earth's axis at [t]
     * (Julian centuries). Each sum starts from 0.0 as in the library's matrix product, which only
     * matters for the sign of a zero. The product's terms with a zero factor add nothing, so they
     * are left out.
     */
    private fun equatorial(v: Vector, t: Double): Vector {
        val eps = radians(23.43929111 - (46.8150 + (0.00059 - 0.001813 * t) * t) * t / 3600.0)
        val s = sin(eps)
        val c = cos(eps)
        return Vector(0.0 + v.x, 0.0 + c * v.y - s * v.z, 0.0 + s * v.y + c * v.z)
    }

    /** Where the body at [v] (equatorial) stands at [mjd] over [latitude] and [longitude] (degrees). */
    private fun horizontal(v: Vector, mjd: Double, latitude: Double, longitude: Double): Position {
        val hourAngle = siderealTime(mjd) + radians(longitude) - v.phi
        val p = polar(hourAngle, v.theta, v.r)
        // Turned about the y axis so the zenith is up, summed as in equatorial.
        val turn = PI / 2.0 - radians(latitude)
        val s = sin(turn)
        val c = cos(turn)
        val h = Vector(0.0 + c * p.x - s * p.z, 0.0 + p.y, 0.0 + s * p.x + c * p.z)
        val altitude = h.theta
        return Position(
            altitude = degrees(altitude + refraction(altitude)),
            azimuth = (degrees(h.phi) + 180.0) % 360.0,
        )
    }

    /** How much the air raises a body at true altitude [h] (radians), nothing below the horizon. */
    private fun refraction(h: Double) = if (h < 0.0) 0.0 else 0.000296706 / tan(h + 0.00312537 / (h + 0.0890118))

    /** The vector [r] long at angle [phi] around the z axis and [theta] above the x-y plane (radians). */
    private fun polar(phi: Double, theta: Double, r: Double): Vector {
        val cosTheta = cos(theta)
        return Vector(r * cos(phi) * cosTheta, r * sin(phi) * cosTheta, r * sin(theta))
    }

    /**
     * A vector. Its angles leave out the library's special cases for zero components, which give
     * the same results here.
     */
    private class Vector(val x: Double, val y: Double, val z: Double) {
        /** The angle around the z axis from the x axis (radians, 0 to 2π). */
        val phi: Double
            get() = atan2(y, x).let { if (it < 0.0) it + PI2 else it }

        /** The angle above the x-y plane (radians). */
        val theta: Double
            get() = atan2(z, sqrt(x * x + y * y))

        val r: Double
            get() = sqrt(x * x + y * y + z * z)

        fun dot(o: Vector) = x * o.x + y * o.y + z * o.z
    }
}
