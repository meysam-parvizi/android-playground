package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The benchmark runs before a result is published, not in a phase afterwards.
 *
 * A separate second stage meant a clean IP appeared in the list with no speed
 * and a provisional grade, then changed later. Measuring it on discovery means
 * every row is complete and final the moment it appears.
 */
class InlineBenchmarkTest {

    /** Records what the engine asked for, in order, without touching sockets. */
    private class FakeProber(
        /** Decides which sampled addresses come back healthy. */
        private val isHealthy: (String) -> Boolean = { true },
        private val bps: Long = 4_000_000,
    ) : Prober() {
        val benchmarked = mutableListOf<String>()
        val benchmarkSizes = mutableListOf<Int>()

        override suspend fun probe(ip: String, port: Int, tries: Int): ScanResult =
            ScanResult(ip = ip, port = port).apply {
                if (!isHealthy(ip)) return@apply
                repeat(tries) {
                    recordAttempt(
                        AttemptResult(
                            sni = "speed.cloudflare.com",
                            tcpConnectMs = 30,
                            tlsRequired = port != 80,
                            tlsOk = true,
                            httpStatus = 200,
                            colo = "VIE",
                            stabilityOk = true,
                            edge = EdgeTiming(rttUs = 30_000),
                        ),
                    )
                }
                wsOk = true
            }

        override suspend fun measureSpeed(result: ScanResult, bytes: Int) {
            benchmarked += result.ip
            benchmarkSizes += bytes
            result.recordBenchmark(bytes = bytes.toLong(), bps = bps)
        }
    }

    private fun engine(prober: Prober, enabled: Boolean, count: Int = 3) = ScanEngine(
        config = ScanConfig(
            targetCount = count,
            concurrency = 1,
            speedTestEnabled = enabled,
            speedTestBytes = 512 * 1024,
            expandNeighbors = false,
            testWebSocket = false,
            downloadBytes = 0,
            progressThrottleMs = 0,
        ),
        proberFactory = { prober },
    )

    @Test
    fun aPublishedResultAlreadyCarriesItsSpeed() = kotlinx.coroutines.test.runTest {
        // The property that matters: by the time the UI sees a row, the row is
        // final. No "weak" placeholder that turns into "good" later.
        // Read from the outcome, not from onResult: those callbacks are
        // delivered on Dispatchers.Main, which a plain JVM test has no looper
        // for, so they are swallowed by design.
        val prober = FakeProber()

        val outcome = engine(prober, enabled = true).scan(onProgress = {}, onResult = {})
        val healthy = outcome.results.filter { it.isHealthy() }

        assertTrue("expected at least one healthy result", healthy.isNotEmpty())
        for (r in healthy) {
            assertTrue("${r.ip} reached the list without a measured speed", r.hasMeasuredSpeed)
        }
    }

    @Test
    fun onlyHealthyResultsAreBenchmarked() = kotlinx.coroutines.test.runTest {
        // Benchmarking a failed address spends data for nothing. Half the
        // sampled addresses fail here, keyed off the address itself so the split
        // is deterministic without knowing which ones get sampled.
        val prober = FakeProber(isHealthy = { it.hashCode() % 2 == 0 })

        val outcome = engine(prober, enabled = true, count = 12)
            .scan(onProgress = {}, onResult = {})

        val healthyIps = outcome.results.filter { it.isHealthy() }.map { it.ip }.toSet()
        val unhealthyIps = outcome.results.filterNot { it.isHealthy() }.map { it.ip }.toSet()

        assertTrue("expected a mix to make this meaningful", healthyIps.isNotEmpty())
        assertTrue(
            "unhealthy addresses must not be benchmarked",
            prober.benchmarked.none { it in unhealthyIps },
        )
        assertEquals(healthyIps, prober.benchmarked.toSet())
    }

    @Test
    fun nothingIsBenchmarkedWhenTheTestIsOff() = kotlinx.coroutines.test.runTest {
        val prober = FakeProber()

        engine(prober, enabled = false).scan(onProgress = {}, onResult = {})

        assertTrue(prober.benchmarked.isEmpty())
    }

    @Test
    fun theConfiguredSizeIsUsed() = kotlinx.coroutines.test.runTest {
        val prober = FakeProber()

        engine(prober, enabled = false).scan(onProgress = {}, onResult = {})
        assertTrue(prober.benchmarkSizes.isEmpty())

        val measuring = FakeProber()
        engine(measuring, enabled = true).scan(onProgress = {}, onResult = {})
        assertTrue(measuring.benchmarkSizes.all { it == 512 * 1024 })
    }

    @Test
    fun aFailedBenchmarkStillPublishesTheResult() = kotlinx.coroutines.test.runTest {
        // Speed is a measurement, not a health check. A clean IP whose benchmark
        // fails is still a clean IP and must not vanish from the list.
        val prober = object : Prober() {
            override suspend fun probe(ip: String, port: Int, tries: Int): ScanResult =
                ScanResult(ip = ip, port = port).apply {
                    repeat(tries) {
                        recordAttempt(
                            AttemptResult(
                                sni = "s", tcpConnectMs = 30, tlsRequired = true, tlsOk = true,
                                httpStatus = 200, colo = "VIE", stabilityOk = true,
                                edge = EdgeTiming(rttUs = 30_000),
                            ),
                        )
                    }
                    wsOk = true
                }

            override suspend fun measureSpeed(result: ScanResult, bytes: Int) {
                result.recordBenchmark(bytes = 0, bps = 0)
            }
        }

        val outcome = engine(prober, enabled = true).scan(onProgress = {}, onResult = {})
        val healthy = outcome.results.filter { it.isHealthy() }

        assertTrue("a clean IP must survive a failed benchmark", healthy.isNotEmpty())
        assertFalse("but it must not claim a speed", healthy.first().hasMeasuredSpeed)
    }

    @Test
    fun thereIsNoSeparateSpeedPhaseLeftInTheEngine() = kotlinx.coroutines.test.runTest {
        // The MEASURING phase existed only because benchmarking happened after
        // the scan. Inline measurement removes the reason for it.
        val src = java.io.File(
            "src/main/java/com/playground/cfscanner/ScanEngine.kt",
        ).readText()
        assertFalse("runSpeedPhase should be gone", src.contains("runSpeedPhase"))
        assertFalse("the phase callback should be gone", src.contains("onSpeedPhaseStart"))
    }
}
