package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Benchmark stage configuration and its user-facing option lists. */
class SpeedConfigTest {

    private fun enabled(topN: Int = SpeedTopNOptions.VALUES[SpeedTopNOptions.DEFAULT_INDEX]) =
        ScanConfig.forMode(count = 100, iranMode = true, speedTestEnabled = true, speedTopN = topN)

    // --- off by default -----------------------------------------------------

    @Test
    fun theBenchmarkIsOffUnlessAskedFor() {
        // It spends real mobile data, so it cannot be a default.
        val default = ScanConfig.forMode(count = 100, iranMode = true)
        assertFalse(default.speedTestEnabled)
        assertEquals("a shortlist of 0 disables the phase downstream", 0, default.speedTopN)
    }

    @Test
    fun turningItOffZeroesTheShortlistEvenIfATopNIsPassed() {
        // The off switch must win: one place decides, so no downstream code has
        // to check the flag as well.
        val off = ScanConfig.forMode(
            count = 100,
            iranMode = true,
            speedTestEnabled = false,
            speedTopN = 50,
        )
        assertEquals(0, off.speedTopN)
    }

    // --- when enabled -------------------------------------------------------

    @Test
    fun enablingItUsesTenResultsAndHalfAMegabyteByDefault() {
        val config = enabled()
        assertTrue(config.speedTestEnabled)
        assertEquals(10, config.speedTopN)
        assertEquals(512 * 1024, config.speedTestBytes)
    }

    @Test
    fun benchmarkConcurrencyStaysLowEnoughToAvoidSelfContention() {
        val config = enabled()
        assertTrue(
            "benchmarking many IPs at once measures contention, not the edges",
            config.speedConcurrency in 1..3,
        )
        assertTrue(config.speedConcurrency < config.concurrency)
    }

    @Test
    fun exactlyOneControlledRetryIsAllowed() {
        assertEquals(1, enabled().speedRetries)
    }

    // --- shortlist options --------------------------------------------------

    @Test
    fun allIsOfferedLastBecauseItIsTheMostExpensive() {
        assertEquals(SpeedTopNOptions.ALL, SpeedTopNOptions.VALUES.last())
    }

    @Test
    fun theShortlistOptionsAreAscendingAndDistinct() {
        assertEquals(SpeedTopNOptions.VALUES.sorted(), SpeedTopNOptions.VALUES)
        assertEquals(SpeedTopNOptions.VALUES.distinct(), SpeedTopNOptions.VALUES)
    }

    @Test
    fun offIsTheSwitchNotAListEntry() {
        // 0 used to sit in this list, which meant two ways to express "off".
        assertFalse(SpeedTopNOptions.VALUES.contains(0))
    }

    @Test
    fun theDefaultShortlistOptionIsTen() {
        assertEquals(10, SpeedTopNOptions.VALUES[SpeedTopNOptions.DEFAULT_INDEX])
    }

    @Test
    fun allMeansEveryHealthyResult() {
        val results = List(200) { ScanResult(ip = "104.16.0.$it", port = 443) }
        // ALL must not truncate; take(Int.MAX_VALUE) is safe.
        assertEquals(0, SpeedPhase.shortlist(results, SpeedTopNOptions.ALL).size)
    }

    // --- download size options ---------------------------------------------

    @Test
    fun everySizeOptionOutgrowsSlowStartEnoughToMeanSomething() {
        // Below ~128KB the transfer finishes inside the first congestion windows,
        // so the figure describes round-trip time rather than bandwidth. That is
        // exactly what made an 8KB gate read as 0.5 Mb/s.
        assertTrue(SpeedSizeOptions.VALUES.min() >= 128 * 1024)
    }

    @Test
    fun theSizeOptionsAreAscendingAndDistinct() {
        assertEquals(SpeedSizeOptions.VALUES.sorted(), SpeedSizeOptions.VALUES)
        assertEquals(SpeedSizeOptions.VALUES.distinct(), SpeedSizeOptions.VALUES)
    }

    @Test
    fun theDefaultSizeIsHalfAMegabyte() {
        assertEquals(512 * 1024, SpeedSizeOptions.VALUES[SpeedSizeOptions.DEFAULT_INDEX])
    }

    @Test
    fun theLargestSizeStaysWithinReason() {
        // 2MB per IP times a large shortlist is already a big bill; anything
        // more should be a deliberate code change, not a dropdown entry.
        assertTrue(SpeedSizeOptions.VALUES.max() <= 2 * 1024 * 1024)
    }
}
