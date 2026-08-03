package com.playground.cfscanner

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the shared HTTP header reader.
 *
 * This logic existed three times in [Prober], written differently each time, and
 * only one copy bounded its input. The unbounded ones could be held open
 * indefinitely by a peer sending one header line at a time, because each
 * individual read stayed under the socket timeout so the timeout never fired.
 *
 * The reader must also leave the stream positioned at the body: the probe reads
 * the response body afterwards, and a buffered reader that pulled ahead would
 * swallow part of it.
 */
class HttpHeadReaderTest {

    private fun streamOf(text: String) = ByteArrayInputStream(text.toByteArray())

    @Test
    fun readsTheStatusCode() {
        val head = HttpHeadReader.read(streamOf("HTTP/1.1 200 OK\r\n\r\n"))
        assertEquals(200, head.status)
    }

    @Test
    fun readsANonOkStatus() {
        assertEquals(101, HttpHeadReader.read(streamOf("HTTP/1.1 101 Switching\r\n\r\n")).status)
        assertEquals(403, HttpHeadReader.read(streamOf("HTTP/1.1 403 Forbidden\r\n\r\n")).status)
    }

    /** A malformed status line must not throw; 0 marks it unreadable. */
    @Test
    fun anUnreadableStatusLineYieldsZero() {
        assertEquals(0, HttpHeadReader.read(streamOf("garbage\r\n\r\n")).status)
        assertEquals(0, HttpHeadReader.read(streamOf("\r\n")).status)
        assertEquals(0, HttpHeadReader.read(streamOf("")).status)
    }

    /**
     * Cloudflare sends Server-Timing more than once, and the telemetry the app
     * relies on is spread across those occurrences.
     */
    @Test
    fun collectsEveryServerTimingHeader() {
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Server: cloudflare\r\n")
            append("Server-Timing: cfSpeedEdge;dur=7\r\n")
            append("Content-Type: text/plain\r\n")
            append("server-timing: cfL4;desc=\"?proto=TCP&rtt=4024\"\r\n")
            append("\r\n")
        }
        val head = HttpHeadReader.read(streamOf(response))

        assertEquals(200, head.status)
        assertEquals(2, head.serverTimings.size)
        assertTrue(head.serverTimings[0].contains("cfSpeedEdge"))
        assertTrue(head.serverTimings[1].contains("cfL4"))
    }

    /** Header names are case-insensitive per the HTTP spec. */
    @Test
    fun matchesTheHeaderNameCaseInsensitively() {
        val response = "HTTP/1.1 200 OK\r\nSERVER-TIMING: a;dur=1\r\nServer-Timing: b;dur=2\r\n\r\n"
        assertEquals(2, HttpHeadReader.read(streamOf(response)).serverTimings.size)
    }

    @Test
    fun ignoresOtherHeaders() {
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nCF-Ray: abc\r\n\r\n"
        assertTrue(HttpHeadReader.read(streamOf(response)).serverTimings.isEmpty())
    }

    /**
     * The decisive property: the body must still be readable afterwards, since
     * the probe parses it for the colo code.
     */
    @Test
    fun leavesTheStreamPositionedAtTheBody() {
        val stream = streamOf("HTTP/1.1 200 OK\r\nServer: cloudflare\r\n\r\ncolo=VIE\nloc=AT\n")
        HttpHeadReader.read(stream)

        val body = stream.readBytes().toString(Charsets.US_ASCII)
        assertTrue("the body was consumed by the header reader: '$body'", body.startsWith("colo=VIE"))
    }

    /**
     * A peer that never sends the blank line must not hold the probe forever.
     * This is the bug the unbounded loops had.
     */
    @Test
    fun stopsAtTheByteCeilingWhenNoBlankLineArrives() {
        val endless = "HTTP/1.1 200 OK\r\n" + "X-Filler: aaaaaaaaaa\r\n".repeat(5_000)
        val head = HttpHeadReader.read(streamOf(endless), maxBytes = 2_048)

        assertEquals("the status should still have been read", 200, head.status)
        // Returning at all is the assertion: an unbounded reader would consume
        // the whole stream instead of stopping at the ceiling.
    }

    @Test
    fun stopsAtTheLineCeiling() {
        val many = "HTTP/1.1 200 OK\r\n" + "X-A: b\r\n".repeat(1_000)
        val head = HttpHeadReader.read(streamOf(many), maxLines = 10)
        assertEquals(200, head.status)
    }

    /** A response that ends mid-headers must not throw. */
    @Test
    fun handlesATruncatedResponse() {
        val head = HttpHeadReader.read(streamOf("HTTP/1.1 200 OK\r\nServer-Timing: a;dur=1"))
        assertEquals(200, head.status)
    }

    /** Bare newlines, without carriage returns, are still line terminators. */
    @Test
    fun acceptsBareNewlines() {
        val head = HttpHeadReader.read(streamOf("HTTP/1.1 200 OK\nServer-Timing: a;dur=1\n\n"))
        assertEquals(200, head.status)
        assertEquals(1, head.serverTimings.size)
    }
}
