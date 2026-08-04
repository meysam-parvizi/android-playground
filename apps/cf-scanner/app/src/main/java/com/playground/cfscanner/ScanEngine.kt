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
    /** Cancellable decorrelation delay between attempts; 0 disables it. */
    val interAttemptDelayMinMs: Int = 0,
    val interAttemptDelayMaxMs: Int = 0,
    val timeoutMs: Int = 4000,
    val idleHoldMs: Int = 2500,
    val webSocketPreDataHoldMs: Int = 0,
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
     * How far from a clean hit neighbour expansion may reach, and how many
     * addresses it may spend there.
     *
     * Contiguous ±1, ±2 expansion spends every probe on four adjacent addresses
     * that usually share one edge server's fate. Geometric offsets reach ±1, ±2,
     * ±4 — twice as far for the same four probes per hit — which is where a
     * second usable edge is actually likely to sit. Cost per hit is unchanged,
     * so the global [neighborBudgetRatio] still expands the same number of hits.
     */
    val neighborRadius: Int = 32,
    val neighborPerHit: Int = 4,
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
     * Second-stage benchmark: how many top results to measure, how much to pull
     * from each, how many at a time, and how many retries a failed measurement
     * gets. [speedTopN] of 0 disables the stage.
     */
    val speedTopN: Int = 0,
    val speedTestBytes: Int = 512 * 1024,
    val speedConcurrency: Int = 2,
    val speedRetries: Int = 1,
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
) {
    companion object {
        /**
         * Builds the config for a scan mode.
         *
         * These numbers used to sit inline in the Activity, which put tuning
         * policy in the UI layer where it could not be tested. Keeping them here
         * means the profile is one reviewable place, and the defaults above
         * cannot silently disagree with what is actually used.
         */
        fun forMode(
            count: Int,
            iranMode: Boolean,
            speedTopN: Int = SpeedTopNOptions.VALUES[SpeedTopNOptions.DEFAULT_INDEX],
        ): ScanConfig = ScanConfig(
            targetCount = count,
            speedTopN = speedTopN,
            tries = if (iranMode) RESTRICTED_TRIES else STANDARD_TRIES,
            interAttemptDelayMinMs = if (iranMode) RESTRICTED_JITTER_MIN_MS else 0,
            interAttemptDelayMaxMs = if (iranMode) RESTRICTED_JITTER_MAX_MS else 0,
            preferIranFriendlyRanges = iranMode,
            // The idle hold proves an IP survives DPI, so keep it generous on a
            // restricted network and quick otherwise.
            idleHoldMs = if (iranMode) RESTRICTED_IDLE_HOLD_MS else STANDARD_IDLE_HOLD_MS,
            webSocketPreDataHoldMs = if (iranMode) RESTRICTED_WS_PRE_DATA_HOLD_MS else 0,
            testWebSocket = iranMode,
            // Transfer a small payload on a restricted network: an IP that
            // handshakes cleanly and then stalls on real data is worse than
            // useless, and only moving bytes reveals it.
            downloadBytes = if (iranMode) DISCOVERY_GATE_BYTES else 0,
        )

        /** Long enough for a DPI reset to show up on a filtered network. */
        const val RESTRICTED_IDLE_HOLD_MS = 2500

        /** Enough to catch an obviously dead connection without slowing the scan. */
        const val STANDARD_IDLE_HOLD_MS = 1200

        /**
         * Payload pulled per candidate during discovery.
         *
         * Only large enough to prove the data path carries real bytes rather
         * than just completing handshakes. Downloading a benchmark-sized payload
         * here would cost 16x the data and still measure contention rather than
         * speed, because discovery runs at full concurrency — so the real
         * measurement happens in [SpeedPhase] instead.
         */
        const val DISCOVERY_GATE_BYTES = 8 * 1024
        const val STANDARD_TRIES = 3
        const val RESTRICTED_TRIES = 4
        const val RESTRICTED_JITTER_MIN_MS = 50
        const val RESTRICTED_JITTER_MAX_MS = 200
        const val RESTRICTED_WS_PRE_DATA_HOLD_MS = 1_500
    }
}

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

object SpeedTopNOptions {
    /** Shortlist sizes offered in settings; 0 turns the benchmark off. */
    val VALUES: List<Int> = listOf(0, 5, 10, 20, 50)

    /** Ten: enough to pick a winner, short enough not to double the scan. */
    const val DEFAULT_INDEX = 2
}

/**
 * Builds the real prober for a config.
 *
 * A top-level function rather than an inline lambda so it can serve as a default
 * argument and stay overridable in tests.
 */
private fun defaultProber(config: ScanConfig): Prober = Prober(
    timeoutMs = config.timeoutMs,
    idleHoldMs = config.idleHoldMs,
    testWebSocket = config.testWebSocket,
    downloadBytes = config.downloadBytes,
    webSocketPreDataHoldMs = config.webSocketPreDataHoldMs,
    interAttemptDelayMinMs = config.interAttemptDelayMinMs,
    interAttemptDelayMaxMs = config.interAttemptDelayMaxMs,
)

/**
 * The end of a scan.
 *
 * [everyProbeFailed] separates "the network is down" from "this network has no
 * usable Cloudflare IP". Both produce zero healthy results, but the advice
 * differs: one needs a connection, the other needs another attempt. Without this
 * distinction a user with Wi-Fi off was told to try more IPs.
 */
data class ScanOutcome(
    val results: List<ScanResult>,
    val everyProbeFailed: Boolean,
)

/**
 * Drives the scan: samples candidate IPs, probes them concurrently, and reports
 * progress and results as they arrive.
 *
 * Probing happens on [Dispatchers.IO]; callbacks are always delivered on
 * [Dispatchers.Main] so callers can touch views directly and safely.
 *
 * @param proberFactory builds the prober for a config. Injectable so the engine's
 *   own logic — neighbour budgets, deduplication, planned-total accounting — can
 *   be tested with a fake prober instead of real sockets.
 */
class ScanEngine(
    private val config: ScanConfig = ScanConfig(),
    private val proberFactory: (ScanConfig) -> Prober = ::defaultProber,
) {

    private val rnd = Random()

    /** Cloudflare's ranges, parsed once rather than per healthy hit. */
    private val nets by lazy { CloudflareRanges.parseAll() }

    /**
     * Runs the scan.
     *
     * @param onProgress invoked on the main thread, rate-limited
     * @param onResult   invoked on the main thread for each healthy IP found
     * @return the results gathered plus whether every probe failed outright
     */
    suspend fun scan(
        onProgress: suspend (ScanProgress) -> Unit,
        onResult: suspend (ScanResult) -> Unit,
    ): ScanOutcome {
        val prober = proberFactory(config)

        val results = Collections.synchronizedList(mutableListOf<ScanResult>())
        val probed = AtomicInteger(0)
        val healthyCount = AtomicInteger(0)
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val lastProgressAt = AtomicLong(0)

        // Counts probes where not a single connection attempt succeeded. If that
        // equals the number probed, the network itself is the problem rather than
        // the addresses, and the UI can say so instead of suggesting more IPs.
        val totalFailures = AtomicInteger(0)

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
                            prober, ip, results, probed, healthyCount, totalFailures,
                            planned, lastProgressAt, onProgress, onResult,
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
                                    totalFailures, planned, lastProgressAt,
                                    onProgress, onResult,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Final tick so the UI never ends stuck mid-way, and so probed == total.
        // Guarded like every other callback: a failure here would otherwise
        // propagate out of scan() and be reported as a scan error.
        deliver { onProgress(ScanProgress(probed.get(), planned.get(), healthyCount.get(), "")) }

        val gathered = results.toList()

        // Second stage: measure real throughput on the best few, now that
        // nothing else is competing for bandwidth.
        runSpeedPhase(
            prober, gathered, onProgress,
            probed.get(), planned.get(), healthyCount.get(),
        )

        val ranked = withContext(Dispatchers.Default) {
            Ranking.sort(gathered, SortBy.SCORE)
        }
        return ScanOutcome(
            results = ranked,
            // Every probe failing means no connection was established at all,
            // which points at the network rather than the addresses. Guarded
            // against the empty case so a cancelled scan is not reported as a
            // connectivity failure.
            everyProbeFailed = gathered.isNotEmpty() &&
                totalFailures.get() >= gathered.size,
        )
    }

    /**
     * Measures throughput on the shortlist, a few IPs at a time.
     *
     * Deliberately sequential-ish and after discovery: benchmarking at scan
     * concurrency makes every candidate look slow because they compete for one
     * radio. Results are mutated in place, so the ranking below picks the
     * measurements up.
     */
    private suspend fun runSpeedPhase(
        prober: Prober,
        gathered: List<ScanResult>,
        onProgress: suspend (ScanProgress) -> Unit,
        probed: Int,
        planned: Int,
        healthy: Int,
    ) {
        val shortlist = SpeedPhase.shortlist(gathered, config.speedTopN)
        if (shortlist.isEmpty()) return

        val gate = Semaphore(config.speedConcurrency)
        coroutineScope {
            for (result in shortlist) {
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        currentCoroutineContext().ensureActive()
                        // One controlled retry: a single failed transfer is
                        // usually transient, but retrying forever would turn a
                        // bounded stage into an unbounded one.
                        repeat(config.speedRetries + 1) { attempt ->
                            if (attempt > 0 && result.throughputBps > 0) return@repeat
                            prober.measureSpeed(result, config.speedTestBytes)
                        }
                        deliver {
                            onProgress(ScanProgress(probed, planned, healthy, result.ip))
                        }
                    }
                }
            }
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
        totalFailures: AtomicInteger,
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

        // A probe where no attempt ever connected. Distinguishes a dead network
        // from a network where Cloudflare simply is not reachable on that IP.
        if (r.successes == 0) totalFailures.incrementAndGet()

        // A healthy hit is rare and important, so it is never throttled away.
        if (r.isHealthy()) {
            healthyCount.incrementAndGet()
            deliver { onResult(r) }
        }

        // Progress is throttled: this fires hundreds of times per second
        // otherwise and starves the main thread.
        val now = System.currentTimeMillis()
        val previous = lastProgressAt.get()
        val total = planned.get()
        val isLast = done >= total
        if (isLast || now - previous >= config.progressThrottleMs) {
            if (lastProgressAt.compareAndSet(previous, now)) {
                deliver { onProgress(ScanProgress(done, total, healthyCount.get(), ip)) }
            }
        }
        return r
    }

    /**
     * Runs a UI callback on the main thread, absorbing its failures.
     *
     * These callbacks touch views, so they can throw for reasons that have
     * nothing to do with scanning — an adapter updated as the Activity finishes,
     * for instance. Unguarded, such an exception propagated out of the enclosing
     * `coroutineScope` and cancelled every other probe, killing the whole scan
     * because one UI update went wrong.
     */
    private suspend inline fun deliver(crossinline block: suspend () -> Unit) {
        try {
            withContext(Dispatchers.Main) { block() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // A failed UI update must not stop the scan.
        }
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
     * Returns addresses spread geometrically around [ip], staying inside
     * Cloudflare's ranges.
     */
    private fun neighborsOf(ip: String): List<String> {
        val base = CloudflareRanges.ipToLong(ip)
        val out = mutableListOf<String>()
        for (delta in NeighborStrategy.offsets(config.neighborRadius, config.neighborPerHit)) {
            val candidate = base + delta
            if (candidate <= 0) continue
            if (CloudflareRanges.isCloudflare(candidate, nets)) {
                out.add(CloudflareRanges.longToIp(candidate))
            }
        }
        return out
    }
}
