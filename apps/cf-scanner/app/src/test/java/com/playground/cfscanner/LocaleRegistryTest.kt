package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the language registry.
 *
 * The registry is the single place a developer edits to add a language, so these
 * check the properties the rest of the app relies on.
 */
class LocaleRegistryTest {

    @Test
    fun persianAndEnglishAreBothOffered() {
        val tags = LocaleRegistry.SUPPORTED.map { it.tag }
        assertTrue("Persian must be offered", tags.contains("fa"))
        assertTrue("English must be offered", tags.contains("en"))
    }

    /**
     * Persian is the default deliberately: the app exists for users on
     * restricted networks, so following a phone that ships set to English would
     * be wrong more often than not.
     */
    @Test
    fun persianIsTheDefault() {
        assertEquals("fa", LocaleRegistry.DEFAULT.tag)
        assertEquals(
            "the default should be the first entry, which is also the first picker row",
            LocaleRegistry.SUPPORTED.first(),
            LocaleRegistry.DEFAULT,
        )
    }

    @Test
    fun tagsAreUnique() {
        val tags = LocaleRegistry.SUPPORTED.map { it.tag.lowercase() }
        assertEquals("two languages share a tag", tags.size, tags.distinct().size)
    }

    /**
     * Each language is listed under its own name in its own script, so a speaker
     * can find it without being able to read the current interface language.
     */
    @Test
    fun everyLanguageNamesItselfInItsOwnScript() {
        for (locale in LocaleRegistry.SUPPORTED) {
            assertTrue("${locale.tag} has no endonym", locale.endonym.isNotBlank())
        }
        val persian = LocaleRegistry.byTag("fa")!!
        val english = LocaleRegistry.byTag("en")!!
        assertEquals("فارسی", persian.endonym)
        assertEquals("English", english.endonym)
    }

    @Test
    fun lookupIsCaseInsensitiveAndRejectsUnknownTags() {
        assertNotNull(LocaleRegistry.byTag("fa"))
        assertNotNull(LocaleRegistry.byTag("FA"))
        assertNotNull(LocaleRegistry.byTag("En"))
        // An unknown tag must not resolve, so callers fall back to the default
        // rather than receiving an arbitrary language.
        assertNull(LocaleRegistry.byTag("zz"))
        assertNull(LocaleRegistry.byTag(""))
        assertNull(LocaleRegistry.byTag(null))
    }

    /**
     * Digit shape is declared per language rather than inferred from writing
     * direction, because the two do not track each other: Arabic and Persian are
     * both right-to-left but use different digit forms.
     */
    @Test
    fun digitShapeIsDeclaredNotInferred() {
        assertTrue(LocaleRegistry.byTag("fa")!!.usesPersianDigits)
        assertFalse(LocaleRegistry.byTag("en")!!.usesPersianDigits)
    }

    @Test
    fun tagsResolveToRealJavaLocales() {
        for (locale in LocaleRegistry.SUPPORTED) {
            val resolved = locale.javaLocale
            assertEquals(
                "${locale.tag} did not round-trip through Locale",
                locale.tag.lowercase(),
                resolved.toLanguageTag().lowercase(),
            )
        }
    }
}
