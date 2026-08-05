package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detailed export is always English and always ASCII digits.
 *
 * A saved file outlives the session and gets pasted into issues, configs and
 * spreadsheets, so it must not depend on which language the UI happened to be
 * in. Persian digits in particular would break any tool that parses it.
 */
class ResultExportDetailedTest {

    private fun result(
        ip: String,
        rttUs: Long = 30_000,
        colo: String = "VIE",
        speed: Long = 0,
        benchBytes: Long = 0,
    ) = ScanResult(ip = ip, port = 443).apply {
        repeat(3) {
            recordAttempt(
                AttemptResult(
                    sni = "speed.cloudflare.com",
                    tcpConnectMs = 30,
                    tlsRequired = true,
                    tlsOk = true,
                    httpStatus = 200,
                    colo = colo,
                    stabilityOk = true,
                    edge = EdgeTiming(rttUs = rttUs),
                ),
            )
        }
        wsOk = true
        if (speed > 0) recordBenchmark(bytes = benchBytes, bps = speed)
    }

    // --- language independence ---------------------------------------------

    @Test
    fun theOutputIsIdenticalWhicheverLanguageTheUiIsIn() {
        val results = listOf(result("104.16.1.1"), result("104.16.1.2"))

        Format.setLocale(LocaleRegistry.byTag("fa")!!)
        val persian = ResultExport.detailed(results)
        Format.setLocale(LocaleRegistry.byTag("en")!!)
        val english = ResultExport.detailed(results)
        Format.setLocale(LocaleRegistry.DEFAULT)

        assertEquals("a saved file must not follow the UI language", english, persian)
    }

    @Test
    fun everyDigitIsAscii() {
        Format.setLocale(LocaleRegistry.byTag("fa")!!)
        val text = ResultExport.detailed(listOf(result("104.16.1.1", speed = 2_000_000, benchBytes = 512 * 1024)))
        Format.setLocale(LocaleRegistry.DEFAULT)

        val persianDigits = text.filter { it in '\u06F0'..'\u06F9' }
        assertTrue("found Persian digits: $persianDigits", persianDigits.isEmpty())
    }

    @Test
    fun noBidiControlCharactersLeakIntoTheFile() {
        // The UI wraps values in FSI/PDI to survive RTL layout. Those are
        // invisible in a text editor but corrupt anything that parses the file.
        val text = ResultExport.detailed(listOf(result("104.16.1.1")))
        for (ch in listOf('\u2068', '\u2069', '\u200F', '\u200E')) {
            assertFalse("control U+%04X leaked".format(ch.code), text.contains(ch))
        }
    }

    // --- structure ----------------------------------------------------------

    @Test
    fun itIsATableWithAHeaderRow() {
        val text = ResultExport.detailed(listOf(result("104.16.1.1")))
        val lines = text.lines()

        val header = lines.first { it.startsWith("rank") }
        for (column in listOf("rank", "ip", "score", "grade", "ping_ms", "jitter_ms", "loss_pct", "datacenter")) {
            assertTrue("header is missing $column", header.contains(column))
        }
    }

    @Test
    fun oneRowPerResultInTheGivenOrder() {
        val results = listOf(result("104.16.1.1"), result("104.16.1.2"), result("104.16.1.3"))
        val rows = ResultExport.detailed(results).lines().filter { it.startsWith("1\t") || it.startsWith("2\t") || it.startsWith("3\t") }

        assertEquals(3, rows.size)
        assertTrue(rows[0].contains("104.16.1.1"))
        assertTrue(rows[1].contains("104.16.1.2"))
        assertTrue(rows[2].contains("104.16.1.3"))
    }

    @Test
    fun rankIsOneBased() {
        val text = ResultExport.detailed(listOf(result("104.16.1.1")))
        assertTrue("ranks shown to the user start at 1", text.contains("1\t104.16.1.1"))
    }

    @Test
    fun anUnmeasuredSpeedIsADashNotAZero() {
        // Zero would read as "measured, and it was nothing".
        val text = ResultExport.detailed(listOf(result("104.16.1.1")))
        val row = text.lines().first { it.startsWith("1\t") }
        assertTrue("expected a dash for unmeasured speed in: $row", row.contains("\t-"))
    }

    @Test
    fun aMeasuredSpeedIsReportedInMbps() {
        val text = ResultExport.detailed(
            listOf(result("104.16.1.1", speed = 2_000_000, benchBytes = 512 * 1024)),
        )
        val row = text.lines().first { it.startsWith("1\t") }
        assertTrue("expected 16.0 Mbps in: $row", row.contains("16.0"))
    }

    @Test
    fun theHeaderRecordsWhenAndWhatWasScanned() {
        val text = ResultExport.detailed(listOf(result("104.16.1.1")))
        assertTrue(text.contains("CF Scanner"))
        assertTrue("the count makes a truncated file obvious", text.contains("results: 1"))
    }

    @Test
    fun anEmptyListStillProducesAValidFile() {
        val text = ResultExport.detailed(emptyList())
        assertTrue(text.contains("results: 0"))
        assertFalse(text.endsWith("\n\n"))
    }

    @Test
    fun theExportedGradeBandsMatchTheUiSource() {
        // The grade names are duplicated in ResultExport because the file must
        // stay English while gradeRes() follows the language. Duplication drifts,
        // so compare the two `when` tables as written: restating the boundaries
        // in this test would only assert that I typed them the same way twice.
        val bands = Regex("in (\\d+)\\.\\.(\\d+) ->")

        val uiBands = bands.findAll(
            java.io.File("src/main/java/com/playground/cfscanner/ScanResult.kt").readText()
                .substringAfter("fun gradeRes()").substringBefore("}"),
        ).map { it.groupValues[1] to it.groupValues[2] }.toList()

        val exportBands = bands.findAll(
            java.io.File("src/main/java/com/playground/cfscanner/ResultExport.kt").readText()
                .substringAfter("private fun grade(").substringBefore("}"),
        ).map { it.groupValues[1] to it.groupValues[2] }.toList()

        assertTrue("expected to find the UI's grade bands", uiBands.isNotEmpty())
        assertEquals("exported grades must use the same score bands", uiBands, exportBands)
    }

    @Test
    fun theFileParsesBackIntoTheSameValues() {
        // The point of the format is that a tool can read it. Parse it back and
        // compare against the source objects, rather than trusting that a header
        // and some tabs are enough.
        val results = listOf(
            result("104.16.1.1", rttUs = 23_000, colo = "VIE", speed = 2_000_000, benchBytes = 512 * 1024),
            result("104.16.1.2", rttUs = 45_000, colo = "FRA"),
        )

        val lines = ResultExport.detailed(results).lines()
        val header = lines.first { !it.startsWith("#") && it.isNotBlank() }.split("\t")
        val rows = lines.dropWhile { it != header.joinToString("\t") }.drop(1)
            .filter { it.isNotBlank() }
            .map { header.zip(it.split("\t")).toMap() }

        assertEquals(2, rows.size)
        assertEquals(results.map { it.ip }, rows.map { it["ip"] })
        assertEquals(listOf("1", "2"), rows.map { it["rank"] })
        assertEquals(listOf("VIE", "FRA"), rows.map { it["datacenter"] })
        assertEquals(
            results.map { it.avgMs().toString() },
            rows.map { it["ping_ms"] },
        )
        assertEquals("16.0", rows[0]["speed_mbps"])
        assertEquals("-", rows[1]["speed_mbps"])
        // Every row must have a value in every column, or a parser silently
        // shifts fields.
        for (row in rows) assertEquals(header.size, row.size)
    }

    // --- the plain mode is unchanged ---------------------------------------

    @Test
    fun thePlainModeIsStillJustAddresses() {
        val text = ResultExport.ipList(listOf(result("104.16.1.1"), result("104.16.1.2")))
        assertEquals("104.16.1.1\n104.16.1.2", text)
    }
}
