package com.playground.cfscanner

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the manifest wiring that makes the per-app language actually apply.
 *
 * The app shipped twice looking correct but behaving wrongly: the picker reported
 * Persian while the interface stayed English and left-to-right. The cause was not
 * the strings — it was that `AppCompatDelegate.setApplicationLocales` never took
 * effect, because on Android 12 and below AppCompat only loads and applies a
 * stored locale when `AppLocalesMetadataHolderService` is declared in the
 * manifest. Without it the call is silently dropped.
 *
 * Nothing about that is visible in the Kotlin, which is why it survived two
 * rounds of fixes. These tests make its absence a build failure.
 *
 * The manifest is parsed as text so this stays a plain JVM test.
 */
class LocaleWiringTest {

    private val manifest: String by lazy {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("missing ${file.path}", file.exists())
        file.readText()
    }

    /**
     * Without this service the whole language feature is inert below API 33.
     */
    @Test
    fun appLocalesServiceIsDeclared() {
        assertTrue(
            "AndroidManifest.xml does not declare " +
                "androidx.appcompat.app.AppLocalesMetadataHolderService. Without it " +
                "AppCompat cannot restore the chosen language on Android 12 and " +
                "below, and setApplicationLocales is silently dropped.",
            manifest.contains("androidx.appcompat.app.AppLocalesMetadataHolderService"),
        )
    }

    /**
     * The service is inert without its metadata flag, so declaring one without the
     * other looks correct and still fails.
     */
    @Test
    fun autoStoreLocalesMetadataIsEnabled() {
        val hasName = Regex("""android:name="autoStoreLocales"""").containsMatchIn(manifest)
        assertTrue(
            "the autoStoreLocales meta-data tag is missing; the service does nothing " +
                "without it",
            hasName,
        )
        assertTrue(
            "autoStoreLocales must be \"true\"; any other value tells AppCompat the " +
                "app stores locales itself, which reintroduces the dropped-locale bug",
            Regex("""android:name="autoStoreLocales"\s*\n?\s*android:value="true"""")
                .containsMatchIn(manifest),
        )
    }

    /**
     * The Application subclass is where the default is established on first run.
     */
    @Test
    fun theApplicationClassIsRegistered() {
        assertTrue(
            "AndroidManifest.xml does not set android:name=\".CfScannerApp\", so the " +
                "default language is never applied on first launch",
            manifest.contains("android:name=\".CfScannerApp\""),
        )
    }

    /**
     * `supportsRtl` must stay on, or Persian would not mirror the layout even once
     * the language resolves correctly.
     */
    @Test
    fun rtlSupportIsEnabled() {
        assertTrue(
            "android:supportsRtl must be true for the Persian layout to mirror",
            manifest.contains("android:supportsRtl=\"true\""),
        )
    }

    /**
     * The default language must be applied on first launch rather than left to the
     * device.
     *
     * This is the specific defect from the screenshot: with nothing stored,
     * AppCompat follows the device locale, so the picker showed Persian (the app's
     * declared default) while the resources resolved to English (the device's).
     */
    @Test
    fun theDefaultLanguageIsAppliedWhenNothingIsStored() {
        val source = File(
            "src/main/java/com/playground/cfscanner/LocaleRegistry.kt",
        )
        assertTrue("missing ${source.path}", source.exists())
        val text = source.readText()

        assertTrue(
            "LocaleRegistry.restore() must apply a locale when AppCompat has none, " +
                "otherwise first launch follows the device language while the picker " +
                "reports the app default",
            text.contains("getApplicationLocales()"),
        )
        assertTrue(
            "restore() should return early once a locale is already set, so it does " +
                "not fight AppCompat's own restored value",
            Regex("""if\s*\(!AppCompatDelegate\.getApplicationLocales\(\)\.isEmpty\)\s*return""")
                .containsMatchIn(text),
        )
    }
}
