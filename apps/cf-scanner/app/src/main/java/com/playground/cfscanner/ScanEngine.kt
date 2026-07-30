package com.playground.cfscanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Tunable scan parameters, surfaced in the UI. */
data class ScanConfig(
    val targetCount: Int = 300,
    val concurrency: Int = 16,
    val port: Int = 443,
    val tries: Int = 3,
    val timeoutMs: Int = 4000,
    val idleHoldMs: Int = 2500,
    val testWebSocket: Boolean = true,
    /** Bias sampling toward ranges that behave better on filtered networks. */
    val preferIranFriendlyRanges: Boolean = true,
    /** After a healthy hit, also probe its immediate neighbours. */
    val expandNeighbors: Boolean = true,
    /**
     * Minimum gap between progress callbacks, in milliseconds.
     *
     * Without throttling, hundreds of probes per second each trigger a UI
     * update; the main thread cannot keep up and Android raises an ANR. The
     * scan is long-running, so refreshing a few times a second is plenty.
     */
    val progressThrottleMs: Long = 150,
)

/** Live progress pushed to the UI. */
data class ScanProgress(
    val probed: Int,
    val total: Int,
    val healthy: Int,
    val currentIp: String,
)

/**
 * Drives the scan: samples candidate IPs, probes them concurrently, and reports
 * progress and results as they arrive.
 *
 * Probing happens on [Dispatchers.IO]; callbacks are always delivered on
 * [Dispatchers.Main] so callers can touch views directly and safely.
 */
class ScanEngine(private val config: ScanConfig = ScanConfig()) {

    private val rnd = Random()

    /**
     * Runs the scan.
     *
     * @param onProgress invoked on the main thread, rate-limited
     * @param onResult   invoked on the main thread for each healthy IP found
     * @return every result gathered, healthy or not, ranked best-first
     */
    suspend fun scan(
        onProgress: suspend (ScanProgress) -> Unit,
        onResult: suspend (ScanResult) -> Unit,
    ): List<ScanResult> {
        val prober = Prober(
            timeoutMs = config.timeoutMs,
            idleHoldMs = config.idleHoldMs,
            testWebSocket = config.testWebSocket,
        )

        val results = Collections.synchronizedList(mutableListOf<ScanResult>())
        val probed = AtomicInteger(0)
        val healthyCount = AtomicInteger(0)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val lastProgressAt = AtomicLong(0)

        val candidates = withContext(Dispatchers.Default) { sampleIps(config.targetCount) }
        val total = candidates.size
        val gate = Semaphore(config.concurrency)

        coroutineScope {
            for (ip in candidates) {
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        if (!seen.add(ip)) return@withPermit

                        val r = probeAndReport(
                            prober, ip, results, probed, healthyCount, total,
                            lastProgressAt, onProgress, onResult,
                        )

                        // Neighbour expansion: Cloudflare edges cluster, so the
                        // addresses beside a working IP are unusually likely to
                        // work too. Cheap, high-yield optimisation.
                        if (config.expandNeighbors && r.isHealthy()) {
                            for (neighbor in neighborsOf(ip)) {
                                currentCoroutineContext().ensureActive()
                                if (seen.add(neighbor)) {
                                    probeAndReport(
                                        prober, neighbor, results, probed, healthyCount,
                                        total, lastProgressAt, onProgress, onResult,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Final progress tick so the UI never ends stuck mid-way.
        withContext(Dispatchers.Main) {
            onProgress(ScanProgress(probed.get(), total, healthyCount.get(), ""))
        }

        return withContext(Dispatchers.Default) {
            Ranking.sort(results.toList(), SortBy.SCORE)
        }
    }

    private suspend fun probeAndReport(
        prober: Prober,
        ip: String,
        results: MutableList<ScanResult>,
        probed: AtomicInteger,
        healthyCount: AtomicInteger,
        total: Int,
        lastProgressAt: AtomicLong,
        onProgress: suspend (ScanProgress) -> Unit,
        onResult: suspend (ScanResult) -> Unit,
    ): ScanResult {
        val r = try {
            prober.probe(ip, config.port, config.tries)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            ScanResult(ip, config.port)
        }

        results.add(r)
        val done = probed.incrementAndGet()

        // A healthy hit is rare and important, so it is never throttled away.
        if (r.isHealthy()) {
            healthyCount.incrementAndGet()
            withContext(Dispatchers.Main) { onResult(r) }
        }

        // Progress is throttled: this fires hundreds of times per second
        // otherwise and starves the main thread.
        val now = System.currentTimeMillis()
        val previous = lastProgressAt.get()
        val isLast = done >= total
        if (isLast || now - previous >= config.progressThrottleMs) {
            if (lastProgressAt.compareAndSet(previous, now)) {
                withContext(Dispatchers.Main) {
                    onProgress(ScanProgress(done, total, healthyCount.get(), ip))
                }
            }
        }
        return r
    }

    /**
     * Picks random candidate addresses from Cloudflare's ranges.
     *
     * Sampling is weighted: when [ScanConfig.preferIranFriendlyRanges] is on,
     * 70% of candidates come from blocks that historically work better on
     * filtered networks, and 30% from the full list so unusual-but-good edges
     * are still discovered.
     */
    fun sampleIps(count: Int): List<String> {
        val all = CloudflareRanges.parseAll(CloudflareRanges.V4)
        val preferred = CloudflareRanges.parseAll(CloudflareRanges.V4_PREFERRED)
        val out = LinkedHashSet<String>(count)

        var guard = 0
        val maxIterations = count * 20
        while (out.size < count && guard++ < maxIterations) {
            val pool = if (config.preferIranFriendlyRanges && rnd.nextDouble() < 0.70) preferred else all
            val net = pool[rnd.nextInt(pool.size)]
            out.add(CloudflareRanges.longToIp(net.randomIp(rnd)))
        }
        return out.toList()
    }

    /**
     * Returns the addresses immediately before and after [ip], staying inside
     * Cloudflare's ranges.
     */
    private fun neighborsOf(ip: String, span: Int = 2): List<String> {
        val base = CloudflareRanges.ipToLong(ip)
        val nets = CloudflareRanges.parseAll()
        val out = mutableListOf<String>()
        for (delta in -span..span) {
            if (delta == 0) continue
            val candidate = base + delta
            if (candidate <= 0) continue
            if (CloudflareRanges.isCloudflare(candidate, nets)) {
                out.add(CloudflareRanges.longToIp(candidate))
            }
        }
        return out
    }
}
