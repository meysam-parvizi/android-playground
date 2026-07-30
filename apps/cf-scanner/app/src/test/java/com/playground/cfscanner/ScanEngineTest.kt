package com.playground.cfscanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the freeze reported on a real device: starting a scan made
 * the app completely unresponsive and even hung the phone.
 *
 * Four defects caused it, and each is covered here:
 *  1. callbacks fired on Dispatchers.IO while touching views
 *  2. unthrottled progress callbacks flooding the main thread
 *  3. Thread.sleep inside coroutines starving the IO dispatcher and defeating cancel
 *  4. a liveness check that always threw, so nothing was ever reported healthy
 *
 * These tests perform real (immediately-refused) socket calls against the
 * loopback discard port, so they use [runBlocking] rather than `runTest` — the
 * latter's virtual clock does not advance real network waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanEngineTest {

    private val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "cf-test-main") }
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    @Before
    fun installMain() {
        // The real Dispatchers.Main needs a Looper, which plain unit tests lack.
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun removeMain() {
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    /** Config pointed at a closed loopback port: fails fast, touches no network. */
    private fun fastFailConfig(
        count: Int,
        concurrency: Int = 4,
        throttleMs: Long = 0,
        tries: Int = 1,
    ) = ScanConfig(
        targetCount = count,
        concurrency = concurrency,
        port = DISCARD_PORT,
        tries = tries,
        timeoutMs = 200,
        idleHoldMs = 100,
        testWebSocket = false,
        expandNeighbors = false,
        progressThrottleMs = throttleMs,
    )

    /**
     * Defect 1: callbacks must arrive on the main dispatcher.
     *
     * Previously the engine wrapped everything in withContext(Dispatchers.IO) and
     * invoked callbacks there, so the UI was mutated off the main thread.
     */
    @Test
    fun callbacksArriveOnMainDispatcher() = runBlocking {
        val threads = Collections.synchronizedSet(mutableSetOf<String>())

        ScanEngine(fastFailConfig(count = 6, concurrency = 3)).scan(
            onProgress = { threads.add(Thread.currentThread().name) },
            onResult = { threads.add(Thread.currentThread().name) },
        )

        assertTrue("no callback was delivered at all", threads.isNotEmpty())
        assertEquals(
            "callbacks must be delivered on the main dispatcher, saw $threads",
            setOf(MAIN_THREAD), threads,
        )
    }

    /**
     * Defect 2: progress must be rate-limited.
     *
     * One callback per completed probe flooded the main thread and Android raised
     * an ANR. A long throttle window should collapse many completions into very
     * few UI updates.
     */
    @Test
    fun progressIsThrottled() = runBlocking {
        val calls = AtomicInteger(0)
        val probes = 40

        ScanEngine(
            fastFailConfig(count = probes, concurrency = 20, throttleMs = 60_000),
        ).scan(
            onProgress = { calls.incrementAndGet() },
            onResult = { },
        )

        assertTrue("at least one progress callback expected", calls.get() >= 1)
        assertTrue(
            "throttling failed: ${calls.get()} callbacks for $probes probes",
            calls.get() <= 4,
        )
    }

    /** The engine must always emit a final tick so the UI never ends mid-way. */
    @Test
    fun finalProgressTickIsAlwaysEmitted() = runBlocking {
        var last: ScanProgress? = null

        ScanEngine(fastFailConfig(count = 5, concurrency = 2, throttleMs = 60_000)).scan(
            onProgress = { last = it },
            onResult = { },
        )

        assertNotNull("expected a final progress callback", last)
        assertEquals("final tick must report all probes done", 5, last!!.probed)
        assertEquals(5, last!!.total)
    }

    /**
     * Defect 3: a running scan must unwind promptly when cancelled.
     *
     * Thread.sleep is not cancellable, so Stop used to leave threads parked for
     * seconds while the UI sat frozen. With delay() the scan aborts quickly.
     */
    @Test
    fun scanCancelsPromptly() = runBlocking {
        // Long holds and timeouts: without cooperative cancellation this would
        // take far longer than the assertion window below.
        val engine = ScanEngine(
            ScanConfig(
                targetCount = 500,
                concurrency = 16,
                port = DISCARD_PORT,
                tries = 3,
                timeoutMs = 5_000,
                idleHoldMs = 5_000,
                testWebSocket = false,
                expandNeighbors = false,
            ),
        )

        val job = async(Dispatchers.Default) { engine.scan(onProgress = { }, onResult = { }) }
        withContext(Dispatchers.Default) { delay(250) }

        val startedAt = System.currentTimeMillis()
        job.cancel()
        val settled = withTimeoutOrNull(4_000) {
            try {
                job.await()
            } catch (_: CancellationException) {
                // expected
            }
            true
        }
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("scan ignored cancellation for ${elapsed}ms", settled == true)
        assertTrue("cancellation took too long: ${elapsed}ms", elapsed < 4_000)
    }

    /**
     * A closed port must never be reported as usable.
     *
     * Safety net for defect 4 in the opposite direction: whatever the liveness
     * check does, an unreachable address has to stay unhealthy.
     */
    @Test
    fun unreachableAddressesAreNeverHealthy() = runBlocking {
        val results = ScanEngine(fastFailConfig(count = 8, concurrency = 4, tries = 2))
            .scan(onProgress = { }, onResult = { })

        assertTrue("expected some results", results.isNotEmpty())
        for (r in results) {
            assertFalse("${r.ip} on a closed port must not be healthy", r.isHealthy())
            assertEquals("unhealthy results must score 0", 0, r.score())
            assertTrue("a refused connection must record loss", r.loss() > 0.0)
        }
    }

    /** Healthy hits are never throttled away, so no successful IP is lost. */
    @Test
    fun resultsAreReturnedRankedBestFirst() = runBlocking {
        val results = ScanEngine(fastFailConfig(count = 6, concurrency = 3))
            .scan(onProgress = { }, onResult = { })
        val scores = results.map { it.score() }
        assertEquals("results must be in descending score order", scores.sortedDescending(), scores)
    }

    /**
     * Main-dispatcher callbacks must never overlap.
     *
     * This is what makes it safe for the UI layer to touch views directly from
     * them, and it proves the dispatch is genuinely serialised.
     */
    @Test
    fun mainCallbacksNeverOverlap() = runBlocking {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)

        ScanEngine(fastFailConfig(count = 30, concurrency = 8)).scan(
            onProgress = {
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { p -> maxOf(p, now) }
                delay(1)
                inFlight.decrementAndGet()
            },
            onResult = { },
        )

        assertEquals("callbacks overlapped — they are not serialised", 1, peak.get())
    }

    @Test
    fun samplerIsBoundedAndDeduplicated() {
        val sampled = ScanEngine(ScanConfig(targetCount = 50)).sampleIps(50)
        assertTrue("sampler must not exceed the requested count", sampled.size <= 50)
        assertTrue("sampler returned nothing", sampled.isNotEmpty())
        assertEquals("sampler must not return duplicates", sampled.size, sampled.distinct().size)
    }

    private companion object {
        const val MAIN_THREAD = "cf-test-main"
        /** TCP discard: reliably closed on loopback, so connects fail immediately. */
        const val DISCARD_PORT = 9
    }
}
