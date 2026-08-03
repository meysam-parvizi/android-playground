package com.playground.cfscanner

import java.io.File
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
     * `android:defaultLocale` must stay out of the file.
     *
     * The attribute requires compileSdk 35 and this app targets 34, so its
     * presence fails resource linking with "attribute android:defaultLocale not
     * found". It only labels the fallback in the system's own picker, and the
     * app's default is enforced by [LocaleRegistry.DEFAULT] regardless.
     */
    @Test
    fun defaultLocaleAttributeIsAbsentWhileCompileSdkIsBelow35() {
        assertTrue(
            "android:defaultLocale requires compileSdk 35; with compileSdk 34 it " +
                "fails resource linking",
            defaultLocale() == null,
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
     * The default language must have its own qualified folder.
     *
     * This is the assertion that pins the fix for the UI opening in English while
     * the language was set to Persian. Persian originally lived in the unqualified
     * `res/values/` folder with no `values-fa/`, and the resource system consults
     * the unqualified set only after every qualified folder has failed to match —
     * so on an English-locale device a request for `fa` matched `values-en/` from
     * the locale chain and English won.
     *
     * Note that `android:localeConfig` does not solve this: the system reads it
     * only from API 33 onward, while the bug affects every supported API level.
     * A real `values-fa/` folder is what fixes it.
     */
    @Test
    fun theDefaultLanguageHasItsOwnQualifiedFolder() {
        val tag = LocaleRegistry.DEFAULT.tag
        val folder = File("src/main/res/values-$tag/strings.xml")
        assertTrue(
            "the default language '$tag' has no res/values-$tag/strings.xml. Leaving it " +
                "only in the unqualified values/ folder makes the UI open in whatever " +
                "language matches the device instead.",
            folder.exists(),
        )
    }
}
