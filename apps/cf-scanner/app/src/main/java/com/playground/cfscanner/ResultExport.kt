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
}
