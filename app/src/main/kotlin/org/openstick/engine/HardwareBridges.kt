package org.openstick.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import java.io.File

object HardwareBridges {
    private const val NOTIF_CHANNEL_ID = "openstick_guest_notifs"

    var cameraBridgeEnabled = true
    var micBridgeEnabled = true
    var audioOutputEnabled = true
    var notificationBridgeEnabled = true
    var calendarSyncEnabled = true
    var clipboardSyncEnabled = true
    var printingBridgeEnabled = true
    var sshServerEnabled = false
    var vncServerEnabled = false
    var processLimit = 32

    fun applyDnsConfig(env: LinuxEnvironment, dnsChoice: String, customIp: String = "") {
        val resolvConf = File(env.rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()

        val dnsServers = when (dnsChoice) {
            "Google" -> listOf("8.8.8.8", "8.8.4.4")
            "Cloudflare" -> listOf("1.1.1.1", "1.0.0.1")
            "OpenDNS" -> listOf("208.67.222.222", "208.67.220.220")
            "Custom" -> listOf(customIp.ifEmpty { "1.1.1.1" })
            else -> listOf("1.1.1.1", "8.8.8.8")
        }

        val content = dnsServers.joinToString("\n") { "nameserver $it" } + "\n"
        try {
            resolvConf.writeText(content)
        } catch (_: Exception) {}
    }

    fun startSshServer(runner: ProotRunner) {
        sshServerEnabled = true
        runner.start(
            command = listOf(
                "/bin/sh", "-c",
                "service dropbear start 2>/dev/null || /usr/sbin/dropbear -p 2222 2>/dev/null || service ssh start"
            )
        )
    }

    fun stopSshServer(runner: ProotRunner) {
        sshServerEnabled = false
        runner.start(
            command = listOf("/bin/sh", "-c", "killall dropbear 2>/dev/null || killall sshd 2>/dev/null")
        )
    }

    fun startVncServer(runner: ProotRunner) {
        vncServerEnabled = true
        runner.start(
            command = listOf(
                "/bin/sh", "-c",
                "x11vnc -display :0 -rfbport 5900 -shared -forever -bg 2>/dev/null || tightvncserver :1"
            )
        )
    }

    fun stopVncServer(runner: ProotRunner) {
        vncServerEnabled = false
        runner.start(
            command = listOf("/bin/sh", "-c", "killall x11vnc 2>/dev/null || killall Xvnc 2>/dev/null")
        )
    }

    fun repairPackages(runner: ProotRunner, onProgress: (String) -> Unit) {
        runner.start(
            command = listOf(
                "/bin/sh", "-c",
                "export DEBIAN_FRONTEND=noninteractive; dpkg --configure -a 2>&1; apt-get -f install -y 2>&1; apt-get clean 2>&1; echo 'Repair complete.'"
            ),
            asRoot = true,
            onOutput = onProgress
        )
    }

    fun forceStopSession(runner: ProotRunner, onOutput: (String) -> Unit) {
        runner.start(
            command = listOf("/bin/sh", "-c", "kill -9 -1 2>/dev/null; echo 'Session stopped.'"),
            asRoot = true,
            onOutput = onOutput
        )
    }

    fun showGuestNotification(context: Context, title: String, text: String) {
        if (!notificationBridgeEnabled) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Linux Guest Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        manager.notify((System.currentTimeMillis() % 100000).toInt(), notif)
    }
}
