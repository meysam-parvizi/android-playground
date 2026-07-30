package com.playground.cfscanner

/**
 * Official Cloudflare IPv4 ranges (https://www.cloudflare.com/ips-v4).
 *
 * These are the only ranges worth scanning: an IP outside them cannot be a
 * Cloudflare edge, so probing it is wasted time.
 */
object CloudflareRanges {

    val V4: List<String> = listOf(
        "173.245.48.0/20",
        "103.21.244.0/22",
        "103.22.200.0/22",
        "103.31.4.0/22",
        "141.101.64.0/18",
        "108.162.192.0/18",
        "190.93.240.0/20",
        "188.114.96.0/20",
        "197.234.240.0/22",
        "198.41.128.0/17",
        "162.158.0.0/15",
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "131.0.72.0/22",
    )

    /**
     * Ranges that tend to behave best from Iranian ISPs.
     *
     * Not a guarantee — DPI behaviour shifts constantly — but these blocks
     * historically carry more reachable edges, so the scanner biases sampling
     * toward them to find working IPs faster.
     */
    val V4_PREFERRED: List<String> = listOf(
        "104.16.0.0/13",
        "104.24.0.0/14",
        "172.64.0.0/13",
        "162.158.0.0/15",
        "188.114.96.0/20",
        "141.101.64.0/18",
    )

    /** Parsed CIDR: network base as unsigned int plus prefix length. */
    data class Cidr(val base: Long, val prefix: Int) {
        val size: Long get() = 1L shl (32 - prefix)

        fun contains(ip: Long): Boolean {
            val mask = maskFor(prefix)
            return (ip and mask) == (base and mask)
        }

        /** Returns a uniformly random address inside this block. */
        fun randomIp(rnd: java.util.Random): Long {
            val span = size
            // Skip the network address itself for /31 and wider blocks.
            val offset = if (span > 2) 1 + (rnd.nextDouble() * (span - 2)).toLong() else 0
            return base + offset
        }

        companion object {
            fun maskFor(prefix: Int): Long =
                if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        }
    }

    fun parse(cidr: String): Cidr {
        val (addr, prefixStr) = cidr.split("/")
        return Cidr(ipToLong(addr), prefixStr.toInt())
    }

    fun parseAll(cidrs: List<String> = V4): List<Cidr> = cidrs.map { parse(it) }

    fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        require(parts.size == 4) { "not an IPv4 address: $ip" }
        var out = 0L
        for (p in parts) {
            val octet = p.toInt()
            require(octet in 0..255) { "octet out of range in $ip" }
            out = (out shl 8) or octet.toLong()
        }
        return out
    }

    fun longToIp(value: Long): String {
        val v = value and 0xFFFFFFFFL
        return "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
    }

    /** True when [ip] falls inside any official Cloudflare range. */
    fun isCloudflare(ip: Long, nets: List<Cidr> = parseAll()): Boolean =
        nets.any { it.contains(ip) }
}
