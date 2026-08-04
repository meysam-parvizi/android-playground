package com.playground.cfscanner

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Probes a single IP to decide whether it is a genuinely usable Cloudflare edge.
 *
 * Why this is more than a ping: from restrictive networks an IP routinely passes
 * a bare TCP connect, and often even a full TLS handshake, and is then reset by
 * DPI a few seconds later. A naive scanner reports such an IP as excellent. This
 * prober runs a staged check and only trusts an IP that survives all of it.
 *
 * Every blocking socket call runs on [Dispatchers.IO] and every wait uses
 * `delay`, never `Thread.sleep` — blocking the dispatcher threads starves the
 * pool and freezes the whole app.
 */
open class Prober(
    private val timeoutMs: Int = 4000,
    /**
     * How long to hold an established connection idle, watching for a DPI reset.
     * This is the single most important test on filtered networks.
     */
    private val idleHoldMs: Int = 2500,
    private val testWebSocket: Boolean = true,
    /**
     * Bytes to pull from Cloudflare's speed endpoint, or 0 to skip.
     *
     * Handshakes alone do not prove an IP can carry traffic: some complete TLS
     * and answer /cdn-cgi/trace, then stall as soon as real bytes flow. A small
     * transfer catches those. Kept small because it is paid once per candidate.
     */
    private val downloadBytes: Int = 0,
    private val webSocketPreDataHoldMs: Int = 0,
    private val interAttemptDelayMinMs: Int = 0,
    private val interAttemptDelayMaxMs: Int = 0,
) {

    /** Identity/telemetry returned by one trace request, never shared across tries. */
    private data class EdgeVerification(
        val httpStatus: Int = 0,
        val colo: String = "",
        val edge: EdgeTiming? = null,
    )

    private val rnd = SecureRandom()

    /**
     * Permissive trust manager.
     *
     * We connect to a raw IP, so the certificate can never match the SNI we
     * send. Certificate identity is irrelevant here: the goal is to measure
     * whether the transport works, and edge identity is confirmed separately via
     * /cdn-cgi/trace. Used ONLY for probing; this app carries no user data.
     */
    private val probeSocketFactory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }.socketFactory
    }

    /**
     * Wraps [socket] in a TLS layer with [sni] as the server name.
     *
     * Returned rather than assigned inside an `apply` block. The previous shape
     * — `tls = (createSocket(...) as SSLSocket).apply { ... startHandshake() }` —
     * only assigned `tls` once the whole block succeeded, so a failing handshake
     * left the SSLSocket unreferenced and the `finally` clause closed only the
     * underlying socket. Since `autoClose = false` is passed, that does not close
     * the TLS layer, which leaked steadily on a filtered network where handshake
     * failures are the norm.
     *
     * Callers must assign the result to their `tls` variable immediately:
     *
     *     val s = openTls(socket, sni, port)
     *     tls = s
     *     s.startHandshake()
     */
    private fun openTls(socket: Socket, sni: String, port: Int): SSLSocket {
        val s = probeSocketFactory.createSocket(socket, sni, port, false) as SSLSocket
        s.soTimeout = timeoutMs
        s.sslParameters = s.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
        return s
    }

    /** Runs one atomic TCP/TLS/trace/stability chain for one SNI. */
    private suspend fun probeCoreAttempt(ip: String, port: Int, sni: String): AttemptResult {
        var socket: Socket? = null
        var tls: SSLSocket? = null
        var tcpMs = 0L
        var tlsSucceeded = false
        return try {
            val connectStarted = System.nanoTime()
            socket = Socket().apply {
                tcpNoDelay = true
                soTimeout = timeoutMs
            }
            connectCancellable(socket, InetSocketAddress(ip, port))
            tcpMs = ((System.nanoTime() - connectStarted) / 1_000_000).coerceAtLeast(1)

            val verification: EdgeVerification
            val stability: Boolean
            if (port == 80) {
                verification = verifyEdge(socket, sni)
                stability = verification.httpStatus in 200..399 &&
                    verification.colo.isNotEmpty() && holdIdle(socket)
            } else {
                val secure = openTls(socket, sni, port)
                tls = secure
                secure.startHandshake()
                tlsSucceeded = true
                verification = verifyEdge(secure, sni)
                stability = verification.httpStatus in 200..399 &&
                    verification.colo.isNotEmpty() && holdIdle(secure)
            }

            AttemptResult(
                sni = sni,
                tcpConnectMs = tcpMs,
                tlsRequired = port != 80,
                tlsOk = tlsSucceeded,
                httpStatus = verification.httpStatus,
                colo = verification.colo,
                stabilityOk = stability,
                edge = verification.edge,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            AttemptResult(
                sni = sni,
                tcpConnectMs = tcpMs,
                tlsRequired = port != 80,
                tlsOk = tlsSucceeded,
            )
        } finally {
            tls?.closeQuietly()
            socket?.closeQuietly()
        }
    }

    /**
     * Runs [tries] staged attempts against [ip] and returns the aggregate.
     *
     * Suspending and cooperatively cancellable: pressing Stop aborts in-flight
     * probes instead of leaving threads parked for seconds.
     */
    open suspend fun probe(ip: String, port: Int = 443, tries: Int = 3): ScanResult =
        withContext(Dispatchers.IO) {
            val result = ScanResult(ip = ip, port = port)

            repeat(tries) { attemptIndex ->
                currentCoroutineContext().ensureActive()

                var selected = AttemptResult(
                    sni = SniStrategy.order(ip, attemptIndex).first(),
                    tlsRequired = port != 80,
                )
                for (sni in SniStrategy.order(ip, attemptIndex)) {
                    val current = probeCoreAttempt(ip, port, sni)
                    selected = current
                    if (current.coreSuccess) break
                }
                result.recordAttempt(selected)

                if (selected.coreSuccess) {
                    // Candidate-level gates run once after the successful SNI was
                    // chosen. They never rewrite per-attempt loss accounting.
                    if (testWebSocket && !result.wsOk) {
                        probeWebSocket(ip, port, selected.sni, result)
                    }
                    if (downloadBytes > 0 && !result.downloadTested) {
                        probeDownload(ip, port, selected.sni, result)
                    }
                }

                if (attemptIndex < tries - 1) {
                    val pause = nextInterAttemptDelayMs()
                    if (pause > 0) delay(pause.toLong())
                }
            }

            result
        }

    /**
     * Benchmarks an already-discovered IP, overwriting its throughput figures.
     *
     * Separate from [probe] because it is a different question asked at a
     * different time: discovery decides whether an IP works, this measures how
     * fast it is once nothing else is competing for the radio. Reuses the SNI
     * that worked during discovery so the benchmark exercises the same path.
     */
    open suspend fun measureSpeed(result: ScanResult, bytes: Int) {
        if (bytes <= 0) return
        val sni = result.attemptResults.lastOrNull { it.coreSuccess }?.sni
            ?: SniStrategy.order(result.ip, 0).first()
        probeDownload(result.ip, result.port, sni, result, bytes)
    }

    private fun nextInterAttemptDelayMs(): Int {
        val min = interAttemptDelayMinMs.coerceAtLeast(0)
        val max = interAttemptDelayMaxMs.coerceAtLeast(min)
        if (max == 0) return 0
        return min + rnd.nextInt(max - min + 1)
    }

    /**
     * Connects [socket] to [address], aborting immediately if the coroutine is
     * cancelled.
     *
     * `Socket.connect` is an uninterruptible blocking call: it neither checks
     * nor responds to coroutine cancellation, so a cancelled scan used to keep
     * threads parked until every connect timed out. Closing the socket from the
     * cancellation handler forces the blocked call to fail straight away.
     */
    private suspend fun connectCancellable(socket: Socket, address: InetSocketAddress) =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                try { socket.close() } catch (_: Exception) { }
            }
            try {
                socket.connect(address, timeoutMs)
                if (cont.isActive) cont.resume(Unit)
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    /**
     * Issues GET /cdn-cgi/trace and extracts the HTTP status, colo, and any
     * `Server-Timing` telemetry the edge volunteers.
     */
    private fun verifyEdge(socket: Socket, sni: String): EdgeVerification = try {
        val request = buildString {
            append("GET /cdn-cgi/trace HTTP/1.1\r\n")
            append("Host: $sni\r\n")
            append("User-Agent: Mozilla/5.0\r\n")
            append("Accept: */*\r\n")
            append("Connection: keep-alive\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(request.toByteArray())
            flush()
        }

        val input = socket.getInputStream()
        val head = HttpHeadReader.read(input)
        val edge = head.serverTimings
            .takeIf { it.isNotEmpty() }
            ?.let(ServerTimingParser::parse)
            ?.takeIf { it.hasAnything }

        // Drain the trace body so the later idle check cannot succeed on stale
        // response bytes that were already in the socket buffer.
        EdgeVerification(
            httpStatus = head.status,
            colo = readColo(input),
            edge = edge,
        )
    } catch (_: Exception) {
        EdgeVerification()
    }

    /**
     * Reads the `/cdn-cgi/trace` body and returns the `colo=` value.
     *
     * Drains the whole body rather than returning at the match. The idle hold
     * that follows checks whether the peer tears the connection down, and it does
     * that by attempting a read: if unread response bytes were still buffered,
     * that read would succeed immediately on stale data and report the connection
     * healthy without ever testing it. Since this is the decisive check for DPI
     * interference, leaving bytes behind quietly defeated it.
     *
     * Bounded so a peer that never closes cannot hold the probe open; the trace
     * response is a few hundred bytes.
     */
    private fun readColo(input: InputStream): String {
        val body = StringBuilder()
        var read = 0
        val buf = ByteArray(512)
        while (read < MAX_TRACE_BODY_BYTES) {
            val n = try {
                input.read(buf)
            } catch (_: SocketTimeoutException) {
                // The peer sent everything and is holding the connection open;
                // that is a complete body, not a failure.
                break
            }
            if (n <= 0) break
            read += n
            body.append(String(buf, 0, n, Charsets.US_ASCII))
        }

        return body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("colo=") }
            ?.removePrefix("colo=")
            ?.trim()
            ?.uppercase()
            .orEmpty()
    }

    /**
     * Keeps the connection open and idle, then verifies it is still alive.
     *
     * DPI on filtered networks commonly allows the handshake through and injects
     * an RST a few seconds later. Waiting and then re-testing is what separates
     * an IP that merely *connects* from one that actually *works*.
     *
     * Liveness is checked by a short blocking read: a reset connection throws or
     * returns -1 (EOF), while a healthy idle connection simply times out — and a
     * read timeout is precisely the outcome we want. An earlier version used
     * `sendUrgentData`, which most Android devices reject outright, so no IP
     * ever passed and every result came back unhealthy.
     */
    private suspend fun holdIdle(socket: Socket): Boolean =
        holdFor(socket, idleHoldMs)

    /** Holds a connection silent, treating timeout as survival and RST/EOF as failure. */
    private suspend fun holdFor(socket: Socket, durationMs: Int): Boolean = try {
        val target = durationMs.coerceAtLeast(0)
        if (target == 0) {
            true
        } else {
            // Split the wait so cancellation lands promptly.
            val slice = (target / 5).coerceAtLeast(100).toLong()
            var waited = 0L
            var open = true
            while (waited < target && open) {
                val waitNow = minOf(slice, target - waited)
                delay(waitNow)
                waited += waitNow
                currentCoroutineContext().ensureActive()
                open = !socket.isClosed && socket.isConnected && !socket.isInputShutdown
            }

            open && withContext(Dispatchers.IO) { isStillAlive(socket) }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        false
    }

    /**
     * Returns true when the socket is still usable.
     *
     * A very short read timeout is applied: `SocketTimeoutException` means
     * nothing arrived, i.e. the connection is open and healthy. EOF (-1) or any
     * other exception means the peer or DPI tore it down.
     */
    private fun isStillAlive(socket: Socket): Boolean {
        val previousTimeout = try { socket.soTimeout } catch (_: Exception) { timeoutMs }
        return try {
            socket.soTimeout = 350
            val b = socket.getInputStream().read()
            // Unexpected data is fine; EOF is not.
            b != -1
        } catch (_: SocketTimeoutException) {
            true // silence == still connected
        } catch (_: Exception) {
            false
        } finally {
            try { socket.soTimeout = previousTimeout } catch (_: Exception) { }
        }
    }

    /**
     * Attempts a WebSocket upgrade.
     *
     * VLESS/Trojan/V2Ray configurations almost always ride WebSocket over TLS,
     * so an edge that refuses the upgrade is of little practical use even if it
     * answers plain HTTPS.
     */
    private suspend fun probeWebSocket(ip: String, port: Int, sni: String, result: ScanResult) =
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            var tls: SSLSocket? = null
            try {
                socket = Socket()
                socket.soTimeout = timeoutMs
                connectCancellable(socket, InetSocketAddress(ip, port))
                // Assigned before the handshake so `finally` can always close it.
                val secure = openTls(socket, sni, port)
                tls = secure
                secure.startHandshake()

                // Tunnel-like TLS connections can be reset by DPI before they
                // send application data. Restricted mode holds this dedicated WS
                // connection silent first; timeout means it survived, RST/EOF
                // means it was killed.
                if (webSocketPreDataHoldMs > 0 &&
                    !holdFor(secure, webSocketPreDataHoldMs)
                ) {
                    return@withContext
                }

                val keyBytes = ByteArray(16).also { rnd.nextBytes(it) }
                val key = base64(keyBytes)
                val request = buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: $sni\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("User-Agent: Mozilla/5.0\r\n\r\n")
                }
                tls.getOutputStream().apply {
                    write(request.toByteArray())
                    flush()
                }
                // Shared reader rather than a third hand-rolled status parse.
                val code = HttpHeadReader.read(tls.getInputStream()).status
                if (code == 0) return@withContext
                // 101 = upgraded. 4xx still proves the edge parsed our request and
                // is reachable through DPI, which is the property we care about.
                result.wsOk = code == 101 || code in 400..499
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Leave wsOk false.
            } finally {
                tls?.closeQuietly()
                socket?.closeQuietly()
            }
        }

    /**
     * Pulls a small payload from Cloudflare's speed endpoint through [ip].
     *
     * This closes a real gap. Every earlier stage only proves the connection can
     * be *established*: TCP connects, TLS completes, the edge answers a tiny
     * trace request, and the link survives being idle. An IP can pass all of that
     * and still stall the instant a real transfer starts. Moving actual bytes is
     * the only way to see it.
     *
     * Sets [ScanResult.downloadTested] so a failure is distinguishable from
     * never having tried, and records throughput on success.
     */
    private suspend fun probeDownload(
        ip: String,
        port: Int,
        sni: String,
        result: ScanResult,
        bytes: Int = downloadBytes,
    ) = withContext(Dispatchers.IO) {
            result.downloadTested = true
            var socket: Socket? = null
            var tls: SSLSocket? = null
            try {
                socket = Socket()
                socket.soTimeout = timeoutMs
                connectCancellable(socket, InetSocketAddress(ip, port))
                // Assigned before the handshake so `finally` can always close it.
                val secure = openTls(socket, sni, port)
                tls = secure
                secure.startHandshake()

                val request = buildString {
                    append("GET /__down?bytes=$bytes HTTP/1.1\r\n")
                    append("Host: speed.cloudflare.com\r\n")
                    append("User-Agent: Mozilla/5.0\r\n")
                    append("Accept: */*\r\n")
                    append("Connection: close\r\n\r\n")
                }
                val started = System.nanoTime()
                tls.getOutputStream().apply {
                    write(request.toByteArray())
                    flush()
                }

                val input = tls.getInputStream()
                // Same shared reader as verifyEdge; this endpoint reports richer
                // TCP telemetry than the trace request.
                val timings = HttpHeadReader.read(input).serverTimings

                // Drain the body and measure how fast it arrived.
                val buf = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total >= bytes) break
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                result.downloadedBytes = total

                if (total >= ScanResult.MIN_DATA_GATE_BYTES && elapsedMs > 0) {
                    // Discount the edge's own processing time so the figure is
                    // transfer speed rather than transfer plus server think-time.
                    val edgeMs = ServerTimingParser.parse(timings).edgeDurationMs ?: 0
                    val netMs = (elapsedMs - edgeMs).coerceAtLeast(1)
                    result.throughputBps = (total * 1000) / netMs
                }
                // Merge in this endpoint's telemetry; it is usually richer.
                if (timings.isNotEmpty()) {
                    ServerTimingParser.parse(timings).takeIf { it.hasAnything }
                        ?.let { result.edge = it }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Leave throughput at 0; downloadTested marks this as a failure.
            } finally {
                tls?.closeQuietly()
                socket?.closeQuietly()
            }
        }

    private fun Socket.closeQuietly() {
        try { close() } catch (_: Exception) { } catch (_: SocketException) { }
    }

    /**
     * Minimal Base64 encoder.
     *
     * Deliberately hand-rolled rather than using android.util.Base64, which is a
     * stubbed class in plain JVM unit tests and throws at runtime there, and
     * java.util.Base64, which needs API 26 (this app supports 24).
     */
    private fun base64(bytes: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2

            sb.append(table[(triple shr 18) and 0x3F])
            sb.append(table[(triple shr 12) and 0x3F])
            sb.append(if (i + 1 < bytes.size) table[(triple shr 6) and 0x3F] else '=')
            sb.append(if (i + 2 < bytes.size) table[triple and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    private companion object {
        /** The trace response is a few hundred bytes; this is a safety ceiling. */
        const val MAX_TRACE_BODY_BYTES = 8_192
    }
}
