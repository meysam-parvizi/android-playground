package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scan is only valid while the app is in the foreground.
 *
 * Every health check depends on a connection surviving a deliberate silence —
 * the idle hold is precisely a test of "does this link tolerate being quiet".
 * With no foreground service and no wake lock, Doze and App Standby suspend the
 * process's network and reset quiet sockets, so that test starts measuring
 * Android's power management instead of the edge. Probes still complete and the
 * counter still advances, which is why the failure looks like "scanning fine,
 * finding nothing".
 *
 * So the scan keeps the screen on while it runs, and stops rather than
 * producing results it cannot stand behind if the app is backgrounded anyway.
 */
class BackgroundScanTest {

    @Test
    fun interruptedIsItsOwnPhase() {
        // Distinct from STOPPED: the user pressed nothing, and the reason needs
        // explaining rather than silently showing an empty list.
        assertFalse(ScanPhase.INTERRUPTED == ScanPhase.STOPPED)
        assertFalse(ScanPhase.INTERRUPTED == ScanPhase.FINISHED)
    }

    @Test
    fun interruptedIsNotAScanInFlight() {
        assertFalse(HeaderState(phase = ScanPhase.INTERRUPTED).isScanning)
    }

    @Test
    fun interruptedExplainsItselfWhenNothingWasFound() {
        // "No IPs found" would blame the network for an interruption we caused.
        val content = EmptyStateRules.contentFor(ScanPhase.INTERRUPTED, resultCount = 0)
        assertTrue(content != null)
        assertEquals(
            "an interruption must not be reported as an empty result",
            R.string.empty_interrupted,
            content!!.titleRes,
        )
    }

    @Test
    fun resultsFoundBeforeTheInterruptionAreStillShown() {
        // They were measured in the foreground, so they are as valid as any
        // other. Only the unfinished remainder is lost.
        assertEquals(null, EmptyStateRules.contentFor(ScanPhase.INTERRUPTED, resultCount = 3))
    }

    @Test
    fun everyPhaseStillHasAStatusLabel() {
        // A phase without a label renders as a blank status line.
        for (phase in ScanPhase.entries) {
            assertTrue(
                "$phase has no status string",
                HeaderAdapter.statusTextResFor(phase) != 0,
            )
        }
    }

    @Test
    fun theScreenIsKeptOnOnlyWhileScanning() {
        // Holding the flag permanently would drain the battery of anyone who
        // leaves the app open, so it is tied to the scan's lifetime.
        assertTrue(HeaderState(phase = ScanPhase.SCANNING).shouldKeepScreenOn)

        for (phase in listOf(
            ScanPhase.IDLE,
            ScanPhase.FINISHED,
            ScanPhase.STOPPED,
            ScanPhase.ERROR,
            ScanPhase.INTERRUPTED,
        )) {
            assertFalse("$phase must release the screen", HeaderState(phase = phase).shouldKeepScreenOn)
        }
    }
}
