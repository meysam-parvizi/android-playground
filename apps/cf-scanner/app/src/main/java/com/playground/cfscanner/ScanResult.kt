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
    /**
     * Whether a payload download was attempted at all.
     *
     * Needed to distinguish "not tested" from "tested and failed": an IP that
     * completes every handshake but stalls the moment real bytes flow is useless,
     * and only a failed transfer reveals it.
     */
    var downloadTested: Boolean = false,
    /**
     * Cloudflare's own view of the connection, from `Server-Timing`.
     *
     * Optional and best-effort — the headers are undocumented. When present it
     * gives real TCP loss and retransmit counts plus a server-side RTT, all of
     * which are more trustworthy than what a handful of client samples can infer.
     */
    var edge: EdgeTiming? = null,
) {

    val attempts: Int get() = latencies.size
    val successes: Int get() = latencies.count { it > 0 }

    /**
     * Mean latency over successful attempts only.
     *
     * The edge's own processing time is subtracted when Cloudflare reported it,
     * so the figure reflects network round-trip rather than round-trip plus
     * however long the edge spent thinking.
     */
    fun avgMs(): Long {
        val ok = latencies.filter { it > 0 }
        if (ok.isEmpty()) return 0
        val raw = ok.sum() / ok.size
        val serverSide = edge?.edgeDurationMs ?: 0
        // Never report below 1 ms: a measurement cannot be instantaneous, and
        // clamping avoids a nonsensical 0 that would read as "failed".
        return (raw - serverSide).coerceAtLeast(1)
    }

    fun minMs(): Long = latencies.filter { it > 0 }.minOrNull() ?: 0

    /**
     * Latency consistency.
     *
     * Prefers Cloudflare's `rtt_var`, which the server's TCP stack maintains over
     * the whole connection, to a standard deviation computed from three client
     * samples. Falls back to the client figure when the header is absent.
     */
    fun jitterMs(): Long = edge?.rttVarMs ?: clientJitterMs()

    /** Standard deviation of successful latencies, measured client-side. */
    fun clientJitterMs(): Long {
        val ok = latencies.filter { it > 0 }
        if (ok.size < 2) return 0
        val mean = ok.average()
        val variance = ok.sumOf { (it - mean) * (it - mean) } / ok.size
        return sqrt(variance).toLong()
    }

    /**
     * Packet loss as a percentage (0..100).
     *
     * Client-side loss is coarse: with three attempts it can only be 0, 33, 66 or
     * 100. When Cloudflare reports segment-level `lost`/`retrans` counts, those
     * are blended in so a connection that completed every attempt but retransmitted
     * heavily is not scored as flawless.
     */
    fun loss(): Double {
        val attemptLoss =
            if (latencies.isEmpty()) 100.0
            else (latencies.count { it == 0L }.toDouble() / latencies.size) * 100.0

        val e = edge ?: return attemptLoss
        val lost = e.lost ?: 0
        val retrans = e.retrans ?: 0
        if (lost == 0 && retrans == 0) return attemptLoss

        // Retransmissions are a weaker signal than outright loss, so they count
        // for less. Each event adds a few percent, capped so server telemetry can
        // never by itself mark an otherwise working IP as unusable.
        val penalty = (lost * 4.0 + retrans * 2.0).coerceAtMost(30.0)
        return (attemptLoss + penalty).coerceAtMost(100.0)
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
        // If a payload transfer was attempted, it had to actually move bytes.
        // Some IPs complete every handshake and answer /cdn-cgi/trace, then stall
        // the moment real data flows — a handshake-only probe cannot see that.
        if (downloadTested && throughputBps <= 0) return false
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

    /**
     * Quality band for the UI.
     *
     * Returns a string resource rather than text so the label follows the
     * selected language. Returning a literal here was what made the grade the
     * one part of a result row that stayed Persian in an English UI.
     */
    fun gradeRes(): Int = when (score()) {
        in 90..100 -> R.string.grade_excellent
        in 75..89 -> R.string.grade_good
        in 55..74 -> R.string.grade_fair
        in 1..54 -> R.string.grade_weak
        else -> R.string.grade_unhealthy
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

/**
 * Ordering strategies offered in the UI.
 *
 * Carries a string resource rather than a label, so the sort names follow the
 * selected language. A literal here previously left the sort dropdown in Persian
 * regardless of the chosen UI language.
 */
enum class SortBy(val labelRes: Int) {
    SCORE(R.string.sort_score),
    LATENCY(R.string.sort_latency),
    JITTER(R.string.sort_jitter),
    LOSS(R.string.sort_loss),
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
