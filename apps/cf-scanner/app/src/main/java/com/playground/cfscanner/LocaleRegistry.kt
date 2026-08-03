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
     * The language the user has chosen, or [DEFAULT].
     *
     * Reads only the stored preference, deliberately: this is called from
     * `attachBaseContext`, which runs before AppCompat is usable, and it must
     * express what the app *intends* to show. Compare with
     * [LocaleContext.effectiveLocale], which reports what the resources actually
     * resolved to — the two disagreeing is precisely the bug this design avoids.
     */
    fun preferred(context: Context): AppLocale =
        byTag(prefs(context).getString(KEY_LOCALE, null)) ?: DEFAULT

    /**
     * The language the UI is currently showing.
     *
     * Read from AppCompat first, since that is what actually drives resource
     * resolution. The stored preference is only a fallback for the window before
     * AppCompat has loaded its own record.
     */
    fun current(context: Context): AppLocale {
        val active = AppCompatDelegate.getApplicationLocales()
        if (!active.isEmpty) {
            byTag(active[0]?.language)?.let { return it }
        }
        return preferred(context)
    }

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
     * Establishes the language on first launch.
     *
     * Only acts when no locale has been chosen yet. Once the user has picked one,
     * AppCompat holds it — restored from its own storage via the
     * `AppLocalesMetadataHolderService` entry in the manifest — and re-applying
     * here would fight that.
     *
     * The first-launch case is what the fix is for. With nothing set, AppCompat
     * leaves the app on the device locale, so an English phone showed an English,
     * left-to-right interface while the picker reported Persian: the picker read
     * the app's own default while the resources followed the device. Applying the
     * default explicitly makes the two agree from the very first frame.
     */
    fun restore(context: Context) {
        if (!AppCompatDelegate.getApplicationLocales().isEmpty) return

        val stored = byTag(prefs(context).getString(KEY_LOCALE, null))
        val locale = stored ?: DEFAULT

        // Record the default too, so the stored value and what is displayed never
        // disagree — that mismatch is exactly what the bug looked like.
        if (stored == null) {
            prefs(context).edit().putString(KEY_LOCALE, locale.tag).apply()
        }
        applyWithoutPersisting(locale)
    }
}
