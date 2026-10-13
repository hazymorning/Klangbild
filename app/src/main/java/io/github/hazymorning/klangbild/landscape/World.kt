package io.github.hazymorning.klangbild.landscape

import io.github.hazymorning.klangbild.math.smoothstep
import org.shredzone.commons.suncalc.MoonIllumination
import org.shredzone.commons.suncalc.MoonPosition
import org.shredzone.commons.suncalc.SunPosition
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Where on Earth the window looks out (degrees, north and east positive). */
data class Place(val latitude: Double, val longitude: Double)

/** The sky over a place at one moment: sun and moon (degrees, azimuth from north through east) and the stars' turn. */
data class Heavens(
    val sunElevation: Float,
    val sunAzimuth: Float,
    val moonElevation: Float,
    val moonAzimuth: Float,
    val moonFraction: Float,
    /** Local sidereal time (degrees): how far the stars have turned. */
    val siderealDeg: Double,
    val latitude: Double,
)

/**
 * The real world behind the picture, kept to what it shows in every moment: the sun's and the
 * moon's places and the moon's phase (computed with commons-suncalc, Apache 2.0, exact to
 * about a minute, far more than a picture needs), the stars' turn, and the season from the
 * calendar. Everything is a smooth function of time: nothing in the picture ever jumps.
 */
object World {

    fun heavens(epochMs: Long, place: Place): Heavens {
        val instant = Instant.ofEpochMilli(epochMs)
        val sun = SunPosition.compute().on(instant).at(place.latitude, place.longitude).execute()
        val moon = MoonPosition.compute().on(instant).at(place.latitude, place.longitude).execute()
        val lit = MoonIllumination.compute().on(instant).at(place.latitude, place.longitude).execute()
        return Heavens(
            sunElevation = sun.altitude.toFloat(),
            sunAzimuth = sun.azimuth.toFloat(),
            moonElevation = moon.altitude.toFloat(),
            moonAzimuth = moon.azimuth.toFloat(),
            moonFraction = lit.fraction.toFloat(),
            siderealDeg = siderealDegrees(epochMs, place.longitude),
            latitude = place.latitude,
        )
    }

    /**
     * What the sky does at [epochMs] over [place] in [weather] (everything the picture's light
     * depends on), and where the heavens stand.
     */
    fun sky(epochMs: Long, place: Place, zone: ZoneId, weather: Weather): Pair<SkyState, Heavens> {
        val h = heavens(epochMs, place)
        val (season, shapes) = season(epochMs, zone, place.latitude)
        return SkyState(
            h.sunElevation, h.sunAzimuth, h.moonElevation, h.moonAzimuth, h.moonFraction,
            cover = weather.cover, rain = weather.rain, snow = weather.snow, fog = weather.fog, storm = weather.storm,
            season = season, blossom = shapes.blossom,
        ) to h
    }

    /** Local mean sidereal time in degrees (the standard formula, good to a fraction of a degree). */
    fun siderealDegrees(epochMs: Long, longitude: Double): Double {
        val jd = epochMs / 86_400_000.0 + 2440587.5
        val gmst = 280.46061837 + 360.98564736629 * (jd - 2451545.0)
        return ((gmst + longitude) % 360.0 + 360.0) % 360.0
    }

    /**
     * Without Home Assistant's location, a place guessed from the time zone: the zone's big
     * city where it is known, else a latitude of 50° and the longitude its offset implies.
     * Good enough for the sun's colors; the stars and the moon only shift a little.
     */
    fun guessPlace(zone: ZoneId): Place {
        KNOWN[zone.id]?.let { return it }
        val offsetHours = zone.rules.getStandardOffset(Instant.now()).totalSeconds / 3600.0
        return Place(50.0, offsetHours * 15.0)
    }

    private val KNOWN = mapOf(
        "Europe/Berlin" to Place(52.52, 13.40), "Europe/Vienna" to Place(48.21, 16.37), "Europe/Zurich" to Place(47.37, 8.54),
        "Europe/Amsterdam" to Place(52.37, 4.90), "Europe/Brussels" to Place(50.85, 4.35), "Europe/Luxembourg" to Place(49.61, 6.13),
        "Europe/Paris" to Place(48.86, 2.35), "Europe/London" to Place(51.51, -0.13), "Europe/Copenhagen" to Place(55.68, 12.57),
        "Europe/Stockholm" to Place(59.33, 18.07), "Europe/Oslo" to Place(59.91, 10.75), "Europe/Prague" to Place(50.08, 14.44),
        "Europe/Warsaw" to Place(52.23, 21.01), "Europe/Rome" to Place(41.90, 12.50), "Europe/Madrid" to Place(40.42, -3.70),
        "Europe/Busingen" to Place(47.70, 8.69), "Europe/Vaduz" to Place(47.14, 9.52),
    )

    /**
     * The season at [epochMs] for a place at [latitude] (the south has its seasons half a year
     * apart): how much spring, summer, autumn and winter color the land, and what has changed
     * shape (snow, leaves, blossom). Every value passes into the next over weeks.
     */
    fun season(epochMs: Long, zone: ZoneId, latitude: Double): Pair<SeasonMix, SeasonShapes> {
        val date = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone)
        var day = date.dayOfYear - 1 + (date.hour * 60 + date.minute) / 1440.0
        if (latitude < 0) day = (day + 182.6) % 365.25
        return seasonOnDay(day.toFloat())
    }

    /** The season on day [d] of a northern year (0 is January 1). */
    fun seasonOnDay(d: Float): Pair<SeasonMix, SeasonShapes> {
        // Each season's weight: a plateau with long ramps at both ends (day numbers, not dates).
        val spring = window(d, 68f, 100f, 130f, 161f)
        val summer = window(d, 130f, 161f, 236f, 272f)
        val autumn = window(d, 252f, 278f, 314f, 340f)
        val winter = max(window(d, 323f, 349f, 400f, 420f), window(d + 365f, 323f, 349f, 416f, 449f))
        val sum = (spring + summer + autumn + winter).coerceAtLeast(1e-4f)
        val mix = SeasonMix(spring / sum, summer / sum, autumn / sum, winter / sum)
        val shapes = SeasonShapes(
            snow = max(window(d, 334f, 353f, 410f, 434f), window(d + 365f, 334f, 353f, 410f, 434f)),
            peaks = 1f - window(d, 92f, 170f, 258f, 324f),
            leaves = window(d, 98f, 130f, 288f, 320f),
            blossom = window(d, 94f, 108f, 114f, 130f),
            flowers = window(d, 96f, 120f, 186f, 222f),
            fallen = window(d, 268f, 292f, 318f, 340f),
        )
        return mix to shapes
    }

    /** Smoothly 0 before [a], rising to 1 at [b], 1 until [c], falling to 0 at [e]. */
    private fun window(x: Float, a: Float, b: Float, c: Float, e: Float): Float =
        smoothstep(a, b, x) * (1f - smoothstep(c, e, x))

    private fun max(a: Float, b: Float) = if (a > b) a else b
}

/**
 * The weather as the picture shows it: how cloudy the sky is ([cover], 0 clear to 1 overcast),
 * what falls ([rain], [snow], 0 none to 1 as hard as it gets), fog in the valley ([fog]), a
 * storm's dark sky ([storm]) and the wind ([wind], 0 still to 1 a gale). The clouds go faster
 * with the wind, and the trees, the flames and what falls lean with it.
 */
data class Weather(
    val cover: Float,
    val rain: Float = 0f,
    val snow: Float = 0f,
    val fog: Float = 0f,
    val storm: Float = 0f,
    val wind: Float = BREEZE,
) {
    /** On the way from this weather to [to], [t] (0..1) of it. */
    fun towards(to: Weather, t: Float): Weather {
        if (t <= 0f) return this
        if (t >= 1f) return to
        fun m(a: Float, b: Float) = a + (b - a) * t
        return Weather(m(cover, to.cover), m(rain, to.rain), m(snow, to.snow), m(fog, to.fog), m(storm, to.storm), m(wind, to.wind))
    }

    /** The largest step from this weather to [o] in what the light depends on (not the wind). */
    fun distance(o: Weather): Float = maxOf(
        kotlin.math.abs(cover - o.cover), kotlin.math.abs(rain - o.rain), kotlin.math.abs(snow - o.snow),
        maxOf(kotlin.math.abs(fog - o.fog), kotlin.math.abs(storm - o.storm)),
    )

    companion object {
        /** A breeze: what the trees and the clouds do on a calm day. */
        const val BREEZE = 0.2f

        /** Without a weather entity: a fair sky with a few clouds and a breeze. */
        val FAIR = Weather(cover = 0.25f)
    }
}

/**
 * Home Assistant's weather entity as [Weather]: its state (`sunny`, `rainy`, `snowy` and so
 * on), its `cloud_coverage` (%) and its `wind_speed` (in its `wind_speed_unit`). No lightning
 * flashes: a storm only darkens the sky and drives the rain.
 */
object WeatherMood {
    /** The weather for an entity's [state], cloud cover (%) and wind (km/h), or null when nothing is known. */
    fun of(state: String?, cloudCoverage: Double?, windKmh: Double? = null): Weather? {
        val byState = when (state) {
            "sunny", "clear-night" -> Weather(cover = 0.05f)
            "partlycloudy" -> Weather(cover = 0.45f)
            "cloudy" -> Weather(cover = 0.88f)
            "fog" -> Weather(cover = 0.75f, fog = 1f, wind = 0.05f)
            "rainy" -> Weather(cover = 1f, rain = 0.55f, wind = 0.3f)
            "pouring" -> Weather(cover = 1f, rain = 1f, wind = 0.45f)
            "snowy" -> Weather(cover = 0.95f, snow = 0.7f, wind = 0.2f)
            "snowy-rainy" -> Weather(cover = 1f, rain = 0.35f, snow = 0.45f, wind = 0.3f)
            "hail" -> Weather(cover = 1f, rain = 0.75f, storm = 0.4f, wind = 0.5f)
            "lightning" -> Weather(cover = 1f, storm = 1f, wind = 0.55f)
            "lightning-rainy" -> Weather(cover = 1f, rain = 0.85f, storm = 1f, wind = 0.6f)
            "windy" -> Weather(cover = 0.25f, wind = 0.85f)
            "windy-variant" -> Weather(cover = 0.6f, wind = 0.85f)
            "exceptional" -> Weather(cover = 0.6f)
            else -> null
        }
        val byCoverage = cloudCoverage?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }
        val w = byState ?: byCoverage?.let { Weather(cover = it) } ?: return null
        val cover = if (byState != null && byCoverage != null) (byState.cover + byCoverage) / 2f else w.cover
        // The measured wind counts, but a state that says "windy" is never calmer than 0.6.
        val wind = windKmh?.let { windOf(it) }?.let { if (state == "windy" || state == "windy-variant") maxOf(it, 0.6f) else it } ?: w.wind
        return w.copy(cover = cover, wind = wind)
    }

    /** Wind 0..1 from km/h: still at 0, a breeze about 10, 0.6 at 40, a gale from about 90. */
    fun windOf(kmh: Double): Float = (0.05 + 0.95 * (1.0 - kotlin.math.exp(-kmh.coerceAtLeast(0.0) / 45.0))).toFloat()
}
