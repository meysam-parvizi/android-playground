package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two-stage scanning: a cheap discovery gate, then a bounded speed benchmark on
 * a shortlist.
 */
class SpeedPhaseTest {

    private fun healthy(ip: String, rttUs: Long, colo: String = "FRA") =
        ScanResult(ip = ip, port = 443).apply {
            repeat(3) {
                recordAttempt(
                    AttemptResult(
                        sni = "speed.cloudflare.com",
                        tcpConnectMs = 40,
                        tlsRequired = true,
                        tlsOk = true,
                        httpStatus = 200,
                        colo = colo,
                        stabilityOk = true,
                        edge = EdgeTiming(rttUs = rttUs),
                    ),
                )
            }
            wsOk = true
        }

    // --- discovery gate -----------------------------------------------------

    @Test
    fun discoveryUsesTheCheapGateNotTheBenchmark() {
        val restricted = ScanConfig.forMode(count = 100, iranMode = true)
        assertEquals(
            "discovery must not download a benchmark payload per candidate",
            ScanConfig.DISCOVERY_GATE_BYTES,
            restricted.downloadBytes,
        )
        assertTrue(restricted.downloadBytes <= 16 * 1024)
    }

    @Test
    fun standardModeStillSkipsTheDiscoveryTransfer() {
        assertEquals(0, ScanConfig.forMode(count = 100, iranMode = false).downloadBytes)
    }

    // --- shortlist ----------------------------------------------------------

    @Test
    fun shortlistTakesTheBestNHealthyResults()
    {
        val results = listOf(
            healthy("1.1.1.1", 200_000),
            healthy("1.1.1.2", 20_000),
            healthy("1.1.1.3", 90_000),
        )

        val shortlist = SpeedPhase.shortlist(results, limit = 2)

        assertEquals(listOf("1.1.1.2", "1.1.1.3"), shortlist.map { it.ip })
    }

    @Test
    fun shortlistNeverIncludesUnhealthyResults() {
        val broken = ScanResult(ip = "9.9.9.9", port = 443)
        assertFalse(broken.isHealthy())

        val shortlist = SpeedPhase.shortlist(listOf(broken, healthy("1.1.1.1", 30_000)), limit = 5)

        assertEquals(listOf("1.1.1.1"), shortlist.map { it.ip })
    }

    @Test
    fun shortlistOfZeroOrEmptyInputDoesNoWork() {
        assertTrue(SpeedPhase.shortlist(listOf(healthy("1.1.1.1", 30_000)), limit = 0).isEmpty())
        assertTrue(SpeedPhase.shortlist(emptyList(), limit = 10).isEmpty())
    }

    // --- speed-aware reranking ---------------------------------------------

    @Test
    fun measuredSpeedOutranksAMarginallyBetterPing() {
        val fastPingSlowData = healthy("1.1.1.1", 30_000).apply {
            recordBenchmark(bytes = 512 * 1024, bps = 40_000) // ~0.3 Mbps
        }
        val slightlySlowerPingFastData = healthy("1.1.1.2", 45_000).apply {
            recordBenchmark(bytes = 512 * 1024, bps = 4_000_000) // ~32 Mbps
        }

        assertTrue(
            "a 100x faster data path must outrank a 15ms ping edge",
            slightlySlowerPingFastData.score() > fastPingSlowData.score(),
        )
    }

    @Test
    fun anUnmeasuredIpIsNotPenalisedAgainstAMeasuredOne() {
        // Not benchmarked means "unknown", not "slow": an IP outside the
        // shortlist must not sink below a benchmarked one purely for that.
        val unmeasured = healthy("1.1.1.1", 30_000)
        val measured = healthy("1.1.1.2", 30_000).apply {
            recordBenchmark(bytes = 512 * 1024, bps = 4_000_000)
        }

        assertEquals(unmeasured.score(), measured.score())
    }
}
