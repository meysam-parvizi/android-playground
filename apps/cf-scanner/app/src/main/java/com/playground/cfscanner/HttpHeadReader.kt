package com.playground.cfscanner

import java.io.InputStream

/**
 * The head of an HTTP response.
 *
 * @param status the numeric status, or 0 when the status line was unreadable
 * @param serverTimings every `Server-Timing` header value, in order. Cloudflare
 *   sends the header more than once, so all occurrences are kept.
 */
data class HttpHead(
    val status: Int,
    val serverTimings: List<String>,
)

/**
 * Reads HTTP response headers from a probe connection.
 *
 * This existed twice, written differently: `verifyEdge` used a `BufferedReader`
 * while `probeDownload` hand-rolled a byte-at-a-time loop for the same job. Two
 * implementations of one parser drift, and they had: only the byte-level version
 * bounded its input, so the reader-based one could be held open indefinitely by
 * a peer trickling one header line at a time — each individual read stayed under
 * the socket timeout, so the timeout never fired.
 *
 * Reads bytes directly rather than wrapping the stream. A `BufferedReader` pulls
 * ahead into its own buffer, which would swallow part of the body and leave the
 * caller unable to read it from the raw stream afterwards.
 */
object HttpHeadReader {

    /**
     * Reads the status line and headers, stopping at the blank line that ends
     * them. The stream is left positioned at the first byte of the body.
     *
     * @param maxBytes hard ceiling on header size. Bounds the work a hostile or
     *   broken peer can cause; 16 KiB is far beyond any legitimate response.
     * @param maxLines hard ceiling on header count, for the same reason.
     */
    fun read(
        input: InputStream,
        maxBytes: Int = MAX_HEADER_BYTES,
        maxLines: Int = MAX_HEADER_LINES,
    ): HttpHead {
        var status = 0
        val timings = mutableListOf<String>()
        val line = StringBuilder()
        var bytes = 0
        var lines = 0
        var isFirstLine = true

        while (bytes < maxBytes && lines < maxLines) {
            val b = input.read()
            if (b == -1) break
            bytes++

            when (val c = b.toChar()) {
                '\n' -> {
                    val text = line.toString().trim()
                    line.setLength(0)
                    lines++

                    if (isFirstLine) {
                        // "HTTP/1.1 200 OK" — the middle field is the status.
                        status = text.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                        isFirstLine = false
                    } else {
                        // A blank line terminates the header section.
                        if (text.isEmpty()) return HttpHead(status, timings)
                        collectServerTiming(text, timings)
                    }
                }
                // Carriage returns are part of the line terminator, not content.
                '\r' -> Unit
                else -> line.append(c)
            }
        }
        return HttpHead(status, timings)
    }

    private fun collectServerTiming(header: String, into: MutableList<String>) {
        val colon = header.indexOf(':')
        if (colon <= 0) return
        if (!header.substring(0, colon).trim().equals("server-timing", ignoreCase = true)) return
        into.add(header.substring(colon + 1).trim())
    }

    /** Well past any real response; only a broken or hostile peer reaches it. */
    const val MAX_HEADER_BYTES = 16_384

    /** Bounds a peer that sends many tiny headers rather than a few large ones. */
    const val MAX_HEADER_LINES = 100
}
