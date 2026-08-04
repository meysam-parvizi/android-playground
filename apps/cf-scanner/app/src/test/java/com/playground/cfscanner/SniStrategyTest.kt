package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordered, bounded SNI fallback without a universal scanner fingerprint. */
class SniStrategyTest {

    @Test
    fun orderIsDeterministicForTheSameIpAndAttempt() {
        val a = SniStrategy.order("104.16.1.1", attemptIndex = 0)
        val b = SniStrategy.order("104.16.1.1", attemptIndex = 0)
        assertEquals(a, b)
    }

    @Test
    fun orderContainsEachTraceCapableHostExactlyOnce() {
        val order = SniStrategy.order("104.16.1.1", attemptIndex = 0)
        assertEquals(3, order.size)
        assertEquals(order.size, order.distinct().size)
        assertEquals(
            setOf("speed.cloudflare.com", "www.cloudflare.com", "cloudflare.com"),
            order.toSet(),
        )
    }

    @Test
    fun laterAttemptsRotateTheFirstChoice() {
        val first = SniStrategy.order("104.16.1.1", attemptIndex = 0)
        val second = SniStrategy.order("104.16.1.1", attemptIndex = 1)
        assertNotEquals(first.first(), second.first())
    }

    @Test
    fun differentIpsDoNotAllShareOneUniversalOrder() {
        val orders = listOf(
            "104.16.1.1",
            "172.64.10.20",
            "188.114.96.7",
            "162.159.1.9",
        ).map { SniStrategy.order(it, 0) }

        assertTrue("every IP received the same SNI fingerprint", orders.distinct().size > 1)
    }
}
