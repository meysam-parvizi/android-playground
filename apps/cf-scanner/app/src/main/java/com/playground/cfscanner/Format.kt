package com.playground.cfscanner

/**
 * Number and text formatting for a Persian right-to-left UI.
 *
 * Two rules drive everything here:
 *
 *  1. **Persian digits for measurements, Latin digits for addresses.** Metrics
 *     like ping, jitter, loss, and counts read naturally in Persian numerals,
 *     but an IP address is a technical identifier that gets copied into config
 *     files — converting its digits would make it useless, so [ip] leaves them
 *     alone.
 *
 *  2. **Isolate embedded LTR runs.** A bare number or a Latin word placed inside
 *     Persian text gets reordered by the bidirectional algorithm, which is how
 *     "loss 0%" ended up rendering as "0%لاس". Wrapping such runs in Unicode
 *     isolate characters pins them in place without changing the visible text.
 */
object Format {

    private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    /** First-strong isolate: opens an embedded run whose direction is auto-detected. */
    const val FSI = '\u2068'

    /** Pop directional isolate: closes the most recent isolate. */
    const val PDI = '\u2069'

    /** Converts every ASCII digit in [text] to its Persian equivalent. */
    fun persianDigits(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c)
        }
        return sb.toString()
    }

    /** Renders [value] with Persian digits. */
    fun number(value: Int): String = persianDigits(value.toString())

    /** Renders [value] with Persian digits. */
    fun number(value: Long): String = persianDigits(value.toString())

    /**
     * Wraps [text] in directional isolates so it keeps its own ordering when
     * embedded in Persian text.
     */
    fun isolate(text: String): String = "$FSI$text$PDI"

    /**
     * An IP address for display: Latin digits, isolated so the dots and numbers
     * are never reordered by surrounding right-to-left text.
     */
    fun ip(address: String): String = isolate(address)

    /**
     * A millisecond measurement, e.g. "۲۳ms".
     *
     * The unit stays Latin because that is how it is universally written, and the
     * whole token is isolated so it cannot be split by the bidi algorithm.
     */
    fun millis(value: Long): String = isolate("${persianDigits(value.toString())}ms")

    /** A percentage, e.g. "۰٪", using the Persian percent sign. */
    fun percent(value: Int): String = isolate("${persianDigits(value.toString())}٪")
}
