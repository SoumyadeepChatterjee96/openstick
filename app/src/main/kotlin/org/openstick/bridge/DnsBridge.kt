package org.openstick.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.util.Log
import java.io.File

/**
 * Synchronizes host Android DNS servers dynamically to Linux /etc/resolv.conf
 * so network switching (Wi-Fi <-> Cellular) never breaks internet resolution.
 */
class DnsBridge(
    private val context: Context,
    private val rootfsDir: File
) {
    companion object {
        private const val TAG = "DnsBridge"
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
    }

    private val resolvConf = File(rootfsDir, "etc/resolv.conf")
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        applyCurrentDns()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                applyDnsFromProperties(linkProperties)
            }

            override fun onAvailable(network: Network) {
                applyCurrentDns()
            }

            override fun onLost(network: Network) {
                applyCurrentDns()
            }
        }

        try {
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    fun stop() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            callback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        callback = null
    }

    fun applyCurrentDns() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val activeNet = cm.activeNetwork
        val lp = if (activeNet != null) cm.getLinkProperties(activeNet) else null
        applyDnsFromProperties(lp)
    }

    fun applyCustomDns(servers: List<String>) {
        val validServers = servers.filter { it.isNotBlank() }
        val targetList = if (validServers.isNotEmpty()) validServers else FALLBACK_DNS

        val content = buildString {
            append("# Generated dynamically by OpenStick DnsBridge\n")
            targetList.distinct().forEach { ip ->
                append("nameserver ").append(ip.trim()).append("\n")
            }
        }

        try {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write resolv.conf", e)
        }
    }

    private fun applyDnsFromProperties(lp: LinkProperties?) {
        val servers = mutableListOf<String>()
        lp?.dnsServers?.forEach { addr ->
            val host = addr.hostAddress
            if (!host.isNullOrBlank() && !host.contains(":")) { // prefer IPv4 for compatibility
                servers.add(host)
            }
        }

        if (servers.isEmpty()) {
            servers.addAll(FALLBACK_DNS)
        }

        val content = buildString {
            append("# Generated dynamically by OpenStick DnsBridge\n")
            servers.distinct().forEach { ip ->
                append("nameserver ").append(ip).append("\n")
            }
        }

        try {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write resolv.conf", e)
        }
    }
}
