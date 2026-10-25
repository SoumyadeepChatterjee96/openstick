package org.openstick.engine

import android.content.Context
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

data class SystemStats(
    val cpuPercent: Int,
    val tempCelsius: Float,
    val totalRamMb: Long,
    val usedRamMb: Long,
    val freeRamMb: Long,
    val totalStorageGb: Float,
    val freeStorageGb: Float,
    val processes: List<ProcessInfo>
)

class ResourceMonitor(private val context: Context) {
    private var lastTotalTime: Long = 0
    private var lastIdleTime: Long = 0

    fun sample(): SystemStats {
        val cpu = readCpuUsage()
        val temp = readCpuTemp()
        val (totalRam, usedRam, freeRam) = readRam()
        val (totalStorage, freeStorage) = readStorage()
        val procs = listProcesses()

        return SystemStats(
            cpuPercent = cpu,
            tempCelsius = temp,
            totalRamMb = totalRam,
            usedRamMb = usedRam,
            freeRamMb = freeRam,
            totalStorageGb = totalStorage,
            freeStorageGb = freeStorage,
            processes = procs
        )
    }

    private fun readCpuUsage(): Int {
        try {
            val statFile = File("/proc/stat")
            if (!statFile.canRead()) return 0
            val line = statFile.bufferedReader().use { it.readLine() } ?: return 0
            val parts = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size < 4) return 0

            val idle = parts[3]
            val total = parts.sum()

            val diffTotal = total - lastTotalTime
            val diffIdle = idle - lastIdleTime

            lastTotalTime = total
            lastIdleTime = idle

            if (diffTotal <= 0) return 0
            val usage = ((diffTotal - diffIdle) * 100 / diffTotal).toInt()
            return usage.coerceIn(0, 100)
        } catch (_: Exception) {
            return 0
        }
    }

    private fun readCpuTemp(): Float {
        try {
            val thermalDir = File("/sys/class/thermal")
            val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return 0f
            for (zone in zones) {
                val type = File(zone, "type").readText().trim()
                if (type.contains("cpu", ignoreCase = true) || type.contains("soc", ignoreCase = true)) {
                    val raw = File(zone, "temp").readText().trim().toFloatOrNull() ?: continue
                    return if (raw > 1000) raw / 1000f else raw
                }
            }
        } catch (_: Exception) {}
        return 0f
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
