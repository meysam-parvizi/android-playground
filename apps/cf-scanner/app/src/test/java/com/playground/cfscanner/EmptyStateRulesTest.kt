package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the placeholder rules.
 *
 * These encode the reported bug directly: the screen must never claim that no
 * healthy IP was found while results are listed, and it must never suggest
 * retrying while a scan is still capable of producing a result.
 */
class EmptyStateRulesTest {

    @Test
    fun noPlaceholderWhenResultsExist() {
        // The decisive case: with results on screen there is nothing to explain,
        // in any phase.
        for (phase in ScanPhase.entries) {
            assertNull(
                "phase $phase must not show a placeholder alongside results",
                EmptyStateRules.contentFor(phase, resultCount = 1),
            )
        }
    }

    @Test
    fun scanningWithNoResultsSaysSearchingNotRetry() {
        val content = EmptyStateRules.contentFor(ScanPhase.SCANNING, resultCount = 0)
        assertEquals(R.string.empty_searching, content?.titleRes)
        assertEquals(R.string.empty_searching_hint, content?.hintRes)

        // Explicitly not the "try again" copy, which would be misleading while
        // the scan can still find something.
        assertNotEquals(R.string.empty_none_found, content?.titleRes)
        assertNotEquals(R.string.empty_none_found_hint, content?.hintRes)
    }

    @Test
    fun retryIsOnlySuggestedAfterAScanFinishesEmpty() {
        val content = EmptyStateRules.contentFor(ScanPhase.FINISHED, resultCount = 0)
        assertEquals(R.string.empty_none_found, content?.titleRes)
        assertEquals(R.string.empty_none_found_hint, content?.hintRes)
    }

    @Test
    fun idleShowsTheNeutralPrompt() {
        val content = EmptyStateRules.contentFor(ScanPhase.IDLE, resultCount = 0)
        assertEquals(R.string.empty_title, content?.titleRes)
        assertEquals(R.string.empty_hint, content?.hintRes)
    }

    @Test
    fun stoppedAndErrorHaveTheirOwnWording() {
        val stopped = EmptyStateRules.contentFor(ScanPhase.STOPPED, resultCount = 0)
        assertEquals(R.string.empty_stopped, stopped?.titleRes)

        val error = EmptyStateRules.contentFor(ScanPhase.ERROR, resultCount = 0)
        assertEquals(R.string.empty_error, error?.titleRes)

        // Every phase should be distinguishable, so the user always knows why the
        // list is empty.
        val titles = ScanPhase.entries.map {
            EmptyStateRules.contentFor(it, resultCount = 0)?.titleRes
        }
        assertEquals("each phase needs its own message", titles.size, titles.distinct().size)
    }

    @Test
    fun everyPhaseHasAPlaceholderWhenEmpty() {
        for (phase in ScanPhase.entries) {
            val content = EmptyStateRules.contentFor(phase, resultCount = 0)
            assertEquals(
                "phase $phase must provide placeholder copy",
                true, content != null,
            )
        }
    }
}

/** Tests for the header state's derived values. */
class HeaderStateTest {

    @Test
    fun progressIsAPercentageOfTheTarget() {
        assertEquals(0, HeaderState(probed = 0, total = 300).progressPercent)
        assertEquals(50, HeaderState(probed = 150, total = 300).progressPercent)
        assertEquals(100, HeaderState(probed = 300, total = 300).progressPercent)
    }

    @Test
    fun progressIsClampedAndSafeWithoutATotal() {
        // Neighbour expansion can push probed past the original target.
        assertEquals(100, HeaderState(probed = 400, total = 300).progressPercent)
        // No division by zero before a scan starts.
        assertEquals(0, HeaderState(probed = 0, total = 0).progressPercent)
    }

    @Test
    fun onlyTheScanningPhaseCountsAsScanning() {
        assertEquals(true, HeaderState(phase = ScanPhase.SCANNING).isScanning)
        for (phase in ScanPhase.entries.filterNot { it == ScanPhase.SCANNING }) {
            assertEquals("$phase must not report scanning", false, HeaderState(phase = phase).isScanning)
        }
    }
}
