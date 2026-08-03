package com.playground.cfscanner

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the configuration-level locale override.
 *
 * `AppCompatDelegate.setApplicationLocales` is not honoured everywhere. On a
 * Samsung device whose system does not list Persian, the call was accepted and
 * then ignored: the app came up with English text in a left-to-right layout while
 * showing Persian numerals, because the formatter read the *requested* language
 * and the resources had resolved the *device* one. The same build was correct on
 * a Xiaomi device, which is what made it look device-specific rather than like a
 * design flaw.
 *
 * Two properties prevent it, and neither is obvious from reading the code:
 *
 *  - the locale is forced onto the [android.content.res.Configuration] in
 *    `attachBaseContext`, which no vendor can override;
 *  - anything that has to agree with the visible text reads the *effective*
 *    locale rather than the stored preference.
 *
 * Verified by source inspection so this stays a plain JVM test — exercising it
 * properly would need an instrumented run on a device that reproduces the vendor
 * behaviour.
 */
class LocaleOverrideTest {

    private fun source(name: String): String {
        val file = File("src/main/java/com/playground/cfscanner/$name")
        assertTrue("missing ${file.path}", file.exists())
        return file.readText()
    }

    private val activity by lazy { source("MainActivity.kt") }
    private val application by lazy { source("CfScannerApp.kt") }
    private val localeContext by lazy { source("LocaleContext.kt") }

    /**
     * The activity is the decisive one: each activity gets its own configuration,
     * so wrapping only the Application still leaves it on the device language.
     */
    @Test
    fun theActivityOverridesItsBaseContext() {
        assertTrue(
            "MainActivity must override attachBaseContext, otherwise the activity " +
                "is created with the framework's locale on devices that ignore " +
                "setApplicationLocales",
            Regex("""override fun attachBaseContext""").containsMatchIn(activity),
        )
        assertTrue(
            "attachBaseContext must wrap the context with the chosen locale",
            activity.contains("LocaleContext.wrap("),
        )
    }

    @Test
    fun theApplicationOverridesItsBaseContext() {
        assertTrue(
            "CfScannerApp must override attachBaseContext so the language is set " +
                "before any resource is read",
            Regex("""override fun attachBaseContext""").containsMatchIn(application),
        )
        assertTrue(
            "the Application must wrap its context with the chosen locale",
            application.contains("LocaleContext.wrap("),
        )
    }

    /**
     * Rewriting the configuration is what makes the override independent of vendor
     * behaviour, so the pieces that do it must stay present.
     */
    @Test
    fun theWrapperRewritesTheConfiguration() {
        assertTrue(
            "LocaleContext.wrap must build a new Configuration",
            localeContext.contains("Configuration("),
        )
        assertTrue(
            "wrap must call createConfigurationContext, which is what actually " +
                "applies the locale to resource resolution",
            localeContext.contains("createConfigurationContext("),
        )
        assertTrue(
            "wrap must set the layout direction, or Persian text would render in a " +
                "left-to-right layout",
            localeContext.contains("setLayoutDirection("),
        )
    }

    /**
     * The formatter must follow the resolved locale, never the requested one.
     *
     * Reading the stored preference here is exactly what put Persian numerals in
     * an English interface.
     */
    @Test
    fun theFormatterFollowsTheEffectiveLocale() {
        assertTrue(
            "MainActivity must set the formatter from LocaleContext.effectiveLocale, " +
                "not from the stored preference, or the digits can disagree with the " +
                "text on screen",
            Regex("""Format\.setLocale\(\s*LocaleContext\.effectiveLocale""")
                .containsMatchIn(activity),
        )
        assertTrue(
            "CfScannerApp must do the same",
            Regex("""Format\.setLocale\(\s*LocaleContext\.effectiveLocale""")
                .containsMatchIn(application),
        )
    }

    /**
     * effectiveLocale must read the configuration, since that is the only thing
     * that reflects what the resources really resolved to.
     */
    @Test
    fun effectiveLocaleReadsTheConfiguration() {
        assertTrue(
            "effectiveLocale must read resources.configuration; anything else " +
                "reports the intended language rather than the actual one",
            localeContext.contains("resources.configuration"),
        )
    }

    /**
     * A language switch must recreate the activity explicitly.
     *
     * Relying on `setApplicationLocales` to trigger it means nothing happens at
     * all on a device that ignores the call.
     */
    @Test
    fun switchingLanguageRecreatesTheActivity() {
        assertTrue(
            "the language picker must call recreate(), rather than relying on " +
                "setApplicationLocales to restart the activity",
            Regex("""recreate\(\)""").containsMatchIn(activity),
        )
    }
}
