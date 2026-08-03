package com.playground.cfscanner

import android.app.Application

/**
 * Applies the stored UI language before anything is inflated.
 *
 * This has to happen here rather than in an activity: by the time
 * `Activity.onCreate` runs, the theme and layout have already been resolved
 * against whatever locale was active, so a language set there would only take
 * effect after a recreation. Setting it at application start means the first
 * frame is already in the right language.
 */
class CfScannerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // AppCompat persists its own locale choice from API 33 onward, but not
        // below it, so the app keeps its own record and restores it here. Without
        // this, older devices would silently fall back to the device language.
        LocaleRegistry.restore(this)

        // Digit shapes are chosen per language, so the formatter has to agree
        // with the locale that was just applied.
        Format.setLocale(LocaleRegistry.current(this))
    }
}
