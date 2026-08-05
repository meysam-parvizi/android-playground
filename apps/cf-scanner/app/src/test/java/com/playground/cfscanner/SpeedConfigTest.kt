package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Benchmark stage configuration and its download-size options. */
class SpeedConfigTest {

    private fun enabled(
        bytes: Int = SpeedSizeOptions.VALUES[SpeedSizeOptions.DEFAULT_INDEX],
    ) = ScanConfig.forMode(
        count = 100,
        iranMode = true,
        speedTestEnabled = true,
        speedTestBytes = bytes,
    )

    // --- off by default -----------------------------------------------------

    @Test
    fun theBenchmarkIsOffUnlessAskedFor() {
        // It spends real mobile data, so it cannot be a default.
        assertFalse(ScanConfig.forMode(count = 100, iranMode = true).speedTestEnabled)
    }

    @Test
    fun theSwitchIsTheOnlyThingThatTurnsItOn() {
        // There is no shortlist size any more: all healthy results or none, so
        // every score in a list is on the same scale.
        assertTrue(enabled().speedTestEnabled)
        assertFalse(
            ScanConfig.forMode(count = 100, iranMode = true, speedTestEnabled = false)
                .speedTestEnabled,
        )
    }

    // --- when enabled -------------------------------------------------------

    @Test
    fun theDefaultDownloadIsHalfAMegabyte() {
        assertEquals(512 * 1024, enabled().speedTestBytes)
    }

    @Test
    fun theChosenSizeIsCarriedThrough() {
        assertEquals(2048 * 1024, enabled(bytes = 2048 * 1024).speedTestBytes)
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

    // --- download size options ---------------------------------------------

    @Test
    fun everySizeOptionOutgrowsSlowStartEnoughToMeanSomething() {
        // Below ~128KB the transfer finishes inside the first congestion windows,
        // so the figure describes round-trip time rather than bandwidth. That is
        // what made an 8KB gate read as 0.5 Mb/s on a fast link.
        assertTrue(SpeedSizeOptions.VALUES.min() >= 128 * 1024)
    }

    @Test
    fun theSmallestOptionMatchesTheConfidenceFloor() {
        // The smallest offered size is the one the scorer trusts least; they must
        // agree, or the UI offers a size whose reading is silently discarded
        // without saying so.
        assertEquals(
            ScanResult.SPEED_CONFIDENCE_FLOOR_BYTES,
            SpeedSizeOptions.VALUES.min().toLong(),
        )
    }

    @Test
    fun theLargestOptionIsTheFullyTrustedSize() {
        assertEquals(
            ScanResult.SPEED_CONFIDENCE_FULL_BYTES,
            SpeedSizeOptions.VALUES.max().toLong(),
        )
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
}
