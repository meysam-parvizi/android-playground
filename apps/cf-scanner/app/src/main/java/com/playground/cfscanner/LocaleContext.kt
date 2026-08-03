package com.playground.cfscanner

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Forces a locale onto a [Context] regardless of what the framework decided.
 *
 * `AppCompatDelegate.setApplicationLocales` is the documented way to set a
 * per-app language, but it is not reliable everywhere. On some devices — Samsung
 * in particular, where Persian is not among the system languages — the request is
 * accepted without error and then ignored, leaving the app on the device locale.
 * The result was an English, left-to-right interface with Persian numerals: the
 * app believed it was showing Persian while the resources had resolved English.
 *
 * Wrapping the base context sidesteps that entirely. It rewrites the
 * [Configuration] the activity is created with, so resource resolution has no
 * choice but to use the requested language. It needs no system support and
 * behaves the same on every vendor's build.
 */
object LocaleContext {

    /**
     * Returns [base] reconfigured for [locale].
     *
     * Call from `attachBaseContext`, which runs before any resource is read.
     * Doing it later has no effect, because the configuration has already been
     * resolved by then.
     *
     * Never throws. This is the earliest code in the process, so an exception
     * here kills the app before a single frame is drawn — with no screen on which
     * to report anything. Returning [base] unchanged degrades to the device
     * language, which is visibly wrong but recoverable.
     */
    fun wrap(base: Context, locale: AppLocale): Context = try {
        val target = Locale.forLanguageTag(locale.tag)
        Locale.setDefault(target)

        val config = Configuration(base.resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // A list rather than a single locale, so the framework's own fallback
            // chain starts from the requested language instead of the device's.
            val locales = LocaleList(target)
            LocaleList.setDefault(locales)
            config.setLocales(locales)
        } else {
            @Suppress("DEPRECATION")
            config.locale = target
        }

        // setLayoutDirection is what mirrors the UI for a right-to-left language.
        // Without it the strings would be Persian while the layout stayed
        // left-to-right, which is half of the bug this fixes.
        config.setLayoutDirection(target)

        base.createConfigurationContext(config)
    } catch (_: Exception) {
        base
    }

    /**
     * The language a context's resources actually resolved to.
     *
     * This is the effective locale, not the requested one, so it stays truthful
     * even when the framework overrode the request. [Format] uses it to pick digit
     * shapes, which is what stops Persian numerals appearing in an English UI.
     */
    fun effectiveLocale(context: Context): AppLocale? = try {
        val config = context.resources.configuration
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.locales.takeIf { !it.isEmpty }?.get(0)
        } else {
            @Suppress("DEPRECATION")
            config.locale
        }
        LocaleRegistry.byTag(active?.language)
    } catch (_: Exception) {
        null
    }
}
