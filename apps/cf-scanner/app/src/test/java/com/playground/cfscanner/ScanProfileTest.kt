package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanProfileTest {

    @Test
    fun standardModeUsesThreeImmediateAttempts() {
        val config = ScanConfig.forMode(count = 100, iranMode = false)
        assertEquals(3, config.tries)
        assertEquals(0, config.interAttemptDelayMinMs)
        assertEquals(0, config.interAttemptDelayMaxMs)
        assertEquals(0, config.webSocketPreDataHoldMs)
        assertEquals(32, config.neighborRadius)
        assertEquals(4, config.neighborPerHit)
    }

    @Test
    fun restrictedModeUsesFourDecorrelatedAttempts() {
        val config = ScanConfig.forMode(count = 100, iranMode = true)
        assertEquals(4, config.tries)
        assertEquals(50, config.interAttemptDelayMinMs)
        assertEquals(200, config.interAttemptDelayMaxMs)
        assertEquals(1_500, config.webSocketPreDataHoldMs)
    }
}
