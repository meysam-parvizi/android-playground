package com.playground.cfscanner

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Probes a single IP to decide whether it is a genuinely usable Cloudflare edge.
 *
 * Why this is more than a ping: from Iranian ISPs an IP routinely passes a bare
 * TCP connect, and often even a full TLS handshake, and is then reset by DPI a
 * few seconds later. A naive scanner reports such an IP as excellent. This
 * prober therefore runs a staged check and only trusts an IP that survives all
 * of it.
 */
class Prober(
    private val timeoutMs: Int = 4000,
    /**
     * How long to hold an established connection idle, watching for a DPI reset.
     * This is the single most important test for Iranian networks.
     */
    private val idleHoldMs: Int = 2500,
    private val testWebSocket: Boolean = true,
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
     * We are connecting to a raw IP, so the certificate will never match the
     * SNI hostname we send. Certificate identity is irrelevant here: the goal is
     * to measure whether the transport works, and edge identity is confirmed
     * separately via /cdn-cgi/trace. This socket factory is used ONLY for
     * probing and never for carrying user data.
     */
    private val probeSocketFactory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLSv1.2").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }.socketFactory
    }

    /**
     * Runs [tries] staged attempts against [ip] and returns the aggregate.
     *
     * Stages per attempt:
     *  1. TCP connect (measures latency)
     *  2. TLS handshake with a rotating SNI
     *  3. HTTPS GET /cdn-cgi/trace — confirms a real Cloudflare edge and colo
     *  4. Idle hold — confirms DPI does not reset the connection
     *  5. WebSocket upgrade (optional) — confirms proxy protocols can pass
     */
    fun probe(ip: String, port: Int = 443, tries: Int = 3): ScanResult {
        val result = ScanResult(ip = ip, port = port)

        repeat(tries) { attempt ->
            var socket: Socket? = null
            try {
                val started = System.nanoTime()
                socket = Socket()
                socket.tcpNoDelay = true
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                val connectMs = (System.nanoTime() - started) / 1_000_000

                if (port == 80) {
                    // Plain HTTP path: no TLS to verify.
                    result.latencies.add(if (connectMs > 0) connectMs else 1)
                    holdIdle(socket, result)
                    return@repeat
                }

                val sni = sniPool[(attempt + rnd.nextInt(sniPool.size)) % sniPool.size]
                val tls = probeSocketFactory.createSocket(socket, sni, port, true) as SSLSocket
                tls.soTimeout = timeoutMs
                // Advertise the SNI we intend DPI to see.
                tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(sni)) }
                tls.startHandshake()
                result.tlsOk = true

                val totalMs = (System.nanoTime() - started) / 1_000_000
                result.latencies.add(if (totalMs > 0) totalMs else 1)

                // Confirm this is genuinely a Cloudflare edge, and learn its colo.
                verifyEdge(tls, sni, result)

                // The decisive Iran test: does the link survive being idle?
                holdIdle(tls, result)

                if (testWebSocket && result.wsOk.not() && result.stableOk) {
                    // Reuse of a spent socket is unreliable; use a fresh one.
                    tls.closeQuietly()
                    socket.closeQuietly()
                    socket = null
                    probeWebSocket(ip, port, sni, result)
                }
            } catch (_: Exception) {
                // A failed attempt is recorded as latency 0 so loss() sees it.
                result.latencies.add(0)
            } finally {
                socket?.closeQuietly()
            }
        }

        return result
    }

    /** Issues GET /cdn-cgi/trace and extracts the HTTP status plus colo code. */
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
            // e.g. "HTTP/1.1 200 OK"
            statusLine.split(" ").getOrNull(1)?.toIntOrNull()?.let { result.httpStatus = it }

            // Read headers, then the trace body which carries colo=XXX.
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            var guard = 0
            while (guard++ < 40) {
                line = reader.readLine() ?: break
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
     * Keeps the connection open and idle, then checks it is still alive.
     *
     * Iranian DPI commonly allows the handshake through and injects an RST a
     * few seconds later. Sleeping and then re-testing the socket is what
     * separates an IP that merely *connects* from one that actually *works*.
     */
    private fun holdIdle(socket: Socket, result: ScanResult) {
        try {
            val half = idleHoldMs / 2
            Thread.sleep(half.toLong())
            if (socket.isClosed || !socket.isConnected || socket.isInputShutdown) return
            // Second stage: some DPI resets land later than others.
            Thread.sleep((idleHoldMs - half).toLong())
            if (socket.isClosed || !socket.isConnected || socket.isInputShutdown) return

            // Urgent-data probe forces the stack to surface a pending RST.
            socket.sendUrgentData(0xFF)
            result.stableOk = true
        } catch (_: Exception) {
            // An exception here means DPI (or the peer) killed the connection.
        }
    }

    /**
     * Attempts a WebSocket upgrade.
     *
     * VLESS/Trojan/V2Ray configurations almost always ride WebSocket over TLS,
     * so an edge that refuses the upgrade is of little practical use even if it
     * answers plain HTTPS.
     */
    private fun probeWebSocket(ip: String, port: Int, sni: String, result: ScanResult) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            val tls = probeSocketFactory.createSocket(socket, sni, port, true) as SSLSocket
            tls.soTimeout = timeoutMs
            tls.startHandshake()

            val key = java.util.Base64.getEncoder().encodeToString(ByteArray(16).also { rnd.nextBytes(it) })
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
            val statusLine = BufferedReader(InputStreamReader(tls.getInputStream())).readLine() ?: return
            // 101 = upgraded. 4xx still proves the edge parsed our request and
            // is reachable through DPI, which is the property we care about.
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: return
            result.wsOk = code == 101 || code in 400..499
        } catch (_: Exception) {
            // Leave wsOk false.
        } finally {
            socket?.closeQuietly()
        }
    }

    private fun Socket.closeQuietly() {
        try { close() } catch (_: Exception) { }
    }
}
