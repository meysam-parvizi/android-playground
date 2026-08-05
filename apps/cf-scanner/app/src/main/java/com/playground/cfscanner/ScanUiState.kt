package com.playground.cfscanner

/**
 * What the screen is doing right now.
 *
 * Kept as a small state machine rather than a scatter of booleans so the empty
 * state cannot contradict the list, which is what produced a screen reading
 * "no healthy IP found" above several healthy results.
 */
enum class ScanPhase {
    /** Nothing has been run yet in this session. */
    IDLE,

    /** A scan is in flight. */
    SCANNING,

    /**
     * Discovery finished; the shortlist is being benchmarked for speed.
     *
     * Its own phase because probed == total throughout, so a shared SCANNING
     * phase would leave the UI pinned at 100% for tens of seconds with no
     * explanation for the wait.
     */
    MEASURING,

    /** A scan ran to completion. */
    FINISHED,

    /** The user stopped the scan early. */
    STOPPED,

    /** The scan failed, e.g. no connectivity. */
    ERROR,
}

/** Which placeholder to show when the result list is empty. */
data class EmptyContent(val titleRes: Int, val hintRes: Int)

/**
 * Derives the placeholder shown in place of results.
 *
 * Pure and dependency-free so the rules are unit-testable; [EmptyStateRules] is
 * the single source of truth for them.
 */
object EmptyStateRules {

    /**
     * Returns the placeholder to display, or `null` when the list should be shown
     * instead.
     *
     * The important rule: while a scan is still running we never suggest trying
     * again, because a result may still arrive. "Try again" is only correct once
     * the scan has actually finished empty.
     */
    fun contentFor(phase: ScanPhase, resultCount: Int): EmptyContent? {
        if (resultCount > 0) return null
        return when (phase) {
            ScanPhase.SCANNING -> EmptyContent(
                R.string.empty_searching,
                R.string.empty_searching_hint,
            )
            ScanPhase.MEASURING -> EmptyContent(
                R.string.empty_measuring,
                R.string.empty_measuring_hint,
            )
            ScanPhase.FINISHED -> EmptyContent(
                R.string.empty_none_found,
                R.string.empty_none_found_hint,
            )
            ScanPhase.STOPPED -> EmptyContent(
                R.string.empty_stopped,
                R.string.empty_stopped_hint,
            )
            ScanPhase.ERROR -> EmptyContent(
                R.string.empty_error,
                R.string.empty_error_hint,
            )
            ScanPhase.IDLE -> EmptyContent(
                R.string.empty_title,
                R.string.empty_hint,
            )
        }
    }
}

/** Everything the header needs in order to render. */
data class HeaderState(
    val phase: ScanPhase = ScanPhase.IDLE,
    val probed: Int = 0,
    val total: Int = 0,
    val healthy: Int = 0,
    val resultCount: Int = 0,
    /**
     * Index into the scan-size options.
     *
     * Defaults to the first, smallest option: a first scan should finish quickly
     * enough to show the app works, and a smaller run is usually enough to find
     * usable addresses. Larger counts are one tap away.
     */
    val countIndex: Int = 0,
    val sortIndex: Int = 0,
    /**
     * Whether the speed benchmark runs after the scan.
     *
     * Off by default: it downloads real payload per shortlisted IP, so it must
     * be a deliberate choice rather than a surprise on the data bill.
     */
    val speedTestEnabled: Boolean = false,
    /** Index into [SpeedTopNOptions.VALUES]. */
    val speedIndex: Int = SpeedTopNOptions.DEFAULT_INDEX,
    /** Index into [SpeedSizeOptions.VALUES]. */
    val speedSizeIndex: Int = SpeedSizeOptions.DEFAULT_INDEX,
    val iranMode: Boolean = true,
    /**
     * How many addresses the user asked for, as opposed to how many ended up
     * being tested.
     *
     * Kept separate from [total] so the UI can explain the difference. Neighbour
     * expansion tests extra addresses beside each healthy hit, so a scan of 100
     * can legitimately report 125 tested — which looks like a bug unless it is
     * spelled out.
     */
    val requested: Int = 0,
) {
    val isScanning: Boolean
        get() = phase == ScanPhase.SCANNING || phase == ScanPhase.MEASURING

    /** Progress as a percentage, clamped for display. */
    val progressPercent: Int
        get() = if (total > 0) ((probed * 100) / total).coerceIn(0, 100) else 0

    /**
     * Addresses that were tested and failed.
     *
     * Shown explicitly because "125 tested · 61 healthy" left the user to work
     * out that the other 64 had failed.
     */
    val unhealthy: Int get() = (probed - healthy).coerceAtLeast(0)

    /** Extra addresses tested beyond the requested count, via neighbour expansion. */
    val expandedBy: Int get() = (probed - requested).coerceAtLeast(0)

    /** True when expansion added enough to be worth explaining. */
    val wasExpanded: Boolean get() = requested > 0 && expandedBy > 0
}
