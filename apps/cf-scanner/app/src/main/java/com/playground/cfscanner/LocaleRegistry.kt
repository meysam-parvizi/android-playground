package com.playground.cfscanner

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * A language the UI can be shown in.
 *
 * @param tag BCP-47 tag. Must match the resource qualifier: `fa` maps to
 *   `values-fa/`, `en` to `values-en/`, `de` to `values-de/`, and so on.
 * @param endonym The language's name written in that language. Shown in the
 *   picker so a speaker can find their own language without reading the current
 *   one — a user who cannot read Persian cannot find "انگلیسی" in a Persian list.
 * @param usesPersianDigits Whether numbers render as ۰۱۲۳ rather than 0123.
 *   Tied to the language rather than assumed from direction: Arabic and Persian
 *   are both RTL but use different digit shapes, and Kurdish is RTL with Latin
 *   digits in some regions.
 */
data class AppLocale(
    val tag: String,
    val endonym: String,
    val usesPersianDigits: Boolean = false,
) {
    /** Resolved Java locale, used for layout direction and platform formatting. */
    val javaLocale: Locale get() = Locale.forLanguageTag(tag)
}

/**
 * Every language the app offers.
 *
 * **Adding a language is a two-step change and touches no logic:**
 *
 *  1. Add an entry to [SUPPORTED] below.
 *  2. Create `res/values-<tag>/strings.xml` with the same keys as the default.
 *
 * Nothing else needs editing — the picker, persistence, layout direction and
 * number formatting are all derived from this list. `LocaleRegistryTest` fails
 * if a declared language is missing translations, so a half-added language
 * cannot ship silently.
 */
object LocaleRegistry {

    /**
     * Persian first: it is the default, and the app's audience is
     * Persian-speaking. The order here is the order shown in the picker.
     */
    val SUPPORTED: List<AppLocale> = listOf(
        AppLocale(tag = "fa", endonym = "فارسی", usesPersianDigits = true),
        AppLocale(tag = "en", endonym = "English", usesPersianDigits = false),
    )

    /**
     * The language used when the user has not chosen one.
     *
     * Deliberately Persian rather than the device language: this app exists for
     * users on restricted networks, and defaulting to Persian is far more often
     * right for them than following a phone that ships set to English.
     */
    val DEFAULT: AppLocale = SUPPORTED.first()

    private const val PREFS = "cf_scanner_settings"
    private const val KEY_LOCALE = "ui_locale_tag"

    fun byTag(tag: String?): AppLocale? =
        SUPPORTED.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The user's stored choice, or [DEFAULT].
     *
     * An unknown stored tag falls back to the default rather than failing: a
     * language removed in a later version must not leave the app unable to start.
     */
    fun current(context: Context): AppLocale =
        byTag(prefs(context).getString(KEY_LOCALE, null)) ?: DEFAULT

    /** Persists [locale] and applies it immediately. */
    fun apply(context: Context, locale: AppLocale) {
        prefs(context).edit().putString(KEY_LOCALE, locale.tag).apply()
        applyWithoutPersisting(locale)
    }

    /**
     * Applies a locale to the running app.
     *
     * Uses AppCompat's per-app locale API, which handles configuration and
     * activity recreation itself. Doing it by hand via `Configuration` is
     * deprecated and breaks differently across API levels.
     */
    fun applyWithoutPersisting(locale: AppLocale) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(locale.tag),
        )
    }

    /**
     * Re-applies the stored choice at startup.
     *
     * AppCompat persists its own selection from API 33 onward, but not below it,
     * so the app stores the choice itself and restores it here. Without this the
     * app would silently revert to the device language on older devices.
     */
    fun restore(context: Context) {
        applyWithoutPersisting(current(context))
    }
}
