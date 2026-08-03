package com.playground.cfscanner

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps `res/xml/locales_config.xml` in step with [LocaleRegistry].
 *
 * The file exists for two reasons, and drift in either is a real bug:
 *
 *  - `android:defaultLocale` states which language the unqualified `res/values/`
 *    folder holds. Getting this wrong is what made the UI open in English while
 *    the language was set to Persian, because a request for `fa` matched
 *    `values-en/` instead of falling back to the default folder.
 *  - On Android 13 and above the system reads the locale list to offer a per-app
 *    language picker in Settings. A language missing from the list cannot be
 *    chosen there even though the in-app picker offers it.
 *
 * Parsed as text so this stays a plain JVM test with no Android framework.
 */
class LocaleConfigTest {

    private val configFile = File("src/main/res/xml/locales_config.xml")

    private val contents: String by lazy {
        assertTrue("missing ${configFile.path}", configFile.exists())
        configFile.readText()
    }

    /** Locale tags declared by `<locale android:name="..."/>` entries. */
    private fun declaredLocales(): List<String> =
        Regex("""<locale\s+android:name="([^"]+)"""")
            .findAll(contents)
            .map { it.groupValues[1] }
            .toList()

    private fun defaultLocale(): String? =
        Regex("""android:defaultLocale="([^"]+)"""")
            .find(contents)
            ?.groupValues
            ?.get(1)

    /**
     * The declared default must be the registry's default.
     *
     * This is the assertion that pins the fix: it is what makes `res/values/`
     * resolve as Persian rather than being treated as language-neutral.
     */
    @Test
    fun defaultLocaleMatchesTheRegistryDefault() {
        assertEquals(
            "locales_config.xml declares a different default than LocaleRegistry, " +
                "which is what makes the UI open in the wrong language",
            LocaleRegistry.DEFAULT.tag,
            defaultLocale(),
        )
    }

    @Test
    fun everyRegisteredLanguageIsDeclared() {
        val declared = declaredLocales().map { it.lowercase() }.toSet()
        val missing = LocaleRegistry.SUPPORTED
            .map { it.tag.lowercase() }
            .filterNot { it in declared }

        assertTrue(
            "these languages are offered in the app but absent from " +
                "locales_config.xml, so Android's own language picker cannot " +
                "select them: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun noUndeclaredLanguageIsListed() {
        val registered = LocaleRegistry.SUPPORTED.map { it.tag.lowercase() }.toSet()
        val extra = declaredLocales()
            .map { it.lowercase() }
            .filterNot { it in registered }

        assertTrue(
            "locales_config.xml lists $extra, which the app does not actually " +
                "support — Android would offer a language with no translations",
            extra.isEmpty(),
        )
    }

    @Test
    fun theManifestReferencesTheConfig() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("missing ${manifest.path}", manifest.exists())
        assertTrue(
            "AndroidManifest.xml does not set android:localeConfig, so the file is " +
                "ignored entirely",
            manifest.readText().contains("android:localeConfig=\"@xml/locales_config\""),
        )
    }

    /**
     * The build must also tell the resource compiler which language the default
     * folder holds.
     *
     * `android:localeConfig` is only read by the system from API 33 onward, but
     * the wrong-language bug affects resource resolution on every supported API
     * level, so the compiler flag is what actually fixes it below 33.
     */
    @Test
    fun theBuildDeclaresTheDefaultLocaleToAapt() {
        val buildFile = File("build.gradle.kts")
        assertTrue("missing ${buildFile.path}", buildFile.exists())
        val text = buildFile.readText()

        assertTrue(
            "build.gradle.kts does not pass --default-locale to the resource " +
                "compiler, so res/values/ is still treated as language-neutral on " +
                "API levels below 33",
            text.contains("--default-locale"),
        )
        assertTrue(
            "--default-locale is present but does not name ${LocaleRegistry.DEFAULT.tag}",
            Regex("""--default-locale"\s*,\s*"${LocaleRegistry.DEFAULT.tag}"""")
                .containsMatchIn(text),
        )
    }
}
