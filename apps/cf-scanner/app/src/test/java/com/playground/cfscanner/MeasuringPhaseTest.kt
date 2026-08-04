package com.playground.cfscanner

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speed phase runs after every candidate has been probed, so probed == total
 * throughout it. Without its own phase the UI would sit at 100% for tens of
 * seconds looking frozen, with Stop unavailable.
 */
class MeasuringPhaseTest {

    @Test
    fun measuringIsItsOwnPhase() {
        assertNotEquals(ScanPhase.SCANNING, ScanPhase.MEASURING)
        assertNotEquals(ScanPhase.FINISHED, ScanPhase.MEASURING)
    }

    @Test
    fun measuringStillCountsAsScanning() {
        // isScanning drives the Stop button, the locked inputs and cancellation,
        // all of which must keep working while the benchmark runs.
        assertTrue(HeaderState(phase = ScanPhase.MEASURING).isScanning)
        assertTrue(HeaderState(phase = ScanPhase.SCANNING).isScanning)
    }

    @Test
    fun settledPhasesAreNotScanning() {
        for (phase in listOf(
            ScanPhase.IDLE, ScanPhase.FINISHED, ScanPhase.STOPPED, ScanPhase.ERROR,
        )) {
            assertTrue("$phase must not read as scanning", !HeaderState(phase = phase).isScanning)
        }
    }

    @Test
    fun measuringDoesNotOfferTryAgainWhileStillWorking() {
        // Nothing found yet, but the benchmark is still running: suggesting
        // "try again" here would be wrong, exactly as during discovery.
        val content = EmptyStateRules.contentFor(ScanPhase.MEASURING, resultCount = 0)
        assertTrue(content != null)
        assertNotEquals(
            EmptyStateRules.contentFor(ScanPhase.FINISHED, resultCount = 0),
            content,
        )
    }
}
