package com.playground.cfscanner

/**
 * Ordered SNI fallback for Cloudflare trace probes.
 *
 * Every candidate tries the same small, known trace-capable set, but the starting
 * point is derived from the IP so all scanners do not emit one global hostname
 * fingerprint. Later attempts rotate the start again.
 */
object SniStrategy {
    val TRACE_HOSTS: List<String> = listOf(
        "speed.cloudflare.com",
        "www.cloudflare.com",
        "cloudflare.com",
    )

    fun order(ip: String, attemptIndex: Int): List<String> {
        val start = ((stableHash(ip) + attemptIndex.toLong()) % TRACE_HOSTS.size).toInt()
        return List(TRACE_HOSTS.size) { offset ->
            TRACE_HOSTS[(start + offset) % TRACE_HOSTS.size]
        }
    }

    /** Unsigned FNV-1a, kept positive so modulo is well-defined. */
    private fun stableHash(text: String): Long {
        var hash = 0x811C9DC5L
        for (c in text) {
            hash = hash xor c.code.toLong()
            hash = (hash * 0x01000193L) and 0xFFFF_FFFFL
        }
        return hash
    }
}
