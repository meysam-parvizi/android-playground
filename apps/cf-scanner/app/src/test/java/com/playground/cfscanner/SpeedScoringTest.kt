package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Speed must never move a score unless it was actually measured.
 *
 * The reported bug: an IP graded "weak 48" during the scan became "good 85"
 * after the speed phase, with no quality metric having changed. The 8KB
 * discovery transfer completes inside the first congestion window, so its
 * figure reflects one round trip rather than throughput — and that meaningless
 * number was multiplied into the score.
 */
class SpeedScoringTest {

    private fun healthy(rttUs: Long = 30_000) =
        ScanResult(ip = "104.16.1.1", port = 443).apply {
            repeat(3) {
                recordAttempt(
                    AttemptResult(
                        sni = "speed.cloudflare.com",
                        tcpConnectMs = 30,
                        tlsRequired = true,
                        tlsOk = true,
                        httpStatus = 200,
                        colo = "VIE",
                        stabilityOk = true,
                        edge = EdgeTiming(rttUs = rttUs),
                    ),
                )
            }
            wsOk = true
        }

    // --- the reported bug ---------------------------------------------------

    @Test
    fun theDiscoveryGateNeverAffectsTheScore() {
        // 8KB moved, which only proves the path carries data.
        val gated = healthy().apply {
            dataPathVerified = true
            downloadedBytes = 8 * 1024
            throughputBps = 62_500 // ~0.5 Mb/s, the figure from the screenshots
        }

        assertEquals(
            "a health-gate transfer must not change the score",
            healthy().score(),
            gated.score(),
        )
    }

    @Test
    fun aBenchmarkedResultDoesAffectTheScore() {
        val slow = healthy().apply { recordBenchmark(bytes = 512 * 1024, bps = 62_500) }
        val fast = healthy().apply { recordBenchmark(bytes = 512 * 1024, bps = 2_000_000) }

        assertTrue("a measured-slow IP must rank below a measured-fast one", slow.score() < fast.score())
    }

    @Test
    fun benchmarkedFastMatchesUnbenchmarked() {
        // "Fast enough" is the neutral case: no bonus, no penalty. So an IP that
        // was never benchmarked cannot be beaten purely for lack of measurement.
        val fast = healthy().apply { recordBenchmark(bytes = 512 * 1024, bps = 4_000_000) }
        assertEquals(healthy().score(), fast.score())
    }

    // --- speed test disabled ------------------------------------------------

    @Test
    fun withSpeedTestOffTheScoreIgnoresSpeedEntirely() {
        val config = ScanConfig.forMode(count = 100, iranMode = true, speedTestEnabled = false)
        assertFalse(config.speedTestEnabled)
    }

    @Test
    fun speedTestIsOffByDefault() {
        // It costs real mobile data, so it must be opt-in.
        assertFalse(ScanConfig.forMode(count = 100, iranMode = true).speedTestEnabled)
        assertFalse(HeaderState().speedTestEnabled)
    }

    // --- display ------------------------------------------------------------

    @Test
    fun onlyABenchmarkedResultReportsASpeed() {
        assertFalse("gate-only results must show no speed", healthy().apply {
            dataPathVerified = true
            downloadedBytes = 8 * 1024
            throughputBps = 62_500
        }.hasMeasuredSpeed)

        assertTrue(healthy().apply {
            recordBenchmark(bytes = 512 * 1024, bps = 1_000_000)
        }.hasMeasuredSpeed)
    }

    @Test
    fun aFailedBenchmarkIsNotReportedAsASpeed() {
        val failed = healthy().apply { recordBenchmark(bytes = 0, bps = 0) }
        assertFalse(failed.hasMeasuredSpeed)
    }

    // --- health gate is unchanged ------------------------------------------

    @Test
    fun theHealthGateStillRejectsAStalledDataPath() {
        val stalled = healthy().apply {
            dataPathVerified = true
            downloadedBytes = 0
            throughputBps = 0
        }
        assertFalse("an IP that moved no bytes is not healthy", stalled.isHealthy())
    }

    @Test
    fun aBenchmarkFailureDoesNotMakeAHealthyIpUnhealthy() {
        // The benchmark is a measurement, not a health check: one failed 512KB
        // transfer on a shortlisted IP must not delete a result that already
        // passed every health test.
        val r = healthy().apply {
            dataPathVerified = true
            downloadedBytes = 8 * 1024
            throughputBps = 62_500
            recordBenchmark(bytes = 0, bps = 0)
        }
        assertTrue(r.isHealthy())
    }
}
