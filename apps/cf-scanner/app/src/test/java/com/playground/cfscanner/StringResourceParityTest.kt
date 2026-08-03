package com.playground.cfscanner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the translation contract.
 *
 * Adding a language is meant to be two steps — an entry in [LocaleRegistry] and a
 * `values-<tag>/strings.xml` — and these tests fail the build when the second
 * step is incomplete. Without them a half-translated language ships quietly and
 * the user sees a screen that switches language halfway down.
 *
 * The resource XML is parsed directly rather than through `R`, so this stays a
 * plain JVM test with no Android framework or emulator.
 */
class StringResourceParityTest {

    private val resDir = File("src/main/res")

    private fun stringsFor(qualifier: String?): File {
        val dir = if (qualifier == null) "values" else "values-$qualifier"
        return File(resDir, "$dir/strings.xml")
    }

    /** Extracts name -> value for every `<string>` in a resource file. */
    private fun parse(file: File): Map<String, String> {
        assertTrue("missing resource file: ${file.path}", file.exists())
        val text = file.readText()
        return Regex("""<string name="([^"]+)">([\s\S]*?)</string>""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Positional format specifiers, e.g. %1${'$'}s, used in a string. */
    private fun placeholders(value: String): Set<String> =
        Regex("""%(\d)\$""").findAll(value).map { it.groupValues[1] }.toSet()

    private val defaultStrings by lazy { parse(stringsFor(null)) }

    @Test
    fun defaultResourcesAreNotEmpty() {
        assertTrue(
            "no strings found — the parser or the path is wrong",
            defaultStrings.size > 20,
        )
    }

    /**
     * The heart of the contract: every declared language must translate every
     * key. A missing key silently falls back to Persian, which reads as a bug.
     */
    @Test
    fun everyDeclaredLanguageTranslatesEveryKey() {
        for (locale in LocaleRegistry.SUPPORTED) {
            // The default locale's strings live in `values/`, not `values-fa/`.
            val qualifier = if (locale == LocaleRegistry.DEFAULT) null else locale.tag
            val translated = parse(stringsFor(qualifier))

            val missing = defaultStrings.keys - translated.keys
            assertTrue(
                "language '${locale.tag}' is missing ${missing.size} string(s): " +
                    missing.sorted().joinToString(", "),
                missing.isEmpty(),
            )
        }
    }

    /** A stray key in a translation means a rename was only half applied. */
    @Test
    fun translationsDeclareNoUnknownKeys() {
        for (locale in LocaleRegistry.SUPPORTED) {
            val qualifier = if (locale == LocaleRegistry.DEFAULT) null else locale.tag
            val translated = parse(stringsFor(qualifier))

            val unknown = translated.keys - defaultStrings.keys
            assertTrue(
                "language '${locale.tag}' declares keys absent from the default: " +
                    unknown.sorted().joinToString(", "),
                unknown.isEmpty(),
            )
        }
    }

    /**
     * Format specifiers must match exactly.
     *
     * This is the failure that crashes rather than merely looking wrong: a
     * translation using fewer arguments than the caller supplies throws
     * IllegalFormatException at runtime, and only on the screen that uses it.
     */
    @Test
    fun formatPlaceholdersMatchAcrossLanguages() {
        val problems = mutableListOf<String>()

        for (locale in LocaleRegistry.SUPPORTED) {
            val qualifier = if (locale == LocaleRegistry.DEFAULT) null else locale.tag
            val translated = parse(stringsFor(qualifier))

            for ((key, expected) in defaultStrings) {
                val actual = translated[key] ?: continue
                val want = placeholders(expected)
                val got = placeholders(actual)
                if (want != got) {
                    problems += "${locale.tag}/$key expected $want but found $got"
                }
            }
        }

        assertEquals(
            "placeholder mismatch would throw at runtime:\n" + problems.joinToString("\n"),
            emptyList<String>(),
            problems,
        )
    }

    /**
     * No translation may be left blank.
     *
     * An empty value passes the key check but renders as nothing on screen, which
     * is harder to spot than a missing key.
     */
    @Test
    fun noTranslationIsBlank() {
        for (locale in LocaleRegistry.SUPPORTED) {
            val qualifier = if (locale == LocaleRegistry.DEFAULT) null else locale.tag
            for ((key, value) in parse(stringsFor(qualifier))) {
                assertTrue(
                    "${locale.tag}/$key is blank",
                    value.isNotBlank(),
                )
            }
        }
    }

    /**
     * The English strings must contain no Persian text.
     *
     * Catches the common slip of copying the Persian file and translating only
     * part of it, which the key-parity check alone would pass.
     */
    @Test
    fun englishContainsNoPersianText() {
        val english = parse(stringsFor("en"))
        val leaks = english.filter { (_, value) ->
            value.any { it in '\u0600'..'\u06FF' }
        }
        assertTrue(
            "English strings still contain Persian: ${leaks.keys.sorted()}",
            leaks.isEmpty(),
        )
    }

    /**
     * Every language in the registry has a resource directory, and every
     * translation directory has a registry entry.
     *
     * Catches the mirror-image mistakes: a language offered in the picker with no
     * translations behind it, and translations that were written but never
     * declared, so the user can never select them.
     */
    @Test
    fun registryAndResourceDirectoriesAgree() {
        for (locale in LocaleRegistry.SUPPORTED) {
            if (locale == LocaleRegistry.DEFAULT) continue
            assertTrue(
                "'${locale.tag}' is offered in the picker but res/values-${locale.tag}/ does not exist",
                stringsFor(locale.tag).exists(),
            )
        }

        val declared = LocaleRegistry.SUPPORTED.map { it.tag }.toSet()
        val onDisk = (resDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { it.name.removePrefix("values-") }
            // Ignore non-language qualifiers such as values-night and values-w480dp.
            .filter { it.matches(Regex("[a-z]{2}(-r[A-Z]{2})?")) }
            .filter { stringsFor(it).exists() }

        val undeclared = onDisk.toSet() - declared
        assertTrue(
            "translations exist for $undeclared but they are not in LocaleRegistry.SUPPORTED, " +
                "so no user can select them",
            undeclared.isEmpty(),
        )
    }
}
