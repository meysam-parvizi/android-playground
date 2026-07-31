package com.playground.cfscanner

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Detects whether traffic is currently being routed through a VPN or similar
 * tunnel.
 *
 * This matters for correctness, not politeness: with a tunnel active, every probe
 * travels through it, so the latency, jitter, and stability we measure describe
 * the tunnel rather than the network the results will actually be used on. A scan
 * run over a VPN can rank an IP as excellent that is unusable once the VPN is off.
 */
object VpnDetector {

    /**
     * Returns true when the active network is a VPN, or when the connection is
     * missing the "not VPN" capability.
     *
     * Detection is best-effort: some tunnels are indistinguishable from a normal
     * connection at this level, so a false negative is possible. It is therefore
     * used to strengthen a warning that is shown regardless, never to block a scan.
     */
    fun isActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return try {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } catch (_: Exception) {
            // Permission or platform quirk: fall back to "unknown", not "active".
            false
        }
    }
}
