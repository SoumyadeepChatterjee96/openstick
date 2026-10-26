package org.openstick.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openstick.MainActivity
import java.io.File

class SessionService : LifecycleService() {
    companion object {
        const val CHANNEL_ID = "openstick_session"
        const val NOTIFICATION_ID = 1001

        var isRunning by mutableStateOf(false)
            internal set

        fun start(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            isRunning = true
        }

        fun stop(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            context.stopService(intent)
            isRunning = false
        }

        fun cleanSockets(env: LinuxEnvironment): Int {
            var cleanedCount = 0
            val rootfs = env.rootfsDir
            val tmpDir = File(rootfs, "tmp")
            if (tmpDir.exists()) {
                tmpDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name.startsWith(".X") || name.startsWith(".winch") || name.startsWith("pulse") || name.contains("-lock") || name.startsWith(".openstick-")) {
                        try {
                            if (file.isDirectory) file.deleteRecursively() else file.delete()
                            cleanedCount++
                        } catch (_: Exception) {}
                    }
                }
            }
            val runDir = File(rootfs, "run/openstick")
            if (runDir.exists()) {
                runDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".sock") || file.name.endsWith(".lock")) {
                        try {
                            file.delete()
                            cleanedCount++
                        } catch (_: Exception) {}
                    }
                }
            }
            // Stale DPKG and APT package manager locks
            val dpkgDir = File(rootfs, "var/lib/dpkg")
            if (dpkgDir.exists()) {
                listOf("lock", "lock-frontend").forEach { lockName ->
                    val f = File(dpkgDir, lockName)
                    if (f.exists()) {
                        try {
                            if (f.delete()) cleanedCount++
                        } catch (_: Exception) {}
                    }
                }
            }
            val aptListsDir = File(rootfs, "var/lib/apt/lists")
            if (aptListsDir.exists()) {
                val f = File(aptListsDir, "lock")
                if (f.exists()) {
                    try {
                        if (f.delete()) cleanedCount++
                    } catch (_: Exception) {}
                }
            }
            val aptArchivesDir = File(rootfs, "var/cache/apt/archives")
            if (aptArchivesDir.exists()) {
                val f = File(aptArchivesDir, "lock")
                if (f.exists()) {
                    try {
                        if (f.delete()) cleanedCount++
                    } catch (_: Exception) {}
                }
            }
            return cleanedCount
        }

        fun reboot(
            context: Context,
            env: LinuxEnvironment,
            runner: ProotRunner,
            scope: CoroutineScope,
            onProgress: (String) -> Unit = {}
        ) {
            scope.launch(Dispatchers.IO) {
                onProgress("Stopping session processes...")
                try {
                    runner.killSync()
                } catch (_: Exception) {}
                try {
                    HardwareBridges.stopAll()
                } catch (_: Exception) {}
                stop(context)

                onProgress("Purging lock and socket files...")
                val cleaned = cleanSockets(env)

                kotlinx.coroutines.delay(500)

                onProgress("Starting clean Linux session...")
                start(context)
                try {
                    HardwareBridges.startAll(context, env, scope)
                } catch (_: Exception) {}

                onProgress("Session rebooted successfully ($cleaned locks purged)")
            }
        }

        fun forceKillAll(
            context: Context,
            env: LinuxEnvironment,
            runner: ProotRunner,
            scope: CoroutineScope,
            onProgress: (String) -> Unit = {}
        ) {
            scope.launch(Dispatchers.IO) {
                onProgress("Force terminating guest and proot processes...")
                try {
                    runner.killSync()
                } catch (_: Exception) {}
                try {
                    Runtime.getRuntime().exec(
                        arrayOf(
                            "sh",
                            "-c",
                            "pkill -9 -f proot 2>/dev/null; pkill -9 -f libproot 2>/dev/null; pkill -9 -f xfce4 2>/dev/null; pkill -9 -f bash 2>/dev/null; pkill -9 -f pulseaudio 2>/dev/null || true"
                        )
                    ).waitFor()
                } catch (_: Exception) {}
                try {
                    HardwareBridges.stopAll()
                } catch (_: Exception) {}
                stop(context)
                val cleaned = cleanSockets(env)
                onProgress("All processes killed • $cleaned locks cleared")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val env by lazy { LinuxEnvironment(this) }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenStick:SessionWakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max

        // Launch all hardware and software bridges
        try {
            HardwareBridges.startAll(this, env, serviceScope)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        HardwareBridges.stopAll()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenStick Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active Linux background session"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenStick Linux")
            .setContentText("Linux environment is active in background")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
