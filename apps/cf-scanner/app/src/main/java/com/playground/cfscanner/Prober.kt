package com.playground.cfscanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
) {

    /** SNI hostnames rotated across probes so DPI cannot key on one domain. */
    private val sniPool = listOf(
        "www.cloudflare.com",
        "cdnjs.cloudflare.com",
        "ajax.cloudflare.com",
        "blog.cloudflare.com",
        "developers.cloudflare.com",
        "speed.cloudflare.com",
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
     * Runs [tries] staged attempts against [ip] and returns the aggregate.
     *
     * Suspending and cooperatively cancellable: pressing Stop aborts in-flight
     * probes instead of leaving threads parked for seconds.
     */
    open suspend fun probe(ip: String, port: Int = 443, tries: Int = 3): ScanResult =
        withContext(Dispatchers.IO) {
            val result = ScanResult(ip = ip, port = port)

            repeat(tries) { attempt ->
                currentCoroutineContext().ensureActive()
                var socket: Socket? = null
                var tls: SSLSocket? = null
                try {
                    val started = System.nanoTime()
                    socket = Socket()
                    socket.tcpNoDelay = true
                    socket.soTimeout = timeoutMs

                    // Socket.connect() blocks and does not observe coroutine
                    // cancellation, so a cancelled scan would otherwise sit here
                    // for the full timeout. Closing the socket from the
                    // cancellation handler makes the blocked call throw at once.
                    connectCancellable(socket, InetSocketAddress(ip, port))

                    val connectMs = (System.nanoTime() - started) / 1_000_000

                    if (port == 80) {
                        result.latencies.add(if (connectMs > 0) connectMs else 1)
                        holdIdle(socket, result)
                        return@repeat
                    }

                    val sni = sniPool[(attempt + rnd.nextInt(sniPool.size)) % sniPool.size]
                    tls = (probeSocketFactory.createSocket(socket, sni, port, false) as SSLSocket).apply {
                        soTimeout = timeoutMs
                        sslParameters = sslParameters.apply {
                            serverNames = listOf(SNIHostName(sni))
                        }
                        startHandshake()
                    }
                    result.tlsOk = true

                    val totalMs = (System.nanoTime() - started) / 1_000_000
                    result.latencies.add(if (totalMs > 0) totalMs else 1)

                    // Confirm this is genuinely a Cloudflare edge, and learn its colo.
                    verifyEdge(tls, sni, result)

                    // The decisive test: does the link survive being idle?
                    holdIdle(tls, result)

                    if (testWebSocket && !result.wsOk && result.stableOk) {
                        tls.closeQuietly()
                        socket.closeQuietly()
                        tls = null
                        socket = null
                        probeWebSocket(ip, port, sni, result)
                    }

                    // Payload transfer, last and only once: it is the most
                    // expensive stage, so it runs only for an IP that has already
                    // proven it can hold a connection.
                    if (downloadBytes > 0 && result.stableOk && !result.downloadTested) {
                        tls?.closeQuietly()
                        socket?.closeQuietly()
                        tls = null
                        socket = null
                        probeDownload(ip, port, sni, result)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // A failed attempt is recorded as latency 0 so loss() sees it.
                    result.latencies.add(0)
                } finally {
                    tls?.closeQuietly()
                    socket?.closeQuietly()
                }
            }

            result
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
    private fun verifyEdge(socket: Socket, sni: String, result: ScanResult) {
        try {
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

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine() ?: return
            statusLine.split(" ").getOrNull(1)?.toIntOrNull()?.let { result.httpStatus = it }

            // Collect Server-Timing while walking the headers. The response
            // carries it more than once, so every occurrence is kept.
            val timings = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("server-timing", true)) {
                    timings.add(line.substring(colon + 1).trim())
                }
            }
            if (timings.isNotEmpty()) {
                ServerTimingParser.parse(timings).takeIf { it.hasAnything }?.let { result.edge = it }
            }

            // Body is the trace key=value list; find colo.
            var guard = 0
            while (guard++ < 40) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.startsWith("colo=")) {
                    result.colo = trimmed.removePrefix("colo=").trim().uppercase()
                    break
                }
            }
        } catch (_: Exception) {
            // Leave httpStatus/colo unset; isHealthy() will reject the IP.
        }
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
    private suspend fun holdIdle(socket: Socket, result: ScanResult) {
        try {
            // Split the wait so cancellation lands promptly.
            val slice = (idleHoldMs / 5).coerceAtLeast(100).toLong()
            var waited = 0L
            while (waited < idleHoldMs) {
                delay(slice)
                waited += slice
                if (!currentCoroutineContext().isActive) return
                if (socket.isClosed || !socket.isConnected || socket.isInputShutdown) return
            }

            result.stableOk = withContext(Dispatchers.IO) { isStillAlive(socket) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Any failure here means the connection did not survive.
        }
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
        } catch (_: java.net.SocketTimeoutException) {
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
                tls = (probeSocketFactory.createSocket(socket, sni, port, false) as SSLSocket).apply {
                    soTimeout = timeoutMs
                    sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
                    startHandshake()
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
                val statusLine = BufferedReader(InputStreamReader(tls.getInputStream())).readLine()
                    ?: return@withContext
                // 101 = upgraded. 4xx still proves the edge parsed our request and
                // is reachable through DPI, which is the property we care about.
                val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: return@withContext
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
    private suspend fun probeDownload(ip: String, port: Int, sni: String, result: ScanResult) =
        withContext(Dispatchers.IO) {
            result.downloadTested = true
            var socket: Socket? = null
            var tls: SSLSocket? = null
            try {
                socket = Socket()
                socket.soTimeout = timeoutMs
                connectCancellable(socket, InetSocketAddress(ip, port))
                tls = (probeSocketFactory.createSocket(socket, sni, port, false) as SSLSocket).apply {
                    soTimeout = timeoutMs
                    sslParameters = sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
                    startHandshake()
                }

                val request = buildString {
                    append("GET /__down?bytes=$downloadBytes HTTP/1.1\r\n")
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
                // Read past the headers, capturing Server-Timing on the way: this
                // endpoint reports richer TCP telemetry than the trace request.
                val timings = mutableListOf<String>()
                val headerLine = StringBuilder()
                var blankSeen = false
                var headerBytes = 0
                while (!blankSeen && headerBytes < 16_384) {
                    val b = input.read()
                    if (b == -1) break
                    headerBytes++
                    val c = b.toChar()
                    if (c == '\n') {
                        val line = headerLine.toString().trim()
                        if (line.isEmpty()) {
                            blankSeen = true
                        } else {
                            val colon = line.indexOf(':')
                            if (colon > 0 &&
                                line.substring(0, colon).trim().equals("server-timing", true)
                            ) {
                                timings.add(line.substring(colon + 1).trim())
                            }
                        }
                        headerLine.setLength(0)
                    } else if (c != '\r') {
                        headerLine.append(c)
                    }
                }

                // Drain the body and measure how fast it arrived.
                val buf = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total >= downloadBytes) break
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000

                if (total > 0 && elapsedMs > 0) {
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
}
