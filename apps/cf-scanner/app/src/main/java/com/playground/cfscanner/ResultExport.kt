package com.playground.cfscanner

/**
 * Formats scan results for export.
 *
 * Kept separate from the RecyclerView adapter so it carries no Android
 * dependency and can be unit-tested directly.
 */
object ResultExport {

    /**
     * Bare list of addresses in the given order — one IP per line, nothing else.
     *
     * Deliberately free of headers, comments, and metrics: the output is meant to
     * be pasted straight into a proxy client's config, where any extra text would
     * have to be stripped by hand. There is no trailing newline, which would
     * otherwise read as an empty final entry.
     */
    fun ipList(results: List<ScanResult>): String = results.joinToString("\n") { it.ip }

    /**
     * Full results as a tab-separated table with a short header.
     *
     * Always English, always ASCII digits, regardless of the UI language. A saved
     * file outlives the session — it gets pasted into bug reports, opened in a
     * spreadsheet, diffed against a later scan — so it must not change shape
     * because the app happened to be in Persian. Persian digits would also break
     * anything that parses it.
     *
     * Tabs rather than aligned columns: the file stays diffable and imports into
     * a spreadsheet without a parsing step, and no column can be silently
     * truncated by a long value.
     *
     * None of the UI's formatting helpers are used here for the same reason. They
     * localise digits and wrap values in bidi isolates, which are invisible in an
     * editor but corrupt a parser.
     */
    fun detailed(results: List<ScanResult>): String {
        val header = listOf(
            "# CF Scanner — clean Cloudflare IP scan",
            "# results: ${results.size}",
            "#",
            "# ping_ms   round-trip time, lower is better",
            "# jitter_ms variation in ping, lower is better",
            "# loss_pct  packet loss across attempts",
            "# speed_mbps download speed; '-' means it was not measured",
            "# ws        WebSocket upgrade accepted (yes/no)",
            "",
            COLUMNS.joinToString("\t"),
        )

        val rows = results.mapIndexed { index, r ->
            listOf(
                (index + 1).toString(),
                r.ip,
                r.score().toString(),
                grade(r.score()),
                r.avgMs().toString(),
                r.jitterMs().toString(),
                r.loss().toInt().toString(),
                if (r.hasMeasuredSpeed) mbps(r.throughputBps) else "-",
                if (r.wsOk) "yes" else "no",
                r.colo.ifEmpty { "-" },
            ).joinToString("\t")
        }

        return (header + rows).joinToString("\n")
    }

    private val COLUMNS = listOf(
        "rank", "ip", "score", "grade", "ping_ms", "jitter_ms",
        "loss_pct", "speed_mbps", "ws", "datacenter",
    )

    /**
     * English grade name for a score.
     *
     * The bands mirror [ScanResult.gradeRes] exactly. Resolved here rather than
     * through string resources because those follow the selected language and
     * this file must not — but the boundaries must stay in step, or an exported
     * grade would contradict the one on screen.
     */
    private fun grade(score: Int): String = when (score) {
        in 90..100 -> "excellent"
        in 75..89 -> "good"
        in 55..74 -> "fair"
        in 1..54 -> "weak"
        else -> "unhealthy"
    }

    /** Bytes/sec to Mbps with one decimal, using a dot regardless of locale. */
    private fun mbps(bytesPerSecond: Long): String {
        val tenths = (bytesPerSecond * 8 * 10) / 1_000_000
        return "${tenths / 10}.${tenths % 10}"
    }
}
