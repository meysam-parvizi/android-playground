package com.playground.cfscanner

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that formatting follows the selected language.
 *
 * The bug these guard against is a half-translated interface: English labels
 * around Persian numerals, which looks worse than either language alone.
 */
class LocaleAwareFormatTest {

    private val persianDigits = '\u06F0'..'\u06F9'

    private fun visible(s: String) =
        s.replace(Format.FSI.toString(), "").replace(Format.PDI.toString(), "")

    private fun withLocale(tag: String, block: () -> Unit) {
        Format.setLocale(LocaleRegistry.byTag(tag)!!)
        block()
    }

    @After
    fun restoreDefault() {
        Format.setLocale(LocaleRegistry.DEFAULT)
    }

    @Test
    fun persianRendersPersianDigits() = withLocale("fa") {
        assertEquals("۸۵", Format.number(85))
        assertEquals("۳۰۰", Format.number(300))
        assertEquals("۲۳", visible(Format.millis(23)))
    }

    @Test
    fun englishRendersAsciiDigits() = withLocale("en") {
        assertEquals("85", Format.number(85))
        assertEquals("300", Format.number(300))
        assertEquals("23", visible(Format.millis(23)))
    }

    @Test
    fun englishOutputContainsNoPersianGlyphs() = withLocale("en") {
        val samples = listOf(
            Format.number(85),
            Format.number(1234L),
            visible(Format.millis(250)),
            visible(Format.percent(15)),
            visible(Format.speed(1_091_731)),
        )
        for (sample in samples) {
            assertTrue(
                "Persian digits leaked into the English UI: $sample",
                sample.none { it in persianDigits },
            )
            assertFalse(
                "the Persian percent sign leaked into the English UI: $sample",
                sample.contains('٪'),
            )
            assertFalse(
                "the Persian decimal separator leaked into the English UI: $sample",
                sample.contains('\u066B'),
            )
        }
    }

    @Test
    fun persianOutputContainsNoAsciiDigits() = withLocale("fa") {
        val samples = listOf(
            Format.number(85),
            visible(Format.millis(250)),
            visible(Format.percent(15)),
            visible(Format.speed(1_091_731)),
        )
        for (sample in samples) {
            assertTrue(
                "ASCII digits remained in the Persian UI: $sample",
                sample.none { it in '0'..'9' },
            )
        }
    }

    /**
     * IP addresses keep ASCII digits in every language: they are copied into
     * proxy configuration files, where Persian numerals would be useless.
     */
    @Test
    fun ipAddressesStayAsciiInEveryLanguage() {
        for (locale in LocaleRegistry.SUPPORTED) {
            Format.setLocale(locale)
            val rendered = visible(Format.ip("104.16.132.229"))
            assertEquals(
                "the IP was altered under '${locale.tag}'",
                "104.16.132.229",
                rendered,
            )
        }
    }

    @Test
    fun percentSignFollowsTheLanguage() {
        withLocale("fa") {
            assertTrue(visible(Format.percent(0)).endsWith("٪"))
        }
        withLocale("en") {
            assertTrue(visible(Format.percent(0)).endsWith("%"))
        }
    }

    @Test
    fun decimalSeparatorFollowsTheLanguage() {
        withLocale("fa") {
            // U+066B is the Persian thousands/decimal separator.
            assertTrue(
                "expected the Persian separator",
                visible(Format.speed(1_091_731)).contains('\u066B'),
            )
        }
        withLocale("en") {
            assertTrue(
                "expected a plain full stop",
                visible(Format.speed(1_091_731)).contains('.'),
            )
        }
    }

    /**
     * Isolation is applied in both languages. It is invisible in a
     * left-to-right layout, and applying it unconditionally keeps one code path
     * rather than two.
     */
    @Test
    fun embeddedValuesAreIsolatedInEveryLanguage() {
        for (locale in LocaleRegistry.SUPPORTED) {
            Format.setLocale(locale)
            val values = listOf(
                Format.ip("1.2.3.4"),
                Format.millis(23),
                Format.percent(5),
                Format.speed(1_000_000),
            )
            for (value in values) {
                assertEquals(
                    "unbalanced isolate under '${locale.tag}': $value",
                    1,
                    value.count { it == Format.FSI },
                )
                assertEquals(1, value.count { it == Format.PDI })
            }
        }
    }

    /**
     * Switching language changes subsequent output.
     *
     * Format holds the locale as state, so this confirms a change actually takes
     * effect rather than being read once at class-load time.
     */
    @Test
    fun switchingLanguageChangesSubsequentOutput() {
        Format.setLocale(LocaleRegistry.byTag("fa")!!)
        val persian = Format.number(42)

        Format.setLocale(LocaleRegistry.byTag("en")!!)
        val english = Format.number(42)

        assertEquals("۴۲", persian)
        assertEquals("42", english)
        assertFalse("the locale change had no effect", persian == english)
    }

    /**
     * persianDigits stays unconditional.
     *
     * It is kept for the rare case where Persian digits are wanted regardless of
     * language; the UI uses the language-sensitive localeDigits instead.
     */
    @Test
    fun explicitPersianDigitsIgnoresTheSelectedLanguage() = withLocale("en") {
        assertEquals("۸۵", Format.persianDigits("85"))
        assertEquals("85", Format.localeDigits("85"))
    }
}
