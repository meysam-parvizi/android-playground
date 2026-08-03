package com.playground.cfscanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the rule that decides when a scan's measurements are suspect.
 *
 * The scan deliberately keeps running when the app is backgrounded: it takes
 * minutes, and cancelling it whenever the user glances at a notification would
 * defeat the purpose. The cost is that Android may throttle network access for a
 * background process, and a probe deferred that way is recorded as packet loss
 * exactly like a genuinely blocked address — so a good IP can be ranked low, or
 * dropped, for reasons that have nothing to do with the network.
 *
 * The rule is tested here rather than through the ViewModel because the decision
 * is what matters, and it must not fire for a configuration change, where the
 * process never actually leaves the foreground.
 */
class InterruptionRuleTest {

    /**
     * Mirrors the interaction between MainActivity.onStop and
     * ScanViewModel.onEnteredBackground.
     */
    private class Tracker {
        var scanning = false
        var interrupted = false
            private set

        fun startScan() {
            scanning = true
            // Cleared per scan: an interruption during an earlier run says
            // nothing about this one.
            interrupted = false
        }

        fun finishScan() {
            scanning = false
        }

        /** @param changingConfiguration true for a rotation or language switch. */
        fun onStop(changingConfiguration: Boolean) {
            if (changingConfiguration) return
            if (scanning) interrupted = true
        }
    }

    @Test
    fun backgroundingDuringAScanMarksItInterrupted() {
        val t = Tracker()
        t.startScan()
        t.onStop(changingConfiguration = false)
        t.finishScan()

        assertTrue(
            "a scan that ran in the background must be flagged as unreliable",
            t.interrupted,
        )
    }

    @Test
    fun backgroundingWhileIdleChangesNothing() {
        val t = Tracker()
        t.onStop(changingConfiguration = false)

        assertFalse("no scan was running, so nothing was interrupted", t.interrupted)
    }

    /**
     * The important exclusion: a rotation or language switch stops the activity
     * but the process stays in the foreground, so no throttling applies. Warning
     * there would report an interruption that never happened — and the language
     * switch is a first-party path that calls recreate() deliberately.
     */
    @Test
    fun aConfigurationChangeIsNotAnInterruption() {
        val t = Tracker()
        t.startScan()
        t.onStop(changingConfiguration = true)

        assertFalse(
            "a rotation keeps the process in the foreground; measurements stay valid",
            t.interrupted,
        )
    }

    @Test
    fun aNewScanClearsAPreviousInterruption() {
        val t = Tracker()
        t.startScan()
        t.onStop(changingConfiguration = false)
        t.finishScan()
        assertTrue(t.interrupted)

        t.startScan()
        assertFalse(
            "the previous run's interruption must not taint this one",
            t.interrupted,
        )
    }

    @Test
    fun backgroundingAfterAScanFinishesDoesNotFlagIt() {
        val t = Tracker()
        t.startScan()
        t.finishScan()
        t.onStop(changingConfiguration = false)

        assertFalse(
            "the measurements were already complete before the app was backgrounded",
            t.interrupted,
        )
    }

    @Test
    fun repeatedBackgroundingStaysFlaggedOnce() {
        val t = Tracker()
        t.startScan()
        t.onStop(changingConfiguration = false)
        t.onStop(changingConfiguration = false)

        assertTrue(t.interrupted)
    }
}
