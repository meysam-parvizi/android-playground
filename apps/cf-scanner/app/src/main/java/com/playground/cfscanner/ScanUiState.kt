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
    val countIndex: Int = 2,
    val sortIndex: Int = 0,
    val iranMode: Boolean = true,
) {
    val isScanning: Boolean get() = phase == ScanPhase.SCANNING

    /** Progress as a percentage, clamped for display. */
    val progressPercent: Int
        get() = if (total > 0) ((probed * 100) / total).coerceIn(0, 100) else 0
}
