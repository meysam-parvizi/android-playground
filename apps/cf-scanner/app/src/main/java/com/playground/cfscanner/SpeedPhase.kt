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
     * Every healthy result, ordered as they will be benchmarked.
     *
     * All of them or none: a partial shortlist produced two scoring scales in
     * one list, where a measured IP could be demoted while an unmeasured one
     * kept a neutral score. Ranking across those is meaningless, and the user
     * cannot tell which rows were measured. Ordering is still by discovery score
     * so the most promising addresses are measured first.
     */
    fun shortlist(results: List<ScanResult>): List<ScanResult> = results
        .filter { it.isHealthy() }
        .sortedByDescending { it.score() }
}
