package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-chain attempt accounting.
 *
 * Candidate-level booleans used to let different tries donate different pieces
 * of success: one try could supply HTTP/colo, another a positive TLS latency and
 * stability, and the aggregate could become clean even though no single try
 * passed the whole chain. These tests pin success to one concrete attempt.
 */
class AttemptResultTest {

    private fun attempt(
        tcpMs: Long = 40,
        tlsOk: Boolean = true,
        httpStatus: Int = 200,
        colo: String = "VIE",
        stabilityOk: Boolean = true,
    ) = AttemptResult(
        sni = "speed.cloudflare.com",
        tcpConnectMs = tcpMs,
        tlsOk = tlsOk,
        httpStatus = httpStatus,
        colo = colo,
        stabilityOk = stabilityOk,
    )

    @Test
    fun successPiecesFromDifferentAttemptsCannotBeCombined() {
        val result = ScanResult("104.16.1.1", 443).apply {
            // Has HTTP identity, but DPI killed the connection during the hold.
            attemptResults += attempt(stabilityOk = false)
            // Survived a hold, but never proved it reached a Cloudflare edge.
            attemptResults += attempt(httpStatus = 0, colo = "", stabilityOk = true)
            attemptResults += attempt(tlsOk = false, httpStatus = 0, colo = "")

            // Candidate-level gates are deliberately green: only attempt
            // accounting should reject this result.
            wsOk = true
            dataPathVerified = true
            downloadedBytes = 16 * 1024
            throughputBps = 1_000_000
        }

        assertEquals(0, result.successes)
        assertEquals(100.0, result.loss(), 0.001)
        assertFalse(result.isHealthy())
    }

    @Test
    fun twoCompleteAttemptsCanProduceACleanResult() {
        val result = ScanResult("104.16.1.1", 443).apply {
            attemptResults += attempt(tcpMs = 40)
            attemptResults += attempt(tcpMs = 44)
            attemptResults += attempt(stabilityOk = false)
            wsOk = true
            dataPathVerified = true
            downloadedBytes = 16 * 1024
            throughputBps = 1_000_000
        }

        assertEquals(2, result.successes)
        assertEquals(42, result.avgMs())
        assertEquals(100.0 / 3.0, result.loss(), 0.001)
        assertTrue(result.isHealthy())
    }

    @Test
    fun recordingAnIncompleteAttemptCannotDonateAggregateSuccessFields() {
        val result = ScanResult("104.16.1.1", 443)
        result.recordAttempt(attempt(stabilityOk = false))
        result.recordAttempt(attempt(httpStatus = 0, colo = ""))

        assertEquals(listOf(0L, 0L), result.latencies)
        assertFalse(result.tlsOk)
        assertEquals(0, result.httpStatus)
        assertEquals("", result.colo)
        assertFalse(result.stableOk)
    }

    @Test
    fun recordingACompleteAttemptPublishesItsFields() {
        val edge = EdgeTiming(rttUs = 42_000, rttVarUs = 2_000)
        val result = ScanResult("104.16.1.1", 443)
        result.recordAttempt(attempt(tcpMs = 42).copy(edge = edge))

        assertEquals(listOf(42L), result.latencies)
        assertTrue(result.tlsOk)
        assertEquals(200, result.httpStatus)
        assertEquals("VIE", result.colo)
        assertTrue(result.stableOk)
        assertEquals(edge, result.edge)
    }

    @Test
    fun displayedPingPrefersSmoothedCloudflareRtt() {
        val result = ScanResult("104.16.1.1", 443).apply {
            recordAttempt(attempt(tcpMs = 100).copy(edge = EdgeTiming(rttUs = 40_000)))
            recordAttempt(attempt(tcpMs = 120).copy(edge = EdgeTiming(rttUs = 44_000)))
        }

        // Average server-observed RTT = 42 ms; TCP connect timing is only fallback.
        assertEquals(42, result.avgMs())
    }

    @Test
    fun edgeProcessingTimeIsNotSubtractedFromTcpFallback() {
        val result = ScanResult("104.16.1.1", 443).apply {
            recordAttempt(
                attempt(tcpMs = 100).copy(edge = EdgeTiming(edgeDurationMs = 80)),
            )
            recordAttempt(
                attempt(tcpMs = 120).copy(edge = EdgeTiming(edgeDurationMs = 90)),
            )
        }

        // HTTP edgeDuration was never part of tcpConnectMs, so subtracting it
        // would mix unrelated measurements and manufacture a tiny ping.
        assertEquals(110, result.avgMs())
    }

    @Test
    fun traceRequiresSuccessStatusAndDatacenter() {
        assertTrue(attempt().traceOk)
        assertFalse(attempt(httpStatus = 404).traceOk)
        assertFalse(attempt(httpStatus = 0).traceOk)
        assertFalse(attempt(colo = "").traceOk)
    }

    @Test
    fun coreSuccessRequiresEveryStageOfOneAttempt() {
        assertTrue(attempt().coreSuccess)
        assertFalse(attempt(tcpMs = 0).coreSuccess)
        assertFalse(attempt(tlsOk = false).coreSuccess)
        assertFalse(attempt(httpStatus = 0).coreSuccess)
        assertFalse(attempt(colo = "").coreSuccess)
        assertFalse(attempt(stabilityOk = false).coreSuccess)
    }
}
