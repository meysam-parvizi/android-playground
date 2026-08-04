package com.playground.cfscanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the outcome the engine reports, using a fake prober.
 *
 * The distinction being verified is one the app got wrong: a scan where every
 * probe failed looks identical in the counts to a scan that simply found nothing
 * usable, but the causes are opposite. The first means the network is down and
 * the user should check their connection; the second means they should try
 * again. Telling someone with Wi-Fi off to "raise the number of IPs" is useless
 * advice, and that is exactly what the app used to do.
 *
 * A fake prober keeps this deterministic and fast — the older engine tests probe
 * a closed loopback port, which works but cannot express "connected but
 * unhealthy" as distinct from "never connected at all".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanOutcomeTest {

    /**
     * The engine delivers callbacks on [Dispatchers.Main], which does not exist
     * on a plain JVM — touching it throws "Module with the Main dispatcher had
     * failed to initialize". Substituting a test dispatcher makes the engine
     * runnable off-device.
     */
    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun removeMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** A prober that never connects, as if the device were offline. */
    private class AlwaysFailingProber : Prober() {
        override suspend fun probe(ip: String, port: Int, tries: Int): ScanResult =
            ScanResult(ip = ip, port = port).apply {
                // No successes: every attempt recorded as a failure.
                repeat(tries) { latencies.add(0) }
            }
    }

    /** A prober that connects every time but never passes the health checks. */
    private class ReachableButUnhealthyProber : Prober() {
        override suspend fun probe(ip: String, port: Int, tries: Int): ScanResult =
            ScanResult(ip = ip, port = port).apply {
                repeat(tries) { latencies.add(40) }
                tlsOk = true
                // Left unhealthy: no colo, no HTTP status, no stability hold.
            }
    }

    private fun config(count: Int) = ScanConfig(
        targetCount = count,
        concurrency = 4,
        tries = 2,
        expandNeighbors = false,
        progressThrottleMs = 0,
    )

    @Test
    fun everyProbeFailingIsReportedAsAFailure() = runTest {
        val outcome = ScanEngine(config(6)) { AlwaysFailingProber() }
            .scan(onProgress = { }, onResult = { })

        assertTrue("results were expected even when all probes fail", outcome.results.isNotEmpty())
        assertTrue(
            "a scan where nothing connected must report everyProbeFailed",
            outcome.everyProbeFailed,
        )
    }

    /**
     * The decisive case: connections succeed, so the network is fine, but nothing
     * passes the health checks. Reporting a connectivity failure here would send
     * the user to check a connection that works.
     */
    @Test
    fun reachableButUnhealthyIsNotReportedAsAFailure() = runTest {
        val outcome = ScanEngine(config(6)) { ReachableButUnhealthyProber() }
            .scan(onProgress = { }, onResult = { })

        assertTrue(outcome.results.isNotEmpty())
        assertEquals(
            "no result should have passed the health checks",
            0,
            Ranking.healthy(outcome.results).size,
        )
        assertFalse(
            "connections succeeded, so this is not a connectivity failure",
            outcome.everyProbeFailed,
        )
    }

    /**
     * A single reachable address is enough to prove the network works.
     */
    @Test
    fun oneReachableAddressClearsTheFailureFlag() = runTest {
        var first = true
        val outcome = ScanEngine(config(6)) {
            object : Prober() {
                override suspend fun probe(ip: String, port: Int, tries: Int): ScanResult =
                    ScanResult(ip = ip, port = port).apply {
                        val reachable = synchronized(this@ScanOutcomeTest) {
                            val v = first
                            first = false
                            v
                        }
                        repeat(tries) { latencies.add(if (reachable) 30 else 0) }
                    }
            }
        }.scan(onProgress = { }, onResult = { })

        assertFalse(
            "one successful connection means the network is reachable",
            outcome.everyProbeFailed,
        )
    }

    /**
     * Guards the empty case: a scan cancelled before anything ran must not be
     * mistaken for a network failure.
     */
    @Test
    fun anEmptyScanIsNotAFailure() = runTest {
        val outcome = ScanEngine(config(0)) { AlwaysFailingProber() }
            .scan(onProgress = { }, onResult = { })

        assertTrue(outcome.results.isEmpty())
        assertFalse(
            "an empty scan has no evidence of a network problem",
            outcome.everyProbeFailed,
        )
    }

    /**
     * The scan must survive a callback that throws.
     *
     * The callbacks touch views, so they can fail for reasons unrelated to
     * scanning. Before this was guarded, one such exception propagated out of the
     * engine's coroutineScope and cancelled every other probe in flight.
     */
    @Test
    fun aThrowingCallbackDoesNotAbortTheScan() = runTest {
        val outcome = ScanEngine(config(8)) { ReachableButUnhealthyProber() }
            .scan(
                onProgress = { error("UI update failed") },
                onResult = { error("UI update failed") },
            )

        assertEquals(
            "every address should still have been probed",
            8,
            outcome.results.size,
        )
    }
}
