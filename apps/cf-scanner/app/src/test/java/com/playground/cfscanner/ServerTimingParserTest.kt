package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for parsing Cloudflare's `Server-Timing` headers.
 *
 * The header samples here are real, captured from `speed.cloudflare.com`. The
 * format is undocumented, so the parser must extract what it can and never throw
 * — a scan has to keep working if Cloudflare changes the shape tomorrow.
 */
class ServerTimingParserTest {

    /** Verbatim from a live response. */
    private val realEdge = "cfSpeedEdge;dur=7, cfSpeedWorker;dur=41"
    private val realL4 = "cfL4;desc=\"?proto=TCP&rtt=4024&min_rtt=3979&rtt_var=1524" +
        "&sent=5&recv=7&lost=0&retrans=0&sent_bytes=3941&recv_bytes=1785" +
        "&delivery_rate=1091731&cwnd=53&unsent_bytes=0&cid=b2a354fe1e5e6b7c&ts=101&x=0\""

    @Test
    fun parsesRealHeaders() {
        val t = ServerTimingParser.parse(listOf(realEdge, realL4))

        assertEquals(7L, t.edgeDurationMs)
        assertEquals(4024L, t.rttUs)
        assertEquals(3979L, t.minRttUs)
        assertEquals(1524L, t.rttVarUs)
        assertEquals(0, t.lost)
        assertEquals(0, t.retrans)
        assertEquals(1091731L, t.deliveryRateBps)
        assertTrue(t.hasAnything)
    }

    @Test
    fun convertsMicrosecondsToMilliseconds() {
        val t = ServerTimingParser.parse(listOf(realL4))
        assertEquals(3L, t.minRttMs)   // 3979us
        assertEquals(1L, t.rttVarMs)   // 1524us
    }

    /**
     * The edge metric must be read, not the worker metric.
     *
     * Both carry a `dur=`, and `cfSpeedWorker` can appear first. Subtracting the
     * worker's duration from latency would badly distort the result.
     */
    @Test
    fun readsEdgeDurationNotWorkerDuration() {
        val t = ServerTimingParser.parse(listOf("cfSpeedWorker;dur=99, cfSpeedEdge;dur=4"))
        assertEquals(4L, t.edgeDurationMs)
    }

    @Test
    fun acceptsFractionalDurations() {
        val t = ServerTimingParser.parse(listOf("cfSpeedEdge;dur=6.5"))
        assertEquals(6L, t.edgeDurationMs)
    }

    @Test
    fun detectsLossAndRetransmission() {
        val t = ServerTimingParser.parse(
            listOf("cfL4;desc=\"?rtt=9000&min_rtt=8500&rtt_var=4000&lost=4&retrans=2\""),
        )
        assertEquals(4, t.lost)
        assertEquals(2, t.retrans)
        assertTrue(t.hadLossOrRetrans)
    }

    @Test
    fun cleanConnectionReportsNoLoss() {
        val t = ServerTimingParser.parse(listOf(realL4))
        assertFalse(t.hadLossOrRetrans)
    }

    /** Malformed, truncated, or absent headers must yield nothing, never an error. */
    @Test
    fun survivesMalformedInput() {
        val hostile = listOf(
            emptyList(),
            listOf("x-cache: HIT"),
            listOf("cfL4;desc=\"?rtt=abc&lost=&retrans=x\""),
            listOf("cfL4;desc=\"?rtt=100"),          // unterminated quote
            listOf("cfL4;desc=?rtt=100&lost=1"),      // no quotes at all
            listOf(""),
            listOf("cfSpeedEdge"),                    // metric with no dur
        )
        for (input in hostile) {
            val t = ServerTimingParser.parse(input)
            // Must not throw, and must not invent values.
            assertNull("garbage should not produce a duration: $input", t.edgeDurationMs)
        }
    }

    @Test
    fun partialHeadersStillYieldWhatIsPresent() {
        val edgeOnly = ServerTimingParser.parse(listOf("cfSpeedEdge;dur=12"))
        assertEquals(12L, edgeOnly.edgeDurationMs)
        assertNull(edgeOnly.minRttUs)
        assertTrue(edgeOnly.hasAnything)

        val l4Only = ServerTimingParser.parse(listOf("cfL4;desc=\"?rtt=5000&lost=3\""))
        assertEquals(5000L, l4Only.rttUs)
        assertEquals(3, l4Only.lost)
        assertNull(l4Only.edgeDurationMs)
    }

    @Test
    fun emptyTimingHasNothing() {
        assertFalse(EdgeTiming().hasAnything)
        assertFalse(EdgeTiming().hadLossOrRetrans)
        assertNull(EdgeTiming().minRttMs)
    }
}

/**
 * Tests for how edge telemetry feeds into the metrics.
 *
 * The point of reading these headers is that the server sees things the client
 * cannot: with only three attempts, client-side loss can be 0%, 33%, 66% or 100%
 * and nothing in between, so an IP that completes every attempt while the TCP
 * stack retransmits heavily used to look flawless.
 */
class EdgeTimingMetricsTest {

    private fun result(
        lat: List<Long>,
        edge: EdgeTiming? = null,
    ) = ScanResult(ip = "104.16.0.1", port = 443).apply {
        latencies.addAll(lat)
        tlsOk = true
        stableOk = true
        wsOk = true
        httpStatus = 200
        colo = "FRA"
        this.edge = edge
    }

    @Test
    fun serverProcessingTimeIsSubtractedFromLatency() {
        val plain = result(listOf(120, 125, 122))
        val withEdge = result(listOf(120, 125, 122), EdgeTiming(edgeDurationMs = 7))

        assertEquals(122L, plain.avgMs())
        assertEquals("edge think-time must not count as network latency", 115L, withEdge.avgMs())
    }

    @Test
    fun latencyNeverCollapsesToZero() {
        // A large edge duration must not produce 0 or a negative, which would read
        // as a failed attempt.
        val r = result(listOf(3, 3, 3), EdgeTiming(edgeDurationMs = 41))
        assertTrue("latency must stay positive, got ${r.avgMs()}", r.avgMs() >= 1)
    }

    @Test
    fun serverJitterIsPreferredOverThreeClientSamples() {
        val r = result(listOf(100, 101, 100), EdgeTiming(rttVarUs = 8000))
        assertEquals("should use the server's rtt_var", 8L, r.jitterMs())
        // The client figure is still available for comparison.
        assertTrue(r.clientJitterMs() < 8L)
    }

    @Test
    fun clientJitterIsUsedWhenNoTelemetry() {
        val r = result(listOf(40, 400, 90))
        assertEquals(r.clientJitterMs(), r.jitterMs())
        assertTrue("erratic latency should show jitter", r.jitterMs() > 0)
    }

    /** The headline improvement: loss that client-side probing cannot detect. */
    @Test
    fun retransmissionsCountAsLossEvenWhenEveryAttemptSucceeded() {
        val clean = result(listOf(100, 101, 100), EdgeTiming(lost = 0, retrans = 0))
        val struggling = result(listOf(100, 101, 100), EdgeTiming(lost = 4, retrans = 2))

        assertEquals("a clean connection is still 0% loss", 0.0, clean.loss(), 0.001)
        assertTrue(
            "an IP that retransmits heavily must not read as flawless",
            struggling.loss() > clean.loss(),
        )
    }

    @Test
    fun serverTelemetryAloneCannotCondemnAWorkingIp() {
        // Capped, so absurd counts cannot push a working IP past the 50% health gate.
        val r = result(listOf(100, 101, 100), EdgeTiming(lost = 9999, retrans = 9999))
        assertTrue("penalty must be capped, got ${r.loss()}", r.loss() <= 30.0)
        assertTrue("an otherwise working IP stays healthy", r.isHealthy())
    }

    @Test
    fun attemptFailuresStillDominateLoss() {
        val twoOfThreeFailed = result(listOf(0, 0, 101), EdgeTiming(lost = 0, retrans = 0))
        assertTrue(twoOfThreeFailed.loss() >= 50.0)
        assertFalse(twoOfThreeFailed.isHealthy())
    }

    @Test
    fun absentTelemetryLeavesBehaviourUnchanged() {
        // Backward compatibility: without the headers, loss is the old attempt ratio.
        assertEquals(0.0, result(listOf(100, 101, 102)).loss(), 0.001)
        assertEquals(33, result(listOf(0, 100, 100)).loss().toInt())
        assertEquals(100.0, result(emptyList()).loss(), 0.001)
    }

    /** A transfer that was attempted and moved no bytes means the IP is unusable. */
    @Test
    fun anIpThatStallsOnRealDataIsUnhealthy() {
        val stalled = result(listOf(30, 31, 30)).apply {
            downloadTested = true
            throughputBps = 0
        }
        assertFalse(
            "an IP that handshakes cleanly but transfers nothing must be rejected",
            stalled.isHealthy(),
        )

        val transferred = result(listOf(30, 31, 30)).apply {
            downloadTested = true
            downloadedBytes = 8 * 1024
            throughputBps = 500_000
        }
        assertTrue(transferred.isHealthy())
    }

    @Test
    fun tinyPartialDownloadDoesNotPassTheDataGate() {
        val partial = result(listOf(30, 31, 30)).apply {
            downloadTested = true
            downloadedBytes = 8 * 1024 - 1L
            throughputBps = 500_000
        }
        assertFalse(partial.isHealthy())
    }

    @Test
    fun eightKilobytesPassesTheDataGate() {
        val complete = result(listOf(30, 31, 30)).apply {
            downloadTested = true
            downloadedBytes = 8 * 1024L
            throughputBps = 500_000
        }
        assertTrue(complete.isHealthy())
    }

    @Test
    fun notRunningTheTransferTestDoesNotPenaliseAnIp() {
        // downloadTested = false means "never tried", not "failed".
        val untested = result(listOf(30, 31, 30))
        assertFalse(untested.downloadTested)
        assertTrue(untested.isHealthy())
    }

    @Test
    fun rttOnlyTelemetryIsStillUseful() {
        val timing = ServerTimingParser.parse(
            listOf("cfL4;desc=\"?proto=TCP&rtt=42000&rtt_var=2000&retrans=1\""),
        )

        assertEquals(42_000L, timing.rttUs)
        assertEquals(2_000L, timing.rttVarUs)
        assertTrue("RTT-only telemetry must not be discarded", timing.hasAnything)
    }
}
