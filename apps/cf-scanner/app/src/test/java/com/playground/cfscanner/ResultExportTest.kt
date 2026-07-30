package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the clipboard export format.
 *
 * The Copy button must yield a bare list of addresses — one IP per line, best
 * first, nothing else — so it can be pasted straight into a proxy client config
 * without hand-editing.
 */
class ResultExportTest {

    private fun result(ip: String, lat: List<Long>, colo: String = "FRA") =
        ScanResult(ip = ip, port = 443).apply {
            latencies.addAll(lat)
            tlsOk = true
            stableOk = true
            wsOk = true
            httpStatus = 200
            this.colo = colo
        }

    @Test
    fun exportContainsOnlyIpsOnePerLine() {
        val items = listOf(
            result("104.16.0.1", listOf(30, 32, 31)),
            result("172.64.0.9", listOf(120, 125, 122)),
            result("188.114.96.7", listOf(210, 215, 212)),
        )
        val lines = ResultExport.ipList(items).lines()

        assertEquals("one line per result, no extras", 3, lines.size)
        assertEquals(listOf("104.16.0.1", "172.64.0.9", "188.114.96.7"), lines)

        // Every line must be nothing but a dotted-quad address.
        val ipOnly = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        for (line in lines) {
            assertTrue("line is not a bare IP: '$line'", ipOnly.matches(line))
        }
    }

    @Test
    fun exportCarriesNoCommentsHeadersOrMetrics() {
        val text = ResultExport.ipList(listOf(result("104.16.0.1", listOf(90, 95, 92))))

        assertFalse("comment marker leaked into export", text.contains("#"))
        assertFalse("latency leaked into export", text.contains("ms"))
        assertFalse("score leaked into export", text.contains("/100"))
        assertFalse("loss leaked into export", text.contains("%"))
        assertFalse("colo leaked into export", text.contains("FRA"))
        assertFalse("separator leaked into export", text.contains("|"))
        assertEquals("104.16.0.1", text)
    }

    @Test
    fun exportPreservesRankedOrderBestFirst() {
        val best = result("104.16.0.2", listOf(30, 31, 30), colo = "IST")
        val middle = result("104.16.0.3", listOf(150, 152, 151), colo = "CDG")
        val worst = result("104.16.0.4", listOf(400, 480, 430), colo = "SYD")

        // Feed them in deliberately shuffled, then export in ranked order.
        val ranked = Ranking.sort(listOf(worst, best, middle), SortBy.SCORE)
        val lines = ResultExport.ipList(ranked).lines()

        assertEquals("export order must match the ranking", ranked.map { it.ip }, lines)
        assertEquals("best result must come first", "104.16.0.2", lines.first())
    }

    @Test
    fun exportOfEmptyListIsEmpty() {
        assertEquals("", ResultExport.ipList(emptyList()))
    }

    @Test
    fun exportHasNoTrailingNewline() {
        val items = listOf(
            result("104.16.0.1", listOf(30, 32, 31)),
            result("104.16.0.2", listOf(40, 42, 41)),
        )
        val text = ResultExport.ipList(items)
        assertFalse("a trailing newline adds a bogus empty entry", text.endsWith("\n"))
        assertEquals(2, text.lines().size)
        // No blank lines anywhere.
        assertTrue("export must not contain blank lines", text.lines().none { it.isBlank() })
    }

    @Test
    fun exportOfSingleResultIsJustThatIp() {
        assertEquals("172.64.5.5", ResultExport.ipList(listOf(result("172.64.5.5", listOf(50, 51, 50)))))
    }
}
