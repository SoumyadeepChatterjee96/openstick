package io.breadstick.x11

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
        private var nativeStarted: Boolean = false

        @Volatile
        var isServerRunning: Boolean = false
            private set

        private var entry: CmdEntryPoint? = null
        private var serverThread: HandlerThread? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    val socketPath: File
        get() = File(env.tmpDir, ".X11-unix/X$display")

    fun openRenderFd(): Int {
        val cmdEntryPoint: CmdEntryPoint
        synchronized(X11Server::class.java) {
            cmdEntryPoint = entry ?: CmdEntryPoint().also { entry = it }
        }
        val pfd = cmdEntryPoint.getXConnection()
            ?: throw IllegalStateException("Failed to acquire X11 connection from CmdEntryPoint")
        return pfd.detachFd()
    }

    fun start(onStarted: ((Boolean) -> Unit)? = null) {
        synchronized(X11Server::class.java) {
            if (nativeStarted && socketPath.exists()) {
                isServerRunning = true
                mainHandler.post { onStarted?.invoke(true) }
                return
            }

            try {
                env.ensureDirectories()
                val x11Dir = File(env.tmpDir, ".X11-unix")
                x11Dir.mkdirs()

                socketPath.delete()
                File(env.tmpDir, ".X$display-lock").delete()

                Os.setenv("TMPDIR", env.tmpDir.absolutePath, true)
                Os.setenv("BREADSTICK_ROOTLESS", "0", true)

                // Resolve XKB configuration root from Debian guest or bundled assets
                env.extractXkb()
                val xkbCandidates = listOf(
                    File(env.rootfsDir, "usr/share/X11/xkb"),
                    File(env.rootfsDir, "usr/share/xkeyboard-config-2"),
                    File(env.context.filesDir, "xkb"),
                    File("/data/local/tmp/xkb")
                )
                val validXkb = xkbCandidates.firstOrNull { it.isDirectory }
                if (validXkb != null) {
                    Os.setenv("XKB_CONFIG_ROOT", validXkb.absolutePath, true)
                }

                val logFile = File(env.context.filesDir, "xserver.log")
                try { logFile.writeText("") } catch (_: Throwable) {}
                Os.setenv("XLORIE_LOG_FILE", logFile.absolutePath, true)

                val thread = HandlerThread("openstick-x11").apply { start() }
                serverThread = thread

                Handler(thread.looper).post {
                    try {
                        Log.i(TAG, "Starting X11 server on :$display...")
                        val success = CmdEntryPoint.start(arrayOf(":$display", "-ac", "-noreset"))
                        isServerRunning = success
                        nativeStarted = success
                        Log.i(TAG, "CmdEntryPoint.start returned $success")
                        mainHandler.post { onStarted?.invoke(success) }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to start X11 server", e)
                        isServerRunning = false
                        mainHandler.post { onStarted?.invoke(false) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize X11 environment", e)
                mainHandler.post { onStarted?.invoke(false) }
            }
        }
    }

    fun stop() {
        // We do not terminate dix_main inside the process because Xorg cannot be re-initialized,
        // but we flag that active client sessions are disconnected.
        isServerRunning = false
    }

    fun isConnected(): Boolean {
        return try {
            CmdEntryPoint.connected()
        } catch (_: Throwable) {
            false
        }
    }
}
