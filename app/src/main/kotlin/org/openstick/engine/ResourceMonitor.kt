package org.openstick.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File

data class ProcessInfo(
    val pid: Int,
    val name: String,
    val state: String,
    val memoryKb: Long
)

data class BatteryStats(
    val levelPercent: Int,
    val currentMa: Int,
    val voltageMv: Int,
    val tempCelsius: Float,
    val isCharging: Boolean,
    val statusString: String
)

data class SystemStats(
    val cpuPercent: Int,
    val corePercents: List<Int> = emptyList(),
    val tempCelsius: Float,
    val totalRamMb: Long,
    val usedRamMb: Long,
    val freeRamMb: Long,
    val totalStorageGb: Float,
    val freeStorageGb: Float,
    val battery: BatteryStats = BatteryStats(0, 0, 0, 0f, false, "Unknown"),
    val processes: List<ProcessInfo>
)

class ResourceMonitor(private val context: Context) {
    private var lastTotalTime: Long = 0
    private var lastIdleTime: Long = 0
    private val lastCoreTimes = mutableMapOf<Int, Pair<Long, Long>>()

    fun sample(): SystemStats {
        val cores = readPerCoreCpu()
        val cpu = readCpuUsage(cores)
        val battery = readBattery()
        val temp = readCpuTemp(battery.tempCelsius)
        val (totalRam, usedRam, freeRam) = readRam()
        val (totalStorage, freeStorage) = readStorage()
        val procs = listProcesses()

        return SystemStats(
            cpuPercent = cpu,
            corePercents = cores,
            tempCelsius = temp,
            totalRamMb = totalRam,
            usedRamMb = usedRam,
            freeRamMb = freeRam,
            totalStorageGb = totalStorage,
            freeStorageGb = freeStorage,
            battery = battery,
            processes = procs
        )
    }

    private fun readCpuUsage(cores: List<Int>): Int {
        try {
            val statFile = File("/proc/stat")
            if (statFile.canRead()) {
                val line = statFile.bufferedReader().use { it.readLine() }
                if (line != null) {
                    val parts = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
                    if (parts.size >= 4) {
                        val idle = parts[3]
                        val total = parts.sum()
                        val diffTotal = total - lastTotalTime
                        val diffIdle = idle - lastIdleTime
                        lastTotalTime = total
                        lastIdleTime = idle
                        if (diffTotal > 0) {
                            return ((diffTotal - diffIdle) * 100 / diffTotal).toInt().coerceIn(0, 100)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: Dynamic average of all active cores from cpufreq
        if (cores.isNotEmpty()) {
            return (cores.sum() / cores.size).coerceIn(0, 100)
        }
        return 0
    }

    fun readPerCoreCpu(): List<Int> {
        val results = mutableListOf<Int>()
        try {
            val numCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            for (i in 0 until numCores) {
                val curFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                val maxFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq")
                val minFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_min_freq")
                if (curFile.canRead() && maxFile.canRead()) {
                    val cur = curFile.readText().trim().toLongOrNull() ?: continue
                    val max = maxFile.readText().trim().toLongOrNull() ?: continue
                    val min = if (minFile.canRead()) minFile.readText().trim().toLongOrNull() ?: 0L else 0L
                    val range = max - min
                    val pct = if (range > 0) {
                        ((cur - min) * 100 / range).toInt()
                    } else if (max > 0) {
                        (cur * 100 / max).toInt()
                    } else {
                        0
                    }
                    results.add(pct.coerceIn(0, 100))
                }
            }
        } catch (_: Exception) {}

        if (results.isNotEmpty()) {
            return results
        }

        // Fallback to /proc/stat if accessible
        try {
            val statFile = File("/proc/stat")
            if (statFile.canRead()) {
                val lines = statFile.bufferedReader().readLines()
                for (line in lines) {
                    if (line.matches(Regex("^cpu\\d+\\s+.*"))) {
                        val coreId = line.substringBefore(" ").removePrefix("cpu").toIntOrNull() ?: continue
                        val parts = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
                        if (parts.size >= 4) {
                            val idle = parts[3]
                            val total = parts.sum()
                            val prev = lastCoreTimes[coreId]
                            lastCoreTimes[coreId] = Pair(total, idle)
                            if (prev != null) {
                                val diffTotal = total - prev.first
                                val diffIdle = idle - prev.second
                                val usage = if (diffTotal > 0) ((diffTotal - diffIdle) * 100 / diffTotal).toInt() else 0
                                results.add(usage.coerceIn(0, 100))
                            } else {
                                results.add(0)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return results
    }

    private fun readCpuTemp(fallbackTemp: Float): Float {
        try {
            val thermalDir = File("/sys/class/thermal")
            if (thermalDir.canRead()) {
                val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return fallbackTemp
                for (zone in zones) {
                    val type = File(zone, "type").readText().trim()
                    if (type.contains("cpu", ignoreCase = true) || type.contains("soc", ignoreCase = true)) {
                        val raw = File(zone, "temp").readText().trim().toFloatOrNull() ?: continue
                        return if (raw > 1000) raw / 1000f else raw
                    }
                }
            }
        } catch (_: Exception) {}
        return fallbackTemp
    }

    private fun readBattery(): BatteryStats {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bStatus = context.registerReceiver(null, ifilter)

            val level = bStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0)

            val status = bStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val voltage = bStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val tempRaw = bStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val bTemp = tempRaw / 10f

            var currentNow = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
            if (currentNow == 0 || currentNow == -1) {
                val currentAvg = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: 0
                if (currentAvg != 0 && currentAvg != -1) {
                    currentNow = currentAvg
                }
            }
            if (kotlin.math.abs(currentNow) > 10000) {
                currentNow /= 1000
            }

            val statusStr = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging (${kotlin.math.abs(currentNow)} mA)"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging (${kotlin.math.abs(currentNow)} mA)"
                BatteryManager.BATTERY_STATUS_FULL -> "Full (Plugged)"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Plugged (Idle)"
                else -> if (isCharging) "Charging (${kotlin.math.abs(currentNow)} mA)" else "Discharging (${kotlin.math.abs(currentNow)} mA)"
            }

            BatteryStats(
                levelPercent = pct.coerceIn(0, 100),
                currentMa = currentNow,
                voltageMv = voltage,
                tempCelsius = bTemp,
                isCharging = isCharging,
                statusString = statusStr
            )
        } catch (_: Exception) {
            BatteryStats(
                levelPercent = 0,
                currentMa = 0,
                voltageMv = 0,
                tempCelsius = 0f,
                isCharging = false,
                statusString = "Unavailable"
            )
        }
    }

    private fun readRam(): Triple<Long, Long, Long> {
        var total = 0L
        var available = 0L
        try {
            File("/proc/meminfo").forEachLine { line ->
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim().split(" ")[0].toLongOrNull() ?: 0L
                    if (key == "MemTotal") total = value / 1024
                    if (key == "MemAvailable") available = value / 1024
                }
            }
        } catch (_: Exception) {}
        val used = (total - available).coerceAtLeast(0)
        return Triple(total, used, available)
    }

    private fun readStorage(): Pair<Float, Float> {
        return try {
            val statFs = StatFs(context.filesDir.absolutePath)
            val total = statFs.totalBytes.toFloat() / (1024 * 1024 * 1024)
            val free = statFs.availableBytes.toFloat() / (1024 * 1024 * 1024)
            Pair(total, free)
        } catch (_: Exception) {
            Pair(0f, 0f)
        }
    }

    fun listProcesses(): List<ProcessInfo> {
        val list = mutableListOf<ProcessInfo>()
        val procDir = File("/proc")
        val pids = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return list

        for (dir in pids) {
            val pid = dir.name.toIntOrNull() ?: continue
            try {
                val comm = File(dir, "comm").readText().trim()
                var state = "S"
                var rss = 0L

                val statusFile = File(dir, "status")
                if (statusFile.canRead()) {
                    statusFile.forEachLine { l ->
                        if (l.startsWith("State:")) state = l.substringAfter(":").trim().take(1)
                        if (l.startsWith("VmRSS:")) rss = l.substringAfter(":").trim().split(" ")[0].toLongOrNull() ?: 0L
                    }
                }
                list.add(ProcessInfo(pid, comm, state, rss))
            } catch (_: Exception) {}
        }
        return list.sortedByDescending { it.memoryKb }
    }

    fun getCores(): Int = Runtime.getRuntime().availableProcessors()

    fun killProcess(pid: Int): Boolean {
        return try {
            Os.kill(pid, OsConstants.SIGKILL)
            true
        } catch (_: Exception) {
            false
        }
    }
}
