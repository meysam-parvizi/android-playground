package com.playground.cfscanner

/**
 * Diversified neighbor offsets around a clean IPv4 address.
 *
 * Geometric spacing explores more of the local prefix with the same number of
 * probes than contiguous ±1, ±2, ±3… expansion. The global ScanEngine budget
 * remains the hard cap.
 */
object NeighborStrategy {
    fun offsets(radius: Int, limit: Int): List<Int> {
        if (radius <= 0 || limit <= 0) return emptyList()

        val out = ArrayList<Int>(limit)
        var distance = 1
        while (distance <= radius && out.size < limit) {
            out += distance
            if (out.size < limit) out += -distance
            if (distance > radius / 2) break // doubling again would exceed radius
            distance *= 2
        }
        return out
    }
}
