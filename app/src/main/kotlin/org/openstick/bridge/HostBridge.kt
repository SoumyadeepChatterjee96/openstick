package org.openstick.bridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.File

/**
 * Periodically reports host Android status (Battery %, Charging, Network Type)
 * into Linux guest runtime directories (/run/openstick/ and /run/bsl/).
 */
class HostBridge(
    private val context: Context,
    private val rootfsDir: File
) {
    companion object {
        private const val TAG = "HostBridge"
        private const val REFRESH_INTERVAL_MS = 10000L
    }

    private val runDir = File(rootfsDir, "run/openstick")
    private val bslRunDir = File(rootfsDir, "run/bsl")
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        runDir.mkdirs()
        bslRunDir.mkdirs()

        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    writeHostStatus()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write host status", e)
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun writeHostStatus() {
        runDir.mkdirs()
        bslRunDir.mkdirs()

        // 1. Battery Info
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else level

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        // 2. Network Info
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null

        val isOnline = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val netType = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }

        val content = buildString {
            append("battery=").append(batteryPct).append("\n")
            append("battery_charging=").append(if (isCharging) 1 else 0).append("\n")
            append("online=").append(if (isOnline) 1 else 0).append("\n")
            append("network_type=").append(netType).append("\n")
            append("app_version=1.0.0\n")
            append("pro=1\n") // 100% Free & Open Source, all features unlocked
        }

        writeFile(File(runDir, "battery"), batteryPct.toString())
        writeFile(File(runDir, "network"), netType)
        writeFile(File(runDir, "status"), content)

        // Compatibility mirror in /run/bsl
        writeFile(File(bslRunDir, "battery"), batteryPct.toString())
        writeFile(File(bslRunDir, "network"), netType)
        writeFile(File(bslRunDir, "status"), content)
    }

    private fun writeFile(file: File, text: String) {
        try {
            file.writeText(text)
        } catch (_: Exception) {}
    }
}
