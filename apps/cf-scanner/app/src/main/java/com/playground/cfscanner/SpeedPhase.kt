package com.playground.cfscanner

/**
 * Second scan stage: measure real throughput on a small shortlist.
 *
 * Discovery answers "does this IP work at all?" and must stay cheap, because it
 * runs against every candidate at full concurrency. Speed is a different
 * question and a hostile one to answer during discovery: sixteen simultaneous
 * downloads share one phone's bandwidth, so every figure comes out low and the
 * ranking reflects contention rather than the edges.
 *
 * So throughput is measured afterwards, on the best few results only, a few at
 * a time.
 */
object SpeedPhase {

    /**
     * The best [limit] healthy results, ordered as they will be benchmarked.
     *
     * Ordered by discovery score so the benchmark budget is spent on the
     * candidates most likely to be worth keeping.
     */
    fun shortlist(results: List<ScanResult>, limit: Int): List<ScanResult> {
        if (limit <= 0) return emptyList()
        return results
            .filter { it.isHealthy() }
            .sortedByDescending { it.score() }
            .take(limit)
    }
}
