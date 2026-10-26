package org.openstick.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.breadstick.x11.LorieView
import java.io.File
import java.util.concurrent.Executors

/**
 * Bridges clipboard between Android and Linux:
 * 1. Enables native X11 clipboard sync via LorieView.setClipboardSyncEnabled
 * 2. Synchronizes file-based clipboard for CLI / terminal sessions
 */
class ClipboardBridge(
    private val context: Context,
    private val rootfsDir: File
) {
    companion object {
        private const val TAG = "ClipboardBridge"
        private const val MAX_CLIP_TEXT_LEN = 1024 * 1024 // 1 MB limit
    }

    private val tmpDir: File = if (rootfsDir.name == "tmp") {
        rootfsDir
    } else if (File(rootfsDir.parentFile, "tmp").exists()) {
        File(rootfsDir.parentFile, "tmp")
    } else {
        File(rootfsDir, "tmp")
    }
    private val clipDir = File(tmpDir, ".openstick-clip")
    private val toAndroidDir = File(clipDir, "to-android")
    private val toLinuxFile = File(clipDir, "to-linux")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var observer: FileObserver? = null
    private var lastOfferText: String? = null
    private var lastImportText: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onAndroidClipboardChanged()
    }

    fun start() {
        clipDir.mkdirs()
        toAndroidDir.mkdirs()

        // 1. Enable native X11 LorieView clipboard sync
        mainHandler.post {
            try {
                LorieView.setClipboardSyncEnabled(true, true)
            } catch (e: Throwable) {
                Log.w(TAG, "LorieView native clipboard sync init: ${e.message}")
            }
        }

        // 2. Register Android clipboard listener
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.addPrimaryClipChangedListener(clipListener)

        // 3. Watch to-android directory for Linux CLI copy events
        val obs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(toAndroidDir, CLOSE_WRITE or MOVED_TO or CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    importFromLinux()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(toAndroidDir.absolutePath, CLOSE_WRITE or MOVED_TO or CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    importFromLinux()
                }
            }
        }

        obs.startWatching()
        observer = obs
        importFromLinux()
    }

    fun stop() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.removePrimaryClipChangedListener(clipListener)

        try {
            observer?.stopWatching()
        } catch (_: Exception) {}
        observer = null
    }

    /**
     * Android clipboard changed -> push to Linux file
     */
    private fun onAndroidClipboardChanged() {
        executor.execute {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@execute
                val clip = cm.primaryClip ?: return@execute
                if (clip.itemCount > 0) {
                    val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return@execute
                    if (text == lastImportText || text == lastOfferText) return@execute
                    if (text.length > MAX_CLIP_TEXT_LEN) return@execute

                    lastOfferText = text
                    clipDir.mkdirs()
                    toLinuxFile.writeText(text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to offer Android clipboard to Linux", e)
            }
        }
    }

    /**
     * Linux CLI copied text -> push to Android clipboard
     */
    private fun importFromLinux() {
        executor.execute {
            try {
                val clipFile = File(toAndroidDir, "clip.txt")
                val clipJson = File(toAndroidDir, "clip.json")
                val text = when {
                    clipFile.exists() -> clipFile.readText()
                    clipJson.exists() -> clipJson.readText()
                    else -> return@execute
                }
                if (text.isEmpty() || text == lastOfferText || text == lastImportText) return@execute
                lastImportText = text

                mainHandler.post {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@post
                    cm.setPrimaryClip(ClipData.newPlainText("OpenStick", text))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import clipboard from Linux", e)
            }
        }
    }
}
