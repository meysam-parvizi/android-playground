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
}
