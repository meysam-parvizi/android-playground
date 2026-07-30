package com.playground.cfscanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

/** Tunable scan parameters, surfaced in the UI. */
data class ScanConfig(
    val targetCount: Int = 300,
    val concurrency: Int = 24,
    val port: Int = 443,
    val tries: Int = 3,
    val timeoutMs: Int = 4000,
    val idleHoldMs: Int = 2500,
    val testWebSocket: Boolean = true,
    /** Bias sampling toward ranges that behave better from Iran. */
    val preferIranFriendlyRanges: Boolean = true,
    /** After a healthy hit, also probe its immediate neighbours. */
    val expandNeighbors: Boolean = true,
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
 */
class ScanEngine(private val config: ScanConfig = ScanConfig()) {

    private val rnd = Random()

    /**
     * Runs the scan.
     *
     * @param onProgress called on the main thread as probes complete
     * @param onResult   called on the main thread for each healthy IP found
     * @return every result gathered, healthy or not
     */
    suspend fun scan(
        onProgress: suspend (ScanProgress) -> Unit,
        onResult: suspend (ScanResult) -> Unit,
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val prober = Prober(
            timeoutMs = config.timeoutMs,
            idleHoldMs = config.idleHoldMs,
            testWebSocket = config.testWebSocket,
        )

        val results = Collections.synchronizedList(mutableListOf<ScanResult>())
        val probed = AtomicInteger(0)
        val healthyCount = AtomicInteger(0)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())

        val candidates = sampleIps(config.targetCount)
        // Extra slots reserved for neighbour expansion of successful hits.
        val total = candidates.size

        val gate = Semaphore(config.concurrency)

        coroutineScope {
            for (ip in candidates) {
                launch {
                    gate.withPermit {
                        if (!seen.add(ip)) return@withPermit
                        val r = probeAndReport(prober, ip, results, probed, healthyCount, total, onProgress, onResult)

                        // Neighbour expansion: Cloudflare edges cluster, so the
                        // addresses beside a working IP are unusually likely to
                        // work too. Cheap, high-yield optimisation.
                        if (config.expandNeighbors && r.isHealthy()) {
                            for (neighbor in neighborsOf(ip)) {
                                if (seen.add(neighbor)) {
                                    probeAndReport(
                                        prober, neighbor, results, probed, healthyCount,
                                        total, onProgress, onResult,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Ranking.sort(results.toList(), SortBy.SCORE)
    }

    private suspend fun probeAndReport(
        prober: Prober,
        ip: String,
        results: MutableList<ScanResult>,
        probed: AtomicInteger,
        healthyCount: AtomicInteger,
        total: Int,
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
        if (r.isHealthy()) {
            healthyCount.incrementAndGet()
            onResult(r)
        }
        onProgress(ScanProgress(done, total, healthyCount.get(), ip))
        return r
    }

    /**
     * Picks random candidate addresses from Cloudflare's ranges.
     *
     * Sampling is weighted: when [ScanConfig.preferIranFriendlyRanges] is on,
     * 70% of candidates come from the blocks that historically work better from
     * Iran, and 30% from the full list so unusual-but-good edges are still
     * discovered.
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
