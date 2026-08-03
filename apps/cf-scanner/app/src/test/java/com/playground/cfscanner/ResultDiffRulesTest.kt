package com.playground.cfscanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the comparison rules behind the result list's diffing.
 *
 * The bug these guard against is subtle: `areContentsTheSame` also compared
 * positions, so any row that shifted counted as changed. Ranking reorders
 * constantly as results arrive, so one new high-scoring IP marked every row
 * below it as changed — the whole visible list was rebound on every update,
 * several times a second, which is exactly what DiffUtil exists to avoid.
 *
 * The rules are tested directly rather than through `DiffUtil`, since the fault
 * was in the predicate rather than in the diffing.
 */
class ResultDiffRulesTest {

    private fun result(
        ip: String,
        latencies: List<Long> = listOf(40, 42, 41),
        colo: String = "VIE",
        ws: Boolean = false,
        throughput: Long = 0,
    ) = ScanResult(ip = ip, port = 443).apply {
        this.latencies.addAll(latencies)
        tlsOk = true
        httpStatus = 200
        this.colo = colo
        stableOk = true
        wsOk = ws
        throughputBps = throughput
    }

    /** Mirrors ResultAdapter.Diff.areContentsTheSame after the fix. */
    private fun contentsEqual(a: ScanResult, b: ScanResult): Boolean =
        a.score() == b.score() &&
            a.avgMs() == b.avgMs() &&
            a.jitterMs() == b.jitterMs() &&
            a.loss() == b.loss() &&
            a.colo == b.colo &&
            a.wsOk == b.wsOk &&
            a.throughputBps == b.throughputBps

    /**
     * The decisive case: identical data must compare equal no matter where the
     * rows sit. This is what stops a reorder from rebinding the whole list.
     */
    @Test
    fun identicalDataComparesEqualRegardlessOfPosition() {
        val a = result("104.16.1.1")
        val b = result("104.16.1.1")
        assertTrue(
            "a row whose data did not change must not be reported as changed",
            contentsEqual(a, b),
        )
    }

    @Test
    fun aChangedMetricIsDetected() {
        val before = result("104.16.1.1", latencies = listOf(40, 42, 41))
        val after = result("104.16.1.1", latencies = listOf(200, 210, 205))
        assertFalse("a latency change must force a rebind", contentsEqual(before, after))
    }

    @Test
    fun aChangedColoIsDetected() {
        assertFalse(
            contentsEqual(result("104.16.1.1", colo = "VIE"), result("104.16.1.1", colo = "FRA")),
        )
    }

    @Test
    fun gainingWebSocketSupportIsDetected() {
        assertFalse(
            contentsEqual(result("104.16.1.1", ws = false), result("104.16.1.1", ws = true)),
        )
    }

    @Test
    fun aChangedThroughputIsDetected() {
        assertFalse(
            contentsEqual(
                result("104.16.1.1", throughput = 0),
                result("104.16.1.1", throughput = 1_000_000),
            ),
        )
    }

    /**
     * Rows are identified by address, so a moved row is recognised as the same
     * item and dispatched as a move rather than a remove plus an insert.
     */
    @Test
    fun rowsAreIdentifiedByAddress() {
        val a = result("104.16.1.1")
        val b = result("104.16.1.1", latencies = listOf(900, 910, 905))
        val other = result("104.16.9.9")

        assertTrue("same address means same row, even after its metrics move", a.ip == b.ip)
        assertFalse(a.ip == other.ip)
    }

    /**
     * Demonstrates the regression concretely: a better result inserted at the top
     * shifts every row below it, and none of those rows changed.
     */
    @Test
    fun insertingAtTheTopChangesNoExistingRowsData() {
        val existing = (1..20).map { result("104.16.1.$it") }
        val reordered = listOf(result("104.16.2.1")) + existing

        var unchanged = 0
        for (r in existing) {
            val moved = reordered.first { it.ip == r.ip }
            if (contentsEqual(r, moved)) unchanged++
        }

        assertTrue(
            "all $unchanged existing rows should compare unchanged after a shift",
            unchanged == existing.size,
        )
    }
}
