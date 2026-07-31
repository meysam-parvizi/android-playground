package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for Persian number formatting and bidirectional isolation.
 *
 * Two invariants matter here:
 *  - measurements read in Persian digits, but **IP addresses must stay ASCII**
 *    because they get copied into proxy configs;
 *  - values embedded in Persian labels must be directionally isolated, otherwise
 *    the bidi algorithm reorders them (this is what turned "loss 0%" into the
 *    garbled "0%لاس" on screen).
 */
class FormatTest {

    private fun stripIsolates(s: String) =
        s.replace(Format.FSI.toString(), "").replace(Format.PDI.toString(), "")

    private val persianDigitRange = '\u06F0'..'\u06F9'

    @Test
    fun convertsAsciiDigitsToPersian() {
        assertEquals("۰", Format.number(0))
        assertEquals("۲۳", Format.number(23))
        assertEquals("۸۵", Format.number(85))
        assertEquals("۳۰۰", Format.number(300))
        assertEquals("۱۲۳۴۵۶۷۸۹۰", Format.persianDigits("1234567890"))
    }

    @Test
    fun leavesNonDigitsUntouched() {
        assertEquals("سالم ۴۳", Format.persianDigits("سالم 43"))
        assertEquals("", Format.persianDigits(""))
        assertEquals("abc", Format.persianDigits("abc"))
    }

    @Test
    fun ipKeepsLatinDigits() {
        // The decisive test: a converted address would be useless when pasted
        // into a client config.
        val rendered = Format.ip("104.24.27.229")
        val visible = stripIsolates(rendered)

        assertEquals("104.24.27.229", visible)
        assertTrue(
            "an IP must never contain Persian digits",
            visible.none { it in persianDigitRange },
        )
    }

    @Test
    fun ipIsDirectionallyIsolated() {
        val rendered = Format.ip("172.64.154.106")
        assertTrue("IP must open an isolate", rendered.startsWith(Format.FSI))
        assertTrue("IP must close its isolate", rendered.endsWith(Format.PDI))
    }

    @Test
    fun millisIsABarePersianNumeralWithNoUnit() {
        // The "ms" suffix was dropped: a Latin unit beside Persian digits reads
        // badly in an RTL row, and the chip label already says it is a duration.
        assertEquals("۲۳", stripIsolates(Format.millis(23)))
        assertEquals("۰", stripIsolates(Format.millis(0)))
        assertEquals("۱۲۵۰", stripIsolates(Format.millis(1250)))
        assertFalse("no unit should be appended", stripIsolates(Format.millis(23)).contains("ms"))
    }

    @Test
    fun percentUsesPersianDigitsAndSign() {
        assertEquals("۰٪", stripIsolates(Format.percent(0)))
        assertEquals("۱۵٪", stripIsolates(Format.percent(15)))
        // The ASCII '%' is what got dragged to the wrong side of the number.
        assertFalse("use the Persian percent sign", stripIsolates(Format.percent(5)).contains('%'))
    }

    @Test
    fun everyEmbeddedValueIsIsolated() {
        // Anything dropped into a Persian label must be pinned, or the bidi
        // algorithm will reorder it.
        val values = listOf(Format.ip("1.2.3.4"), Format.millis(120), Format.percent(3))
        for (v in values) {
            assertEquals("unbalanced isolates in '$v'", 1, v.count { it == Format.FSI })
            assertEquals("unbalanced isolates in '$v'", 1, v.count { it == Format.PDI })
        }
    }

    @Test
    fun isolateWrapsExactlyOnce() {
        val wrapped = Format.isolate("test")
        assertEquals("${Format.FSI}test${Format.PDI}", wrapped)
        assertEquals("test", stripIsolates(wrapped))
    }

    @Test
    fun formattedMetricsCarryNoAsciiDigitsOrLatinUnits() {
        // Everything except the address should read as pure Persian.
        val metrics = listOf(
            stripIsolates(Format.millis(250)),
            stripIsolates(Format.percent(12)),
            Format.number(42),
        )
        for (m in metrics) {
            assertTrue("'$m' still contains ASCII digits", m.none { it in '0'..'9' })
            assertTrue("'$m' should carry no Latin letters", m.none { it in 'a'..'z' || it in 'A'..'Z' })
        }
    }
}
