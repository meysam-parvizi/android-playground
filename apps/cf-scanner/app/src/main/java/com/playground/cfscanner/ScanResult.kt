package com.playground.cfscanner

import kotlin.math.sqrt

/**
 * Measurement outcome for a single candidate IP.
 *
 * A failed attempt records latency 0, so a timeout can never be mistaken for a
 * fast response.
 */
data class ScanResult(
    val ip: String,
    val port: Int,
    /** Per-attempt latency in milliseconds; 0 means that attempt failed. */
    val latencies: MutableList<Long> = mutableListOf(),
    var tlsOk: Boolean = false,
    /** Connection survived an idle hold without being reset by DPI. */
    var stableOk: Boolean = false,
    /** A WebSocket upgrade reached the Cloudflare edge (VLESS/Trojan carry). */
    var wsOk: Boolean = false,
    var httpStatus: Int = 0,
    /** Cloudflare datacenter code, e.g. FRA, AMS, IST. */
    var colo: String = "",
    /** Download throughput in bytes/sec; 0 when not measured. */
    var throughputBps: Long = 0,
) {

    val attempts: Int get() = latencies.size
    val successes: Int get() = latencies.count { it > 0 }

    /** Packet loss as a percentage (0..100). */
    fun loss(): Double =
        if (latencies.isEmpty()) 100.0
        else (latencies.count { it == 0L }.toDouble() / latencies.size) * 100.0

    /** Mean latency over successful attempts only. */
    fun avgMs(): Long {
        val ok = latencies.filter { it > 0 }
        return if (ok.isEmpty()) 0 else ok.sum() / ok.size
    }

    fun minMs(): Long = latencies.filter { it > 0 }.minOrNull() ?: 0

    /** Standard deviation of successful latencies — connection steadiness. */
    fun jitterMs(): Long {
        val ok = latencies.filter { it > 0 }
        if (ok.size < 2) return 0
        val mean = ok.average()
        val variance = ok.sumOf { (it - mean) * (it - mean) } / ok.size
        return sqrt(variance).toLong()
    }

    /**
     * A result is only usable if it proved it can actually carry traffic.
     *
     * Deliberately strict: on Iranian ISPs an IP frequently completes a TLS
     * handshake and answers /cdn-cgi/trace, then gets reset moments later. Such
     * an IP is useless in practice, so the stability hold is mandatory.
     */
    fun isHealthy(): Boolean {
        if (attempts < 2 || successes < 2) return false
        if (loss() >= 50.0) return false
        if (avgMs() <= 0) return false
        if (port != 80 && !tlsOk) return false
        if (httpStatus !in 200..399) return false
        if (colo.isEmpty()) return false
        // The decisive check for Iran: survived the idle hold.
        if (!stableOk) return false
        return true
    }

    /**
     * Quality score from 0 to 100 — higher is better.
     *
     * The reference scanner sorted on a single field. That ranks an IP with
     * 50 ms latency above one with 200 ms even when the fast one drops every
     * other connection. Iranian ISPs make that trade-off backwards, so this
     * score weights **stability and consistency above raw speed**:
     *
     *   - stability  35%  survived idle hold + WebSocket carry
     *   - loss       25%  packet loss across attempts
     *   - jitter     20%  latency consistency
     *   - latency    15%  average round-trip
     *   - locality    5%  geographic closeness of the colo
     *
     * Thresholds are deliberately strict: anything above ~20% loss, ~200 ms
     * jitter, or ~500 ms latency is unusable in practice, so those bands score
     * zero rather than merely low. Without that, every IP that cleared the
     * health gate bunched up in the 90s and the grades stopped meaning anything.
     */
    fun score(): Int {
        if (!isHealthy()) return 0

        // Stability (35): passing the idle hold earns the base, WS carry the rest.
        var stability = 20.0
        if (wsOk) stability += 15.0

        // Loss (25): 0% scores full marks, 20% or worse scores nothing.
        val lossScore = (1.0 - (loss() / 20.0)).coerceIn(0.0, 1.0) * 25.0

        // Jitter (20): <=15 ms is excellent, >=200 ms is unusable.
        val j = jitterMs().toDouble()
        val jitterScore = when {
            j <= 15 -> 20.0
            j >= 200 -> 0.0
            else -> (1.0 - ((j - 15) / 185.0)) * 20.0
        }

        // Latency (15): <=60 ms excellent, >=500 ms scores nothing.
        val a = avgMs().toDouble()
        val latencyScore = when {
            a <= 60 -> 15.0
            a >= 500 -> 0.0
            else -> (1.0 - ((a - 60) / 440.0)) * 15.0
        }

        // Locality (5): nearer Cloudflare datacenters behave better from Iran.
        val localityScore = coloScore(colo) * 5.0

        return (stability + lossScore + jitterScore + latencyScore + localityScore)
            .coerceIn(0.0, 100.0)
            .toInt()
    }

    /** Human-readable grade for the UI. */
    fun grade(): String = when (score()) {
        in 90..100 -> "عالی"
        in 75..89 -> "خوب"
        in 55..74 -> "متوسط"
        in 1..54 -> "ضعیف"
        else -> "ناسالم"
    }

    companion object {
        /**
         * Cloudflare datacenters ranked by how well they usually serve Iran.
         *
         * Nearby European and Middle-Eastern colos give far better real-world
         * results than North-American ones, even when raw latency looks similar
         * during a short probe.
         */
        private val NEAR_COLOS = setOf("IST", "FRA", "AMS", "VIE", "DXB", "BAH", "KWI", "DOH", "MCT", "TBS", "EVN", "BAK")
        private val MID_COLOS = setOf("CDG", "MRS", "MXP", "MUC", "WAW", "PRG", "BUD", "OTP", "SOF", "ATH", "LHR", "DUB", "MAD", "BCN", "ZRH", "BEG", "ZAG", "RIX", "HEL", "ARN", "CPH", "OSL")

        fun coloScore(colo: String): Double = when (colo.uppercase()) {
            in NEAR_COLOS -> 1.0
            in MID_COLOS -> 0.6
            "" -> 0.0
            else -> 0.2 // far colos (US, APAC) — usually worse for Iran
        }
    }
}

/** Ordering strategies offered in the UI. */
enum class SortBy(val label: String) {
    SCORE("امتیاز کلی"),
    LATENCY("کمترین پینگ"),
    JITTER("کمترین نوسان"),
    LOSS("کمترین پکت‌لاس"),
}

object Ranking {

    /**
     * Sorts results best-first. Healthy results always outrank unhealthy ones,
     * regardless of the chosen criterion — a fast but broken IP is still broken.
     */
    fun sort(results: List<ScanResult>, by: SortBy): List<ScanResult> {
        val healthyFirst = compareByDescending<ScanResult> { it.isHealthy() }
        val comparator = when (by) {
            SortBy.SCORE -> healthyFirst
                .thenByDescending { it.score() }
                .thenBy { it.avgMs() }
            SortBy.LATENCY -> healthyFirst
                .thenBy { if (it.avgMs() == 0L) Long.MAX_VALUE else it.avgMs() }
                .thenByDescending { it.score() }
            SortBy.JITTER -> healthyFirst
                .thenBy { it.jitterMs() }
                .thenByDescending { it.score() }
            SortBy.LOSS -> healthyFirst
                .thenBy { it.loss() }
                .thenByDescending { it.score() }
        }
        return results.sortedWith(comparator.thenBy { it.ip })
    }

    fun healthy(results: List<ScanResult>): List<ScanResult> = results.filter { it.isHealthy() }
}
