package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A speed reading is trusted in proportion to how much of it escaped slow start.
 *
 * TCP starts every connection with a small congestion window and doubles it each
 * round trip, so a short transfer never reaches the link's real rate. On one
 * fixed 50 Mb/s link the same connection measures roughly:
 *
 *     128 KB -> 9 Mb/s      512 KB -> 19 Mb/s      2 MB -> 32 Mb/s
 *
 * Feeding those into one scoring formula makes the score depend on a *setting*
 * rather than on the IP, so a 128 KB result and a 2 MB result cannot be
 * compared. The fix is not a better formula for converting bytes to a rate —
 * it is to weight the measurement's influence by its own reliability.
 */
class SpeedConfidenceTest {

    private fun benchmarked(bytes: Long, bps: Long) =
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
                        edge = EdgeTiming(rttUs = 30_000),
                    ),
                )
            }
            wsOk = true
            recordBenchmark(bytes = bytes, bps = bps)
        }

    private fun unmeasured() = benchmarked(0, 0).apply {
        benchmarked = false
        throughputBps = 0
        downloadedBytes = 0
    }

    // --- the reported bug ---------------------------------------------------

    @Test
    fun theSmallestDownloadCarriesNoWeight() {
        // 128KB barely leaves slow start, so its figure cannot be trusted to
        // move a score at all.
        assertEquals(0.0, ScanResult.speedConfidence(128 * 1024), 0.001)
    }

    @Test
    fun confidenceRisesWithDownloadSize() {
        val sizes = listOf(128, 256, 512, 1024, 2048).map { it * 1024L }
        val confidences = sizes.map { ScanResult.speedConfidence(it) }

        assertEquals(
            "confidence must be non-decreasing in size",
            confidences.sorted(),
            confidences,
        )
        assertEquals("the largest option is fully trusted", 1.0, confidences.last(), 0.001)
    }

    @Test
    fun aSlowIpIsPenalisedMoreWhenMeasuredWithMoreData() {
        // Same measured rate, different amounts of evidence behind it.
        val slowBps = 100_000L // ~0.8 Mb/s
        val small = benchmarked(128 * 1024, slowBps).score()
        val medium = benchmarked(512 * 1024, slowBps).score()
        val large = benchmarked(2048 * 1024, slowBps).score()

        assertTrue("more evidence must mean a firmer penalty", large < medium)
        assertTrue(large < small)
        assertEquals(
            "a 128KB reading must not change the score at all",
            unmeasured().score(),
            small,
        )
    }

    @Test
    fun aFastIpIsNeverPenalisedRegardlessOfSize() {
        // Above the "fast enough" threshold there is nothing to penalise, so
        // confidence is irrelevant.
        val reference = unmeasured().score()
        for (kb in listOf(128, 512, 2048)) {
            assertEquals(reference, benchmarked(kb * 1024L, 4_000_000).score())
        }
    }

    @Test
    fun sizesBelowTheFloorAreNeverTrusted() {
        // The 8KB health gate must remain incapable of influencing a score even
        // if it were ever mistaken for a benchmark.
        assertEquals(0.0, ScanResult.speedConfidence(8 * 1024), 0.001)
        assertEquals(0.0, ScanResult.speedConfidence(0), 0.001)
    }

    // --- shortlist removed --------------------------------------------------

    @Test
    fun theBenchmarkRunsOnEveryHealthyResultOrNone() {
        // A partial shortlist made scores incomparable: measured IPs could be
        // demoted while unmeasured ones kept a neutral score, so the ranking
        // mixed two different scales.
        val on = ScanConfig.forMode(count = 100, iranMode = true, speedTestEnabled = true)
        val off = ScanConfig.forMode(count = 100, iranMode = true, speedTestEnabled = false)

        assertTrue(on.speedTestEnabled)
        assertFalse(off.speedTestEnabled)
    }

    @Test
    fun everyHealthyResultIsShortlistedWhenEnabled() {
        val results = List(40) { i ->
            benchmarked(512 * 1024, 1_000_000).also { it.benchmarked = false }
        }
        val healthy = results.count { it.isHealthy() }
        assertEquals(healthy, SpeedPhase.shortlist(results).size)
    }

    @Test
    fun unhealthyResultsAreStillExcluded() {
        val broken = ScanResult(ip = "9.9.9.9", port = 443)
        val good = benchmarked(512 * 1024, 1_000_000).also { it.benchmarked = false }

        assertEquals(listOf(good.ip), SpeedPhase.shortlist(listOf(broken, good)).map { it.ip })
    }
}
