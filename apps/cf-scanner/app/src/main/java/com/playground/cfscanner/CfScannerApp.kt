package com.playground.cfscanner

import android.app.Application
import android.content.Context

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

    /**
     * Applies the language before any resource is read.
     *
     * `setApplicationLocales` alone proved unreliable: on devices whose system
     * does not list Persian — Samsung being the case that surfaced it — the
     * request is accepted and then ignored, and the app stays on the device
     * locale. Rewriting the configuration here does not depend on system support.
     */
    override fun attachBaseContext(base: Context) {
        val locale = LocaleRegistry.preferred(base)
        super.attachBaseContext(LocaleContext.wrap(base, locale))
    }

    override fun onCreate() {
        super.onCreate()

        // Keeps AppCompat and the system settings picker in step. This is now a
        // secondary mechanism: if the framework ignores it, attachBaseContext has
        // already applied the language.
        LocaleRegistry.restore(this)

        // Read from the configuration rather than the stored preference, so the
        // digit shapes always match the text actually on screen.
        Format.setLocale(LocaleContext.effectiveLocale(this) ?: LocaleRegistry.DEFAULT)
    }
}
