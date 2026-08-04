package com.playground.cfscanner

/**
 * Cloudflare's own view of the connection, taken from `Server-Timing` headers.
 *
 * Cloudflare's speed endpoints return two things we cannot measure from the
 * client side:
 *
 *  - `cfSpeedEdge;dur=N` — milliseconds the edge spent handling the request.
 *    Subtracting it turns a round-trip figure into actual network latency.
 *  - `cfL4;desc="?proto=TCP&rtt=…&min_rtt=…&rtt_var=…&lost=…&retrans=…"` — the
 *    server's TCP-stack telemetry for this very connection.
 *
 * That second header is the valuable one. Client-side loss can only be inferred
 * coarsely — with three attempts it is 0%, 33%, 66% or 100% and nothing between.
 * `lost` and `retrans` are real segment counts from the kernel that actually
 * carried the traffic, and `rtt_var` is a jitter measurement taken at the far
 * end rather than derived from a handful of samples.
 *
 * All fields are optional: the headers are undocumented, and a response may omit
 * them or change shape at any time. Nothing here is required for a scan to work.
 */
data class EdgeTiming(
    /** Milliseconds the edge spent processing, from `cfSpeedEdge;dur=`. */
    val edgeDurationMs: Long? = null,
    /** Smoothed RTT in microseconds, as seen by the server. */
    val rttUs: Long? = null,
    /** Minimum observed RTT in microseconds — the cleanest latency signal. */
    val minRttUs: Long? = null,
    /** RTT variance in microseconds; the server's own jitter measure. */
    val rttVarUs: Long? = null,
    /** TCP segments the server considered lost. */
    val lost: Int? = null,
    /** TCP segments the server retransmitted. */
    val retrans: Int? = null,
    /** Estimated delivery rate in bytes/sec. */
    val deliveryRateBps: Long? = null,
) {
    val minRttMs: Long? get() = minRttUs?.let { it / 1000 }
    val rttVarMs: Long? get() = rttVarUs?.let { it / 1000 }

    /** True when the server reported any loss or retransmission. */
    val hadLossOrRetrans: Boolean
        get() = (lost ?: 0) > 0 || (retrans ?: 0) > 0

    /** True when at least one useful field was parsed. */
    val hasAnything: Boolean
        get() = edgeDurationMs != null ||
            rttUs != null ||
            minRttUs != null ||
            rttVarUs != null ||
            lost != null ||
            retrans != null ||
            deliveryRateBps != null
}

/**
 * Parses Cloudflare `Server-Timing` headers.
 *
 * Deliberately lenient: these headers are undocumented, so every field is
 * optional and a malformed value is skipped rather than failing the parse. A
 * scan must keep working if Cloudflare changes the format tomorrow.
 */
object ServerTimingParser {

    /**
     * Parses one or more `Server-Timing` header values.
     *
     * A response carries the header twice — once for `cfSpeedEdge`/`cfSpeedWorker`
     * and once for `cfL4` — so callers should pass every occurrence.
     */
    fun parse(headerValues: List<String>): EdgeTiming {
        var edgeMs: Long? = null
        var rtt: Long? = null
        var minRtt: Long? = null
        var rttVar: Long? = null
        var lost: Int? = null
        var retrans: Int? = null
        var rate: Long? = null

        for (raw in headerValues) {
            // cfSpeedEdge;dur=7
            edgeDuration(raw)?.let { edgeMs = it }

            // cfL4;desc="?proto=TCP&rtt=4024&min_rtt=3979&..."
            l4Params(raw).forEach { (k, v) ->
                when (k) {
                    "rtt" -> v.toLongOrNull()?.let { rtt = it }
                    "min_rtt" -> v.toLongOrNull()?.let { minRtt = it }
                    "rtt_var" -> v.toLongOrNull()?.let { rttVar = it }
                    "lost" -> v.toIntOrNull()?.let { lost = it }
                    "retrans" -> v.toIntOrNull()?.let { retrans = it }
                    "delivery_rate" -> v.toLongOrNull()?.let { rate = it }
                }
            }
        }

        return EdgeTiming(
            edgeDurationMs = edgeMs,
            rttUs = rtt,
            minRttUs = minRtt,
            rttVarUs = rttVar,
            lost = lost,
            retrans = retrans,
            deliveryRateBps = rate,
        )
    }

    /** Extracts the `dur=` value of the `cfSpeedEdge` metric, in milliseconds. */
    private fun edgeDuration(header: String): Long? {
        val idx = header.indexOf("cfSpeedEdge", ignoreCase = true)
        if (idx < 0) return null
        // Read the dur= that belongs to this metric, i.e. before the next comma.
        val segment = header.substring(idx).substringBefore(',')
        val dur = DUR.find(segment)?.groupValues?.getOrNull(1) ?: return null
        // Values can be fractional ("dur=6.5"); round to whole milliseconds.
        return dur.toDoubleOrNull()?.toLong()
    }

    /** Extracts the key/value pairs from the `cfL4` description string. */
    private fun l4Params(header: String): Map<String, String> {
        val idx = header.indexOf("cfL4", ignoreCase = true)
        if (idx < 0) return emptyMap()
        val desc = DESC.find(header.substring(idx))?.groupValues?.getOrNull(1) ?: return emptyMap()
        return desc.trimStart('?').split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.substring(0, i) to pair.substring(i + 1)
        }.toMap()
    }

    private val DUR = Regex("""dur=([0-9.]+)""", RegexOption.IGNORE_CASE)
    private val DESC = Regex("""desc="([^"]*)"""", RegexOption.IGNORE_CASE)
}
