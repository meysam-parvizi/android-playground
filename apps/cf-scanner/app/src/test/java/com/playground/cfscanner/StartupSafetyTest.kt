package com.playground.cfscanner

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the code that runs before the first frame.
 *
 * `attachBaseContext` is the earliest hook in the process. An exception there
 * kills the app on launch with no screen on which to explain itself — Android
 * simply reports "this app has a bug", which is what happened when the locale
 * override shipped.
 *
 * The cause was `Context.applicationContext` returning null inside
 * `Application.attachBaseContext`: the Application is not registered yet at that
 * point, so dereferencing it threw before `onCreate`. Nothing about that is
 * visible from reading the call, since the same code is perfectly safe when
 * reached from an Activity.
 *
 * Verified by source inspection, so this stays a plain JVM test.
 */
class StartupSafetyTest {

    private fun source(name: String): String {
        val file = File("src/main/java/com/playground/cfscanner/$name")
        assertTrue("missing ${file.path}", file.exists())
        return file.readText()
    }

    private val registry by lazy { source("LocaleRegistry.kt") }
    private val localeContext by lazy { source("LocaleContext.kt") }
    private val application by lazy { source("CfScannerApp.kt") }

    /** Strips comments so a mention in prose is not mistaken for a call. */
    private fun codeOnly(text: String): String {
        val withoutBlocks = text.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        return withoutBlocks
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    /**
     * The body of a single function, ending at the next declaration.
     *
     * Necessary because a lazy `fun name([\s\S]*?catch` match runs straight past
     * the function and finds a `catch` belonging to a later one — which made an
     * earlier version of this test pass while the guard it checked was gone.
     */
    private fun bodyOf(code: String, name: String): String {
        val start = Regex("""fun $name\(""").find(code)?.range?.first ?: return ""
        val rest = code.substring(start + 4)
        val next = Regex("""\n {4}(?:fun |private fun |/\*\*)""").find(rest)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }

    private fun assertGuarded(code: String, function: String, why: String) {
        val body = bodyOf(code, function)
        assertTrue("could not locate $function in the source", body.isNotEmpty())
        assertTrue(why, body.contains("catch"))
    }

    /**
     * The specific crash: applicationContext is null during
     * Application.attachBaseContext.
     */
    @Test
    fun preferenceStorageDoesNotUseApplicationContext() {
        assertFalse(
            "LocaleRegistry must not touch applicationContext: it is null inside " +
                "Application.attachBaseContext, and dereferencing it throws before " +
                "onCreate, killing the app on launch",
            codeOnly(registry).contains("applicationContext"),
        )
    }

    /**
     * Nothing reached from `attachBaseContext` may be allowed to throw.
     */
    @Test
    fun theLocaleWrapperCannotThrow() {
        val code = codeOnly(localeContext)
        assertGuarded(
            code,
            "wrap",
            "LocaleContext.wrap runs in attachBaseContext and must swallow failures, " +
                "returning the unmodified context rather than crashing before the " +
                "first frame",
        )
        assertGuarded(
            code,
            "effectiveLocale",
            "effectiveLocale must not throw either; it is read during startup",
        )
    }

    @Test
    fun readingTheStoredLanguageCannotThrow() {
        assertGuarded(
            codeOnly(registry),
            "preferred",
            "LocaleRegistry.preferred runs in attachBaseContext and must fall back " +
                "to the default rather than propagate an exception",
        )
    }

    @Test
    fun restoringTheLanguageCannotThrow() {
        assertGuarded(
            codeOnly(registry),
            "restore",
            "LocaleRegistry.restore runs in Application.onCreate; a failure there " +
                "kills the app on launch and it is only a secondary mechanism",
        )
    }

    /**
     * The Application must pass the context it was handed straight through.
     *
     * Reaching for a different context during attach is what introduced the null.
     */
    @Test
    fun theApplicationUsesTheContextItWasGiven() {
        val code = codeOnly(application)
        val attach = Regex("""override fun attachBaseContext\(([\s\S]*?)\n    \}""")
            .find(code)
            ?.value
            .orEmpty()

        assertTrue("could not locate attachBaseContext in CfScannerApp", attach.isNotEmpty())
        assertFalse(
            "attachBaseContext must not use applicationContext, which is null at " +
                "that point in the Application lifecycle",
            attach.contains("applicationContext"),
        )
    }
}
