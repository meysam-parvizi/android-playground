package com.playground.sensormonitor

import kotlin.math.abs

/**
 * Number formatting for a Persian right-to-left UI.
 *
 * Two rules, both learned the hard way on this platform:
 *
 *  1. **Persian digits for readings.** Measurements read naturally in Persian
 *     numerals in a Persian interface.
 *  2. **Isolate every embedded value.** A signed number dropped into Persian
 *     text gets reordered by the bidirectional algorithm, which turns "-9.81"
 *     into something that reads as "9.81-". Wrapping the run in Unicode isolate
 *     characters pins it in place without changing the visible text.
 */
object Format {

    private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    /** First-strong isolate: opens an embedded run with auto-detected direction. */
    const val FSI = '\u2068'

    /** Pop directional isolate: closes the most recent isolate. */
    const val PDI = '\u2069'

    /** Persian decimal separator (U+066B). */
    const val DECIMAL_SEPARATOR = '\u066B'

    /** Converts every ASCII digit in [text] to its Persian equivalent. */
    fun persianDigits(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c)
        return sb.toString()
    }

    /** Wraps [text] in directional isolates so it keeps its own ordering. */
    fun isolate(text: String): String = "$FSI$text$PDI"

    fun number(value: Int): String = persianDigits(value.toString())

    /**
     * A sensor reading with a fixed number of decimals.
     *
     * Fixed rather than adaptive precision on purpose: these values update many
     * times a second, and a digit count that changes with magnitude makes the
     * row visibly jitter, which is far harder to read than a trailing zero.
     *
     * Uses a real minus sign (U+2212) rather than a hyphen, and isolates the
     * whole token so the sign stays attached to its number in an RTL layout.
     */
    fun reading(value: Float, decimals: Int = 3): String {
        if (value.isNaN()) return isolate("—")
        if (value.isInfinite()) return isolate(if (value > 0) "∞" else "\u2212∞")

        val negative = value < 0
        val rounded = roundTo(abs(value.toDouble()), decimals)
        val text = fixed(rounded, decimals)
            .replace('.', DECIMAL_SEPARATOR)
        val withSign = if (negative && !isAllZeros(text)) "\u2212$text" else text
        return isolate(persianDigits(withSign))
    }

    /** Rounds half-up, avoiding the platform's locale-dependent formatters. */
    private fun roundTo(value: Double, decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        return kotlin.math.round(value * factor) / factor
    }

    /** Renders [value] with exactly [decimals] digits after the separator. */
    private fun fixed(value: Double, decimals: Int): String {
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = kotlin.math.round(value * factor).toLong()
        val whole = scaled / factor
        val frac = scaled % factor
        if (decimals == 0) return whole.toString()
        val fracText = frac.toString().padStart(decimals, '0')
        return "$whole.$fracText"
    }

    /**
     * True when a formatted figure is entirely zeros.
     *
     * Guards against rendering "−۰٫۰۰۰": a tiny negative reading rounds to zero,
     * and a signed zero looks like a bug rather than a measurement.
     */
    private fun isAllZeros(text: String): Boolean =
        text.all { it == '0' || it == '.' || it == DECIMAL_SEPARATOR }
}
