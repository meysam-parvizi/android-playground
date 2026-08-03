package com.playground.cfscanner

import android.app.Application

/**
 * Establishes the UI language before the first activity is created.
 *
 * On first launch nothing is stored, and AppCompat leaves the app on the device
 * locale — which is how an English phone ended up showing an English,
 * left-to-right interface while the picker reported Persian. Applying the app's
 * default here makes the two agree from the first frame.
 *
 * The `AppLocalesMetadataHolderService` entry in the manifest is what makes this
 * work on Android 12 and below: AppCompat reads the saved locale during startup
 * only when that service is declared.
 */
class CfScannerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // No-ops once a language has been chosen; AppCompat restores that itself.
        LocaleRegistry.restore(this)

        // Digit shapes follow the language. MainActivity re-applies this on every
        // create, so a switch stays consistent after the recreate.
        Format.setLocale(LocaleRegistry.current(this))
    }
}
