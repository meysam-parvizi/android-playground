package com.playground.cfscanner

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direction is a property of the selected language, computed without a View.
 *
 * Every previous attempt derived it from a view and failed the same way. A
 * View's `layoutDirection` is a *resolved* value that reports LTR until
 * resolution runs, and `android:layoutDirection="locale"` resolves against the
 * process-global `Locale.getDefault()` — which this app rewrites when applying
 * its language. RecyclerView creates holders lazily, so items snapshotted
 * different answers and direction tracked scroll position rather than content.
 *
 * These tests assert the value itself, not the presence of a line of source.
 * The earlier string-matching assertions passed through four broken releases.
 */
class FormatDirectionTest {

    @After
    fun restoreDefault() {
        Format.setLocale(LocaleRegistry.DEFAULT)
    }

    @Test
    fun persianIsRightToLeft() {
        Format.setLocale(LocaleRegistry.byTag("fa")!!)
        assertEquals(Format.LAYOUT_DIRECTION_RTL, Format.layoutDirection)
    }

    @Test
    fun englishIsLeftToRight() {
        Format.setLocale(LocaleRegistry.byTag("en")!!)
        assertEquals(Format.LAYOUT_DIRECTION_LTR, Format.layoutDirection)
    }

    @Test
    fun theseConstantsMatchTheFrameworkValues() {
        // View.LAYOUT_DIRECTION_LTR == 0, LAYOUT_DIRECTION_RTL == 1. They are
        // mirrored rather than imported so this stays a plain JVM test, so the
        // values have to be pinned.
        assertEquals(0, Format.LAYOUT_DIRECTION_LTR)
        assertEquals(1, Format.LAYOUT_DIRECTION_RTL)
    }

    @Test
    fun directionIsStableAcrossRepeatedReads() {
        // The bug's signature was the same input yielding different answers at
        // different moments. Nothing here depends on view state or timing.
        Format.setLocale(LocaleRegistry.byTag("fa")!!)
        val reads = List(50) { Format.layoutDirection }.distinct()
        assertEquals(listOf(Format.LAYOUT_DIRECTION_RTL), reads)
    }

    @Test
    fun switchingLanguageSwitchesDirection() {
        Format.setLocale(LocaleRegistry.byTag("fa")!!)
        assertEquals(Format.LAYOUT_DIRECTION_RTL, Format.layoutDirection)
        Format.setLocale(LocaleRegistry.byTag("en")!!)
        assertEquals(Format.LAYOUT_DIRECTION_LTR, Format.layoutDirection)
    }

    @Test
    fun everyRegisteredLanguageDeclaresItsDirection() {
        // A new language added without setting isRightToLeft would silently
        // render LTR. Persian must be the RTL one; anything else added later
        // has to be a deliberate choice.
        val rtl = LocaleRegistry.SUPPORTED.filter { it.isRightToLeft }.map { it.tag }
        assertEquals(listOf("fa"), rtl)
    }
}
