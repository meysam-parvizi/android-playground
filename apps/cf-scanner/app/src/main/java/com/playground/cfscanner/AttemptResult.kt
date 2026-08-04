package com.playground.cfscanner

/**
 * Outcome of one complete probe attempt against a candidate IP.
 *
 * A candidate is never allowed to combine success pieces from different tries:
 * this object is the atomic unit used for loss and health accounting.
 */
data class AttemptResult(
    val sni: String,
    /** TCP connect time in milliseconds; 0 means connect failed. */
    val tcpConnectMs: Long = 0,
    val tlsRequired: Boolean = true,
    val tlsOk: Boolean = false,
    val httpStatus: Int = 0,
    val colo: String = "",
    val stabilityOk: Boolean = false,
    val edge: EdgeTiming? = null,
) {
    val traceOk: Boolean
        get() = httpStatus in 200..399 && colo.isNotEmpty()

    /** Every mandatory Phase-1 stage passed inside this same attempt. */
    val coreSuccess: Boolean
        get() = tcpConnectMs > 0 && (!tlsRequired || tlsOk) && traceOk && stabilityOk
}
