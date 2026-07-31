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
    /**
     * Weight each range by how many addresses it holds.
     *
     * Cloudflare's blocks differ in size by a factor of 512: `104.16.0.0/13` holds
     * 524,288 addresses while `131.0.72.0/22` holds 1,024. Picking a range
     * uniformly therefore puts ~512x more sampling pressure on each address of a
     * small block than of a large one — an accidental bias, not a decision.
     *
     * Left off by default: over-sampling the small blocks is a defensible
     * heuristic, since a small range is as likely to hold a working edge as a
     * large one, and changing the default would silently alter everyone's results.
     * Turning it on explores the big /13s in proportion to their real size.
     */
    val sizeWeightedSampling: Boolean = false,
    /** After a healthy hit, also probe its immediate neighbours. */
    val expandNeighbors: Boolean = true,
    /**
     * Bytes to pull from Cloudflare's speed endpoint per healthy candidate, or 0
     * to skip the transfer test.
     *
     * Catches IPs that complete every handshake and then stall on real data —
     * invisible to a handshake-only probe. Costs one extra connection per healthy
     * candidate, so it is modest by default rather than off.
     */
    val downloadBytes: Int = 0,
    /**
     * Upper bound on extra probes added by neighbour expansion, as a fraction of
     * [targetCount].
     *
     * Expansion is valuable — Cloudflare edges cluster, so the addresses next to
     * a working one often work too — but it must not run away. On a good network
     * a large share of probes succeed, and unbounded expansion would keep queueing
     * neighbours long past the requested scan size.
     */
    val neighborBudgetRatio: Double = 0.25,
    /**
     * Minimum gap between progress callbacks, in milliseconds.
     *
     * Without throttling, hundreds of probes per second each trigger a UI
     * update; the main thread cannot keep up and Android raises an ANR. The
     * scan is long-running, so refreshing a few times a second is plenty.
     */
    val progressThrottleMs: Long = 150,
)

/**
 * Live progress pushed to the UI.
 *
 * [total] is the number of probes currently planned, not a fixed target: it grows
 * when neighbour expansion queues extra addresses. Reporting a fixed target is
 * what produced the nonsensical "checking 334 of 300".
 */
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
            downloadBytes = config.downloadBytes,
        )

        val results = Collections.synchronizedList(mutableListOf<ScanResult>())
        val probed = AtomicInteger(0)
        val healthyCount = AtomicInteger(0)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val lastProgressAt = AtomicLong(0)

        val candidates = withContext(Dispatchers.Default) { sampleIps(config.targetCount) }

        // Planned work grows as neighbours are queued, so progress never exceeds
        // its own total.
        val planned = AtomicInteger(candidates.size)

        // Hard cap on expansion so a healthy network cannot extend the scan
        // indefinitely.
        val neighborBudget = AtomicInteger(
            (config.targetCount * config.neighborBudgetRatio).toInt().coerceAtLeast(0),
        )

        val gate = Semaphore(config.concurrency)

        coroutineScope {
            for (ip in candidates) {
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        if (!seen.add(ip)) {
                            // Duplicate sample: it will never be probed, so drop
                            // it from the plan to keep the total honest.
                            planned.decrementAndGet()
                            return@withPermit
                        }

                        val r = probeAndReport(
                            prober, ip, results, probed, healthyCount, planned,
                            lastProgressAt, onProgress, onResult,
                        )

                        // Neighbour expansion: Cloudflare edges cluster, so the
                        // addresses beside a working IP are unusually likely to
                        // work too. Cheap, high-yield optimisation.
                        if (config.expandNeighbors && r.isHealthy()) {
                            for (neighbor in neighborsOf(ip)) {
                                currentCoroutineContext().ensureActive()
                                if (!claimNeighborBudget(neighborBudget)) break
                                if (!seen.add(neighbor)) continue

                                // Announce the extra work before doing it.
                                planned.incrementAndGet()
                                probeAndReport(
                                    prober, neighbor, results, probed, healthyCount,
                                    planned, lastProgressAt, onProgress, onResult,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Final tick so the UI never ends stuck mid-way, and so probed == total.
        withContext(Dispatchers.Main) {
            onProgress(ScanProgress(probed.get(), planned.get(), healthyCount.get(), ""))
        }

        return withContext(Dispatchers.Default) {
            Ranking.sort(results.toList(), SortBy.SCORE)
        }
    }

    /** Atomically takes one unit of expansion budget; false when exhausted. */
    private fun claimNeighborBudget(budget: AtomicInteger): Boolean {
        while (true) {
            val remaining = budget.get()
            if (remaining <= 0) return false
            if (budget.compareAndSet(remaining, remaining - 1)) return true
        }
    }

    private suspend fun probeAndReport(
        prober: Prober,
        ip: String,
        results: MutableList<ScanResult>,
        probed: AtomicInteger,
        healthyCount: AtomicInteger,
        planned: AtomicInteger,
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
        val total = planned.get()
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
     *
     * Within the chosen pool, [ScanConfig.sizeWeightedSampling] decides whether a
     * range is picked uniformly or in proportion to how many addresses it holds.
     */
    fun sampleIps(count: Int): List<String> {
        val all = CloudflareRanges.parseAll(CloudflareRanges.V4)
        val preferred = CloudflareRanges.parseAll(CloudflareRanges.V4_PREFERRED)
        // Cumulative address counts, so a range can be drawn in proportion to size.
        val allWeights = cumulativeSizes(all)
        val preferredWeights = cumulativeSizes(preferred)
        val out = LinkedHashSet<String>(count)

        var guard = 0
        val maxIterations = count * 20
        while (out.size < count && guard++ < maxIterations) {
            val usePreferred = config.preferIranFriendlyRanges && rnd.nextDouble() < 0.70
            val pool = if (usePreferred) preferred else all
            val weights = if (usePreferred) preferredWeights else allWeights
            val net = if (config.sizeWeightedSampling) {
                pickWeighted(pool, weights)
            } else {
                pool[rnd.nextInt(pool.size)]
            }
            out.add(CloudflareRanges.longToIp(net.randomIp(rnd)))
        }
        return out.toList()
    }

    /** Running totals of range sizes, for proportional selection. */
    private fun cumulativeSizes(nets: List<CloudflareRanges.Cidr>): LongArray {
        val cumulative = LongArray(nets.size)
        var running = 0L
        for (i in nets.indices) {
            running += nets[i].size
            cumulative[i] = running
        }
        return cumulative
    }

    /** Selects a range with probability proportional to its address count. */
    private fun pickWeighted(
        nets: List<CloudflareRanges.Cidr>,
        cumulative: LongArray,
    ): CloudflareRanges.Cidr {
        val total = cumulative.lastOrNull() ?: return nets[rnd.nextInt(nets.size)]
        if (total <= 0) return nets[rnd.nextInt(nets.size)]
        val target = (rnd.nextDouble() * total).toLong()
        // Binary search for the first cumulative total exceeding the target.
        var low = 0
        var high = cumulative.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (cumulative[mid] <= target) low = mid + 1 else high = mid
        }
        return nets[low]
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
