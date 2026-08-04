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

    /**
     * Regression: the very first moment of a scan must not claim failure.
     *
     * The 0.0.5 bug was an ordering hazard — the placeholder was chosen from
     * `scanJob?.isActive`, and the job had not been assigned yet, so a scan that
     * had just started looked finished and showed "no healthy IP found" while the
     * status line read "checking 1 of 300".
     *
     * Deriving the placeholder from an explicit phase removes the hazard: the
     * instant the phase is SCANNING, the copy is correct regardless of what any
     * coroutine has or has not been created yet.
     */
    @Test
    fun theFirstProbeOfAScanDoesNotReportFailure() {
        // probed == 1, total == 300, nothing found yet: mid-scan, not a failure.
        val midScan = HeaderState(
            phase = ScanPhase.SCANNING,
            probed = 1,
            total = 300,
            healthy = 0,
            resultCount = 0,
        )
        val content = EmptyStateRules.contentFor(midScan.phase, midScan.resultCount)

        assertEquals(R.string.empty_searching, content?.titleRes)
        assertNotEquals(
            "a scan on its first probe must not be reported as having found nothing",
            R.string.empty_none_found, content?.titleRes,
        )
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
    fun onlyUnfinishedWorkCountsAsScanning() {
        // isScanning means "work in flight", which drives the Stop button and the
        // locked inputs. Discovery and the speed benchmark are both in flight;
        // every settled phase is not.
        val busy = setOf(ScanPhase.SCANNING, ScanPhase.MEASURING)
        for (phase in busy) {
            assertEquals("$phase must report scanning", true, HeaderState(phase = phase).isScanning)
        }
        for (phase in ScanPhase.entries.filterNot { it in busy }) {
            assertEquals("$phase must not report scanning", false, HeaderState(phase = phase).isScanning)
        }
    }

    /**
     * The scan size defaults to the smallest option.
     *
     * A first run should finish quickly enough to show the app works; a larger
     * count is one tap away. Pinned here so the default cannot drift back.
     */
    @Test
    fun scanSizeDefaultsToTheFirstOption() {
        assertEquals("default should be the first (smallest) option", 0, HeaderState().countIndex)
    }

    /**
     * The reported numbers must add up, in both directions.
     *
     * A user asked for 100 addresses and the summary read "125 tested, 61
     * healthy", leaving two unexplained gaps: what happened to the other 64, and
     * why 125 rather than 100. Both are now derived and displayed.
     */
    @Test
    fun testedSplitsIntoHealthyAndUnhealthy() {
        val s = HeaderState(requested = 100, probed = 125, healthy = 61)
        assertEquals(64, s.unhealthy)
        assertEquals("healthy + unhealthy must equal tested", s.probed, s.healthy + s.unhealthy)
    }

    @Test
    fun requestedPlusExpansionEqualsTested() {
        val s = HeaderState(requested = 100, probed = 125, healthy = 61)
        assertEquals(25, s.expandedBy)
        assertEquals(true, s.wasExpanded)
        assertEquals("requested + expansion must equal tested", s.probed, s.requested + s.expandedBy)
    }

    @Test
    fun noExpansionIsNotReportedAsExpansion() {
        // A scan that tested exactly what was asked has nothing to explain.
        val exact = HeaderState(requested = 100, probed = 100, healthy = 0)
        assertEquals(0, exact.expandedBy)
        assertEquals(false, exact.wasExpanded)

        // Nor does a scan that was stopped early.
        val short = HeaderState(requested = 100, probed = 40, healthy = 5)
        assertEquals(0, short.expandedBy)
        assertEquals(false, short.wasExpanded)
    }

    @Test
    fun derivedCountsNeverGoNegative() {
        // Defensive: healthy can briefly lead probed if callbacks interleave.
        val odd = HeaderState(requested = 100, probed = 5, healthy = 9)
        assertEquals(0, odd.unhealthy)
        assertEquals(0, HeaderState(requested = 0, probed = 0, healthy = 0).expandedBy)
    }

    @Test
    fun expansionIsNotClaimedWithoutARequest() {
        // requested == 0 means the scan never started, so there is no baseline to
        // compare against and no expansion to report.
        val s = HeaderState(requested = 0, probed = 30, healthy = 3)
        assertEquals(false, s.wasExpanded)
    }
}
