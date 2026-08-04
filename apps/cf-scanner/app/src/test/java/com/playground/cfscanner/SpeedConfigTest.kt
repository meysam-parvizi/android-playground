package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Benchmark stage configuration and its user-facing option list. */
class SpeedConfigTest {

    @Test
    fun defaultShortlistIsTenAndBenchmarkIsHalfAMegabyte() {
        val config = ScanConfig.forMode(count = 100, iranMode = true)
        assertEquals(10, config.speedTopN)
        assertEquals(512 * 1024, config.speedTestBytes)
    }

    @Test
    fun benchmarkConcurrencyStaysLowEnoughToAvoidSelfContention() {
        val config = ScanConfig.forMode(count = 100, iranMode = true)
        assertTrue(
            "benchmarking many IPs at once measures contention, not the edges",
            config.speedConcurrency in 1..3,
        )
        assertTrue(config.speedConcurrency < config.concurrency)
    }

    @Test
    fun exactlyOneControlledRetryIsAllowed() {
        assertEquals(1, ScanConfig.forMode(count = 100, iranMode = true).speedRetries)
    }

    @Test
    fun theUserCanTurnTheBenchmarkOff() {
        assertEquals(0, SpeedTopNOptions.VALUES.first())
        assertTrue("off must be selectable", SpeedTopNOptions.VALUES.contains(0))
    }

    @Test
    fun theDefaultOptionIndexSelectsTenTopResults() {
        assertEquals(10, SpeedTopNOptions.VALUES[SpeedTopNOptions.DEFAULT_INDEX])
    }

    @Test
    fun optionsAreAscendingAndBounded() {
        assertEquals(SpeedTopNOptions.VALUES.sorted(), SpeedTopNOptions.VALUES)
        assertEquals(SpeedTopNOptions.VALUES.distinct(), SpeedTopNOptions.VALUES)
        assertTrue(
            "a huge shortlist would defeat the point of a bounded second stage",
            SpeedTopNOptions.VALUES.max() <= 50,
        )
    }
}
