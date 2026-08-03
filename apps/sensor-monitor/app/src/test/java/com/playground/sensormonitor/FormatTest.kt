package com.playground.sensormonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for reading formatting.
 *
 * These values update many times a second in a right-to-left layout, which makes
 * two things matter more than they would elsewhere: the sign must stay attached
 * to its number, and the width must not change as the magnitude does.
 */
class FormatTest {

    private fun visible(s: String) =
        s.replace(Format.FSI.toString(), "").replace(Format.PDI.toString(), "")

    private val minus = '\u2212'

    @Test
    fun rendersPersianDigits() {
        assertEquals("۰", Format.persianDigits("0"))
        assertEquals("۹۸۱", Format.persianDigits("981"))
        assertEquals("۱۲۳۴۵۶۷۸۹۰", Format.persianDigits("1234567890"))
    }

    @Test
    fun leavesNonDigitsAlone() {
        assertEquals("m/s²", Format.persianDigits("m/s²"))
        assertEquals("", Format.persianDigits(""))
    }

    @Test
    fun usesThePersianDecimalSeparator() {
        val v = visible(Format.reading(9.81f))
        assertTrue("expected U+066B, got '$v'", v.contains(Format.DECIMAL_SEPARATOR))
        assertFalse("an ASCII dot leaked through", v.contains('.'))
    }

    @Test
    fun alwaysRendersTheSameNumberOfDecimals() {
        // A digit count that changes with magnitude makes a live row visibly
        // jitter, which is harder to read than a trailing zero.
        for (value in listOf(0.5f, 9.8f, 12.345f, 123.4f, 1234.5f)) {
            val v = visible(Format.reading(value))
            val fraction = v.substringAfter(Format.DECIMAL_SEPARATOR)
            assertEquals("wrong precision for $value -> $v", 3, fraction.length)
        }
    }

    @Test
    fun keepsRealNegativeSigns() {
        val v = visible(Format.reading(-9.81f))
        assertTrue("lost the minus sign: $v", v.startsWith(minus))
    }

    /**
     * A tiny negative reading rounds to zero, and "−۰٫۰۰۰" reads as a bug rather
     * than a measurement. Sensors sit near zero constantly, so this is common.
     */
    @Test
    fun neverRendersASignedZero() {
        for (value in listOf(-0.0001f, -0.0004f, -0.00049f)) {
            val v = visible(Format.reading(value))
            assertFalse("rendered a signed zero for $value: $v", v.contains(minus))
        }
        // A value large enough to survive rounding must keep its sign.
        assertTrue(visible(Format.reading(-0.001f)).contains(minus))
    }

    @Test
    fun usesATrueMinusNotAHyphen() {
        val v = visible(Format.reading(-1.5f))
        assertTrue(v.contains('\u2212'))
        assertFalse("hyphen-minus reorders badly in RTL", v.contains('-'))
    }

    @Test
    fun everyValueIsDirectionallyIsolated() {
        // Without isolates the bidi algorithm moves the sign away from its digits.
        for (value in listOf(-9.81f, 0f, 3.14f, 1234.5f)) {
            val s = Format.reading(value)
            assertEquals("unbalanced isolates for $value", 1, s.count { it == Format.FSI })
            assertEquals("unbalanced isolates for $value", 1, s.count { it == Format.PDI })
            assertTrue(s.startsWith(Format.FSI))
            assertTrue(s.endsWith(Format.PDI))
        }
    }

    @Test
    fun readingsCarryNoAsciiDigits() {
        for (value in listOf(-9.81f, 0f, 23.5f, 1234.5678f)) {
            val v = visible(Format.reading(value))
            assertTrue("ASCII digits leaked: $v", v.none { it in '0'..'9' })
        }
    }

    @Test
    fun specialValuesDoNotCrash() {
        // A disconnected or faulty sensor can report these.
        assertEquals("—", visible(Format.reading(Float.NaN)))
        assertEquals("∞", visible(Format.reading(Float.POSITIVE_INFINITY)))
        assertTrue(visible(Format.reading(Float.NEGATIVE_INFINITY)).contains('∞'))
    }

    @Test
    fun roundsRatherThanTruncates() {
        assertEquals("۹٫۸۰۷", visible(Format.reading(9.80665f)))
        assertEquals("۰٫۰۱۲", visible(Format.reading(0.0123f)))
    }

    @Test
    fun honoursACustomPrecision() {
        assertEquals("۹٫۸", visible(Format.reading(9.81f, decimals = 1)))
        assertEquals("۱۰", visible(Format.reading(9.81f, decimals = 0)))
    }
}
