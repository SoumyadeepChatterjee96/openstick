package org.openstick.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.MailTo
import org.openstick.MainActivity
import java.io.File
import kotlin.random.Random

/**
 * Bridges Linux guest events to Android host:
 * 1. Linux `notify-send` -> Android Notification bar
 * 2. Linux `xdg-open <url>` -> Android Browser / Intent
 */
class GuestEventBridge(
    private val context: Context,
    private val rootfsDir: File
) {
    companion object {
        private const val TAG = "GuestEventBridge"
        const val CHANNEL_ID = "openstick_guest_notifs"
        const val NOTIFY_FILE = ".openstick-notify"
        const val NOTIFY_BSL_FILE = ".bsl-notify"
        const val OPEN_FILE = ".openstick-open"
        const val OPEN_BSL_FILE = ".bsl-open"
    }

    private val tmpDir = File(rootfsDir, "tmp")
    private var observer: FileObserver? = null
    private val sessionSalt = Random.nextInt() and 0x7FFFFFFF

    fun start() {
        if (observer != null) return
        tmpDir.mkdirs()
        ensureChannel()

        // Create observer for /tmp
        val obs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(tmpDir, CLOSE_WRITE or MOVED_TO or CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(path)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(tmpDir.absolutePath, CLOSE_WRITE or MOVED_TO or CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    handleFileEvent(path)
                }
            }
        }

        obs.startWatching()
        observer = obs

        // Initial drain of any pending requests
        drain(File(tmpDir, OPEN_FILE)) { handleOpen(it) }
        drain(File(tmpDir, OPEN_BSL_FILE)) { handleOpen(it) }
        drain(File(tmpDir, NOTIFY_FILE)) { handleNotify(it) }
        drain(File(tmpDir, NOTIFY_BSL_FILE)) { handleNotify(it) }
    }

    fun stop() {
        try {
            observer?.stopWatching()
        } catch (_: Exception) {}
        observer = null
    }

    private fun handleFileEvent(path: String?) {
        if (path == null) return
        when (path) {
            OPEN_FILE, OPEN_BSL_FILE -> {
                drain(File(tmpDir, path)) { handleOpen(it) }
            }
            NOTIFY_FILE, NOTIFY_BSL_FILE -> {
                drain(File(tmpDir, path)) { handleNotify(it) }
            }
        }
    }

    private fun drain(file: File, handler: (String) -> Unit) {
        if (!file.exists() || file.length() == 0L) return
        try {
            val lines = file.readLines()
            file.writeText("") // Clear immediately
            for (line in lines.take(16)) {
                if (line.isNotBlank()) {
                    try {
                        handler(line)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling line: $line", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to drain ${file.name}", e)
        }
    }

    private fun handleOpen(line: String) {
        val trimmed = line.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith(MailTo.MAILTO_SCHEME, ignoreCase = true)
        ) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open URL: $trimmed", e)
            }
        }
    }

    private fun handleNotify(line: String) {
        val parts = line.split("\t")
        val app = parts.getOrNull(0)?.trim()?.ifEmpty { "OpenStick" } ?: "OpenStick"
        val summary = parts.getOrNull(1)?.trim() ?: ""
        val body = parts.getOrNull(2)?.trim() ?: ""

        val notifId = (System.currentTimeMillis() % 100000).toInt()
        postNotification(notifId, app, summary, body)
    }

    private fun postNotification(id: Int, app: String, summary: String, body: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (summary.isNotBlank()) "$app: $summary" else app
        val content = body.ifBlank { summary }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(id, notif)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Linux App Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications forwarded from Linux applications"
            }
            manager.createNotificationChannel(channel)
        }
    }
}
