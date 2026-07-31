package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareRangesTest {

    @Test
    fun ipToLongAndBack() {
        val samples = listOf("1.2.3.4", "104.16.0.1", "255.255.255.255", "0.0.0.0")
        for (s in samples) {
            assertEquals(s, CloudflareRanges.longToIp(CloudflareRanges.ipToLong(s)))
        }
    }

    @Test
    fun cidrContainsOnlyItsOwnBlock() {
        val net = CloudflareRanges.parse("104.16.0.0/13")
        assertTrue(net.contains(CloudflareRanges.ipToLong("104.16.0.1")))
        assertTrue(net.contains(CloudflareRanges.ipToLong("104.23.255.254")))
        assertFalse(net.contains(CloudflareRanges.ipToLong("8.8.8.8")))
        assertFalse(net.contains(CloudflareRanges.ipToLong("104.24.0.1")))
    }

    @Test
    fun isCloudflareRecognisesRealRangesAndRejectsOthers() {
        assertTrue(CloudflareRanges.isCloudflare(CloudflareRanges.ipToLong("172.64.0.5")))
        assertTrue(CloudflareRanges.isCloudflare(CloudflareRanges.ipToLong("188.114.96.10")))
        // Google DNS and a private address are definitely not Cloudflare.
        assertFalse(CloudflareRanges.isCloudflare(CloudflareRanges.ipToLong("8.8.8.8")))
        assertFalse(CloudflareRanges.isCloudflare(CloudflareRanges.ipToLong("192.168.1.1")))
    }

    @Test
    fun randomIpAlwaysLandsInsideItsBlock() {
        val rnd = java.util.Random(42)
        for (cidr in CloudflareRanges.V4) {
            val net = CloudflareRanges.parse(cidr)
            repeat(50) {
                assertTrue("$cidr produced an out-of-range address", net.contains(net.randomIp(rnd)))
            }
        }
    }

    @Test
    fun sampledIpsAreAlwaysCloudflareOwned() {
        val engine = ScanEngine(ScanConfig(targetCount = 200))
        val sampled = engine.sampleIps(200)
        assertTrue("sampler returned too few addresses", sampled.size > 100)
        val nets = CloudflareRanges.parseAll()
        for (ip in sampled) {
            assertTrue(
                "$ip is not inside any Cloudflare range",
                CloudflareRanges.isCloudflare(CloudflareRanges.ipToLong(ip), nets),
            )
        }
    }

    /**
     * Size-weighted sampling must actually follow block size.
     *
     * Cloudflare's ranges differ in size by 512x: `104.16.0.0/13` holds 524,288
     * addresses, `131.0.72.0/22` holds 1,024. Picking a range uniformly gives each
     * address of a small block ~512x more sampling pressure — an accidental bias.
     * With weighting on, the big blocks should dominate in proportion to their size.
     */
    @Test
    fun sizeWeightedSamplingFavoursLargeBlocks() {
        // preferIranFriendlyRanges off so the whole list is in play.
        val weighted = ScanEngine(
            ScanConfig(
                targetCount = 3000,
                preferIranFriendlyRanges = false,
                sizeWeightedSampling = true,
            ),
        ).sampleIps(3000)

        val bigBlocks = listOf("104.16.0.0/13", "172.64.0.0/13", "104.24.0.0/14")
            .map { CloudflareRanges.parse(it) }
        val inBig = weighted.count { ip ->
            val v = CloudflareRanges.ipToLong(ip)
            bigBlocks.any { it.contains(v) }
        }
        // Those three blocks are ~86% of Cloudflare's IPv4 space, so with correct
        // weighting they should take the clear majority of samples.
        val share = inBig.toDouble() / weighted.size
        assertTrue(
            "size-weighted sampling put only ${(share * 100).toInt()}% in the largest blocks",
            share > 0.70,
        )
    }

    @Test
    fun uniformSamplingSpreadsAcrossBlocksRegardlessOfSize() {
        val uniform = ScanEngine(
            ScanConfig(
                targetCount = 3000,
                preferIranFriendlyRanges = false,
                sizeWeightedSampling = false,
            ),
        ).sampleIps(3000)

        val bigBlocks = listOf("104.16.0.0/13", "172.64.0.0/13", "104.24.0.0/14")
            .map { CloudflareRanges.parse(it) }
        val share = uniform.count { ip ->
            val v = CloudflareRanges.ipToLong(ip)
            bigBlocks.any { it.contains(v) }
        }.toDouble() / uniform.size

        // 3 of 15 blocks chosen uniformly is ~20%, nowhere near their 86% of space.
        assertTrue(
            "uniform sampling should not concentrate in the big blocks, got ${(share * 100).toInt()}%",
            share < 0.40,
        )
    }

    @Test
    fun bothSamplingModesStayInsideCloudflareRanges() {
        val nets = CloudflareRanges.parseAll()
        for (weighted in listOf(true, false)) {
            val ips = ScanEngine(
                ScanConfig(targetCount = 300, sizeWeightedSampling = weighted),
            ).sampleIps(300)
            assertTrue(ips.isNotEmpty())
            for (ip in ips) {
                assertTrue(
                    "$ip escaped Cloudflare's ranges (weighted=$weighted)",
                    CloudflareRanges.isCloudflare(CloudflareRanges.ipToLong(ip), nets),
                )
            }
        }
    }
}
