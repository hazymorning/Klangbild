package io.github.hazymorning.klangbild.text

import kotlinx.datetime.LocalDate

/**
 * How a language in one region writes numbers, percentages, dates and the time of day, as
 * Unicode's CLDR has it ([REGIONS]). Numbers are rounded from the shortest decimal that is the
 * same double, as ICU, which Android's formats come from, rounds them.
 */
internal class Region(
    private val decimalSeparator: String,
    private val groupSeparator: String,
    private val minus: String,
    /** What follows a percentage: "%", or " %" with a no-break space. */
    val percentSuffix: String,
    /** Digits in the group nearest the decimal separator, and in the ones before it (0: as many). */
    private val grouping: Int,
    private val secondaryGrouping: Int,
    /** A number has at least this many digits before its first separator, or none. */
    private val minimumGrouping: Int,
    /** The date with its weekday, as an ICU pattern of EEEE, MMMM and d. */
    private val datePattern: String,
    private val months: Array<String>,
    /** Monday first. */
    private val weekdays: Array<String>,
    private val am: String,
    private val pm: String,
    /** Whether the region's clock has 24 hours. */
    val clock24: Boolean,
) {
    fun integer(value: Long): String = (if (value < 0) minus else "") + grouped(value.toString().removePrefix("-"))

    /**
     * [value] with at least [minFraction] and at most [maxFraction] decimals, rounded half to
     * even, or half away from zero with [halfUp].
     */
    fun decimal(value: Double, minFraction: Int, maxFraction: Int, halfUp: Boolean): String {
        val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
        val sign = if (negative) minus else ""
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return sign + "\u221E"
        val (digits, point) = shortestDigits(if (negative) -value else value)
        // The figures written: the whole number (at least a zero) and every decimal kept. Figure
        // k is digits[k - shift]; the first digit left out is digits[cut].
        var whole = maxOf(point, 1)
        val shift = whole - point
        var figures = IntArray(whole + maxFraction) { k -> digits.getOrElse(k - shift) { 0 } }
        val cut = figures.size - shift
        if (cut < digits.size && roundsUp(digits, cut, halfUp, figures.last())) {
            var k = figures.lastIndex
            while (k >= 0 && figures[k] == 9) figures[k--] = 0
            if (k >= 0) {
                figures[k]++
            } else {
                figures = intArrayOf(1) + figures
                whole++
            }
        }
        val integer = figures.take(whole).joinToString("").trimStart('0').ifEmpty { "0" }
        val fraction = figures.drop(whole).joinToString("").trimEnd('0').padEnd(minFraction, '0')
        return sign + grouped(integer) + if (fraction.isEmpty()) "" else decimalSeparator + fraction
    }

    fun date(date: LocalDate): String {
        val out = StringBuilder()
        var i = 0
        while (i < datePattern.length) {
            val c = datePattern[i]
            var run = 1
            while (i + run < datePattern.length && datePattern[i + run] == c) run++
            when {
                c == '\'' -> {
                    val end = datePattern.indexOf('\'', i + 1)
                    out.append(if (end == i + 1) "'" else datePattern.substring(i + 1, end))
                    i = end + 1
                    continue
                }
                c == 'E' -> out.append(weekdays[date.dayOfWeek.ordinal])
                c == 'M' -> out.append(months[date.month.ordinal])
                c == 'd' -> out.append(date.day.toString().padStart(run, '0'))
                else -> repeat(run) { out.append(c) }
            }
            i += run
        }
        return out.toString()
    }

    /** "AM" or "PM" for [hour] (0 to 23), as the region writes them. */
    fun dayPeriod(hour: Int): String = if (hour < 12) am else pm

    private fun grouped(whole: String): String {
        if (whole.length < grouping + minimumGrouping) return whole
        val out = StringBuilder()
        var end = whole.length
        var size = grouping
        while (end > size) {
            out.insert(0, groupSeparator + whole.substring(end - size, end))
            end -= size
            if (secondaryGrouping > 0) size = secondaryGrouping
        }
        return out.insert(0, whole.substring(0, end)).toString()
    }

    /** Whether the digits left out from [cut] on take [last], the last digit kept, one up. */
    private fun roundsUp(digits: IntArray, cut: Int, halfUp: Boolean, last: Int): Boolean {
        // Before the first digit only zeros are left out.
        if (cut < 0) return false
        val first = digits[cut]
        return when {
            first > 5 -> true
            first < 5 -> false
            halfUp || (cut + 1 until digits.size).any { digits[it] != 0 } -> true
            else -> last % 2 == 1
        }
    }

    private companion object {
        /**
         * The digits of the shortest decimal that reads back as [value] (positive), and where its
         * decimal point stands: 12.5 is 1, 2, 5 with the point after two digits.
         */
        fun shortestDigits(value: Double): Pair<IntArray, Int> {
            val text = value.toString()
            val mantissa = text.substringBefore('E').substringBefore('e')
            val exponent = text.substringAfter('E', text.substringAfter('e', "0")).removePrefix("+").toInt()
            val whole = mantissa.substringBefore('.')
            val all = (whole + mantissa.substringAfter('.', "")).trimEnd('0')
            val leading = all.length - all.trimStart('0').length
            val digits = all.trimStart('0').map { it - '0' }.toIntArray()
            return if (digits.isEmpty()) intArrayOf(0) to 1 else digits to whole.length + exponent - leading
        }
    }
}

/** How this locale writes numbers and dates: its region's way where CLDR has one, else its language's. */
internal fun Locale.region(): Region = REGIONS[toLanguageTag()] ?: REGIONS[language] ?: REGIONS.getValue("en")
