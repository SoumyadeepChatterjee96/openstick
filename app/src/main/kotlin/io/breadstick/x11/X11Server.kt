package io.breadstick.x11

import android.os.Handler
import android.os.HandlerThread
import android.system.Os
import android.util.Log
import org.openstick.engine.LinuxEnvironment
import java.io.File

class X11Server(
    val env: LinuxEnvironment,
    val display: Int = 0
) {
    companion object {
        private const val TAG = "OpenStick-X11"
        @Volatile
        var isServerRunning: Boolean = false
            private set
    }

    private var serverThread: HandlerThread? = null

    val socketPath: File
        get() = File(env.tmpDir, ".X11-unix/X$display")

    fun start(onStarted: ((Boolean) -> Unit)? = null) {
        if (isServerRunning) {
            onStarted?.invoke(true)
            return
        }

        try {
            env.ensureDirectories()
            val x11Dir = File(env.tmpDir, ".X11-unix")
            x11Dir.mkdirs()

            socketPath.delete()
            File(env.tmpDir, ".X$display-lock").delete()

            Os.setenv("TMPDIR", env.tmpDir.absolutePath, true)

            val xkbDir = File(env.rootfsDir, "usr/share/X11/xkb")
            if (xkbDir.isDirectory) {
                Os.setenv("XKB_CONFIG_ROOT", xkbDir.absolutePath, true)
            }

            val logFile = File(env.context.filesDir, "xserver.log")
            Os.setenv("XLORIE_LOG_FILE", logFile.absolutePath, true)

            val thread = HandlerThread("openstick-x11").apply { start() }
            serverThread = thread

            Handler(thread.looper).post {
                try {
                    Log.i(TAG, "Starting X11 server on :$display...")
                    val success = CmdEntryPoint.start(arrayOf(":$display", "-ac", "-noreset"))
                    isServerRunning = success
                    Log.i(TAG, "CmdEntryPoint.start returned $success")
                    onStarted?.invoke(success)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to start X11 server", e)
                    isServerRunning = false
                    onStarted?.invoke(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize X11 environment", e)
            onStarted?.invoke(false)
        }
    }

    fun stop() {
        isServerRunning = false
        serverThread?.quitSafely()
        serverThread = null
        socketPath.delete()
    }

    fun isConnected(): Boolean {
        return try {
            CmdEntryPoint.connected()
        } catch (_: Throwable) {
            false
        }
    }
}
