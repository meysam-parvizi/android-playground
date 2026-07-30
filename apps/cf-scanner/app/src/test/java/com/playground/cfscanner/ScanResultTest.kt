package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanResultTest {

    /** Builds a result that passes every health gate. */
    private fun healthy(
        ip: String = "104.16.0.1",
        lat: List<Long> = listOf(90, 95, 100),
        colo: String = "FRA",
        ws: Boolean = true,
    ) = ScanResult(ip = ip, port = 443).apply {
        latencies.addAll(lat)
        tlsOk = true
        stableOk = true
        wsOk = ws
        httpStatus = 200
        this.colo = colo
    }

    @Test
    fun healthyResultIsRecognised() {
        assertTrue(healthy().isHealthy())
        assertTrue(healthy().score() > 0)
    }

    @Test
    fun failedAttemptsCountAsLossNotAsSpeed() {
        val r = ScanResult("1.1.1.1", 443).apply { latencies.addAll(listOf(0L, 0L, 100L)) }
        // A zero must never be averaged in as a fast response.
        assertEquals(100L, r.avgMs())
        assertEquals(66, r.loss().toInt())
    }

    @Test
    fun unstableIpIsRejectedEvenWhenFast() {
        // This is the core Iran-specific rule: a fast IP that DPI resets is useless.
        val r = healthy(lat = listOf(20, 22, 25)).apply { stableOk = false }
        assertFalse("an IP that fails the idle hold must not be healthy", r.isHealthy())
        assertEquals(0, r.score())
    }

    @Test
    fun missingColoIsRejected() {
        assertFalse(healthy(colo = "").isHealthy())
    }

    @Test
    fun highLossIsRejected() {
        val r = healthy(lat = listOf(0, 0, 0, 100))
        assertFalse(r.isHealthy())
    }

    @Test
    fun stableSlowIpOutranksUnstableFastIp() {
        val stableSlow = healthy(ip = "104.16.0.2", lat = listOf(250, 260, 255))
        val unstableFast = healthy(ip = "104.16.0.3", lat = listOf(30, 31, 30)).apply { stableOk = false }

        val ranked = Ranking.sort(listOf(unstableFast, stableSlow), SortBy.SCORE)
        assertEquals(
            "a stable slow IP must rank above an unstable fast one",
            "104.16.0.2", ranked.first().ip,
        )
    }

    @Test
    fun consistentLatencyBeatsJitteryLatency() {
        val steady = healthy(ip = "104.16.0.4", lat = listOf(120, 122, 121))
        val jittery = healthy(ip = "104.16.0.5", lat = listOf(40, 400, 90))
        assertTrue(
            "low jitter should score higher than an erratic connection",
            steady.score() > jittery.score(),
        )
    }

    @Test
    fun nearColoScoresAboveFarColo() {
        val near = healthy(ip = "104.16.0.6", colo = "FRA")
        val far = healthy(ip = "104.16.0.7", colo = "LAX")
        assertTrue(near.score() >= far.score())
        assertTrue(ScanResult.coloScore("IST") > ScanResult.coloScore("LAX"))
        assertEquals(0.0, ScanResult.coloScore(""), 0.001)
    }

    @Test
    fun webSocketSupportRaisesScore() {
        val withWs = healthy(ip = "104.16.0.8", ws = true)
        val withoutWs = healthy(ip = "104.16.0.9", ws = false)
        assertTrue(withWs.score() > withoutWs.score())
    }

    @Test
    fun unhealthyAlwaysSortsBelowHealthyRegardlessOfCriterion() {
        val good = healthy(ip = "104.16.0.10", lat = listOf(200, 210, 205))
        val broken = ScanResult("104.16.0.11", 443).apply { latencies.addAll(listOf(0L, 0L)) }
        for (criterion in SortBy.entries) {
            val ranked = Ranking.sort(listOf(broken, good), criterion)
            assertEquals("healthy must lead for $criterion", "104.16.0.10", ranked.first().ip)
        }
    }

    @Test
    fun scoreStaysWithinBounds() {
        val best = healthy(lat = listOf(10, 10, 10), colo = "IST", ws = true)
        assertTrue(best.score() in 0..100)
        assertEquals(0, ScanResult("1.1.1.1", 443).score())
    }

    /**
     * Locks in the score calibration.
     *
     * Without strict thresholds every IP that cleared the health gate scored in
     * the 90s and the grades stopped distinguishing anything. These assertions
     * fail if the weights drift back toward being too generous.
     */
    @Test
    fun scoreBandsActuallyDiscriminate() {
        val excellent = healthy(ip = "104.16.1.1", lat = listOf(30, 32, 31), colo = "IST", ws = true)
        val decent = healthy(ip = "104.16.1.2", lat = listOf(200, 205, 202), colo = "PRG", ws = false)
        val marginal = healthy(ip = "104.16.1.3", lat = listOf(180, 320, 250), colo = "IAD", ws = false)
        val weak = healthy(ip = "104.16.1.4", lat = listOf(100, 780, 700), colo = "SYD", ws = false)

        // Strictly decreasing quality must produce strictly decreasing scores.
        assertTrue(
            "scores must separate: ${excellent.score()} ${decent.score()} ${marginal.score()} ${weak.score()}",
            excellent.score() > decent.score() &&
                decent.score() > marginal.score() &&
                marginal.score() > weak.score(),
        )

        // And they must land in different grade bands, not all read "عالی".
        val grades = listOf(excellent, decent, marginal, weak).map { it.grade() }
        assertEquals("each tier should get its own grade", grades.size, grades.distinct().size)
    }

    @Test
    fun heavyLossIsNotGradedWell() {
        // 25% loss is bad in practice; it must not come out as "خوب" or better.
        val lossy = healthy(ip = "104.16.1.5", lat = listOf(200, 0, 210, 205), ws = false)
        assertTrue("25% loss scored too high: ${lossy.score()}", lossy.score() < 70)
    }
}
