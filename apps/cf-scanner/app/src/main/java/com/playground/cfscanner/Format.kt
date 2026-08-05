package com.playground.cfscanner

/**
 * Number and text formatting for the selected UI language.
 *
 * Three rules drive everything here:
 *
 *  1. **Digit shape follows the language, not the direction.** Persian renders
 *     ۰۱۲۳; English renders 0123. These are tied to the language via
 *     [AppLocale.usesPersianDigits] rather than inferred from writing direction,
 *     because the two do not track each other — Arabic and Persian are both
 *     right-to-left but use different digit forms.
 *
 *  2. **IP addresses always keep ASCII digits.** They get copied into proxy
 *     configs, where Persian numerals would be useless. This holds in every
 *     language.
 *
 *  3. **Isolate embedded runs.** A number or Latin word placed inside
 *     right-to-left text gets reordered by the bidirectional algorithm, which is
 *     how "loss 0%" once rendered as "0%لاس". Wrapping such runs in Unicode
 *     isolate characters pins them without changing the visible text. Harmless
 *     in a left-to-right layout, so it is applied unconditionally rather than
 *     branching on direction.
 */
object Format {

    private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    /** First-strong isolate: opens an embedded run whose direction is auto-detected. */
    const val FSI = '\u2068'

    /** Pop directional isolate: closes the most recent isolate. */
    const val PDI = '\u2069'

    /**
     * The locale used for formatting.
     *
     * Held here so call sites stay terse — a formatter argument threaded through
     * every chip and status line would add noise at dozens of sites for a value
     * that only changes when the user switches language. Set once at startup and
     * on each change.
     */
    @Volatile
    private var locale: AppLocale = LocaleRegistry.DEFAULT

    fun setLocale(newLocale: AppLocale) {
        locale = newLocale
    }

    /**
     * Layout direction of the active language.
     *
     * The single source of truth for direction in this app. Views cannot be
     * asked: `layoutDirection` is a *resolved* value that reports LTR before
     * resolution has run, and `layoutDirection="locale"` resolves against the
     * process-global `Locale.getDefault()` — which this app rewrites when
     * applying its language. Both made RecyclerView items disagree by creation
     * order. This value is set before any view exists and never changes without
     * an activity recreate.
     */
    val layoutDirection: Int
        get() = if (locale.isRightToLeft) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR

    /** Mirrors View.LAYOUT_DIRECTION_LTR without depending on the framework. */
    const val LAYOUT_DIRECTION_LTR = 0

    /** Mirrors View.LAYOUT_DIRECTION_RTL. */
    const val LAYOUT_DIRECTION_RTL = 1


    /**
     * Converts ASCII digits to the digit shape of the active language.
     *
     * A no-op for languages that use Latin digits.
     */
    fun localeDigits(text: String): String {
        if (text.isEmpty() || !locale.usesPersianDigits) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c)
        return sb.toString()
    }

    /**
     * Converts ASCII digits to Persian regardless of the active language.
     *
     * Kept for the rare case where Persian digits are wanted explicitly; the
     * language-sensitive [localeDigits] is what the UI should use.
     */
    fun persianDigits(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c)
        return sb.toString()
    }

    /** Wraps [text] in directional isolates so it keeps its own ordering. */
    fun isolate(text: String): String = "$FSI$text$PDI"

    fun number(value: Int): String = localeDigits(value.toString())

    fun number(value: Long): String = localeDigits(value.toString())

    /**
     * An IP address for display: ASCII digits, isolated so the dots and numbers
     * are never reordered by surrounding right-to-left text.
     */
    fun ip(address: String): String = isolate(address)

    /**
     * A millisecond measurement as a bare numeral.
     *
     * The unit is omitted deliberately: mixing a Latin "ms" with Persian digits
     * reads badly in a right-to-left row, and the chip label already establishes
     * that the value is a duration.
     */
    fun millis(value: Long): String = isolate(localeDigits(value.toString()))

    /** A percentage, using the percent sign appropriate to the language. */
    fun percent(value: Int): String {
        val sign = if (locale.usesPersianDigits) "٪" else "%"
        return isolate("${localeDigits(value.toString())}$sign")
    }

    /**
     * A transfer rate in megabits per second.
     *
     * One decimal place because a scan measures a short burst — more precision
     * would imply accuracy the measurement does not have.
     */
    fun speed(bytesPerSecond: Long): String {
        val mbps = bytesPerSecond * 8.0 / 1_000_000.0
        val rounded = kotlin.math.round(mbps * 10) / 10.0
        val separator = if (locale.usesPersianDigits) '\u066B' else '.'
        val text = localeDigits(rounded.toString().replace('.', separator))
        return isolate(text)
    }
}
