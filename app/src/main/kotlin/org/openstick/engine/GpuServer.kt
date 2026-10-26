package org.openstick.engine

import android.util.Log
import java.io.File

object GpuServer {
    private const val TAG = "OpenStick-GPU"
    private var process: Process? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start(env: LinuxEnvironment): Boolean {
        if (isRunning) return true
        val bin = env.virglServerBin
        if (!bin.exists() || !bin.canExecute()) {
            Log.w(TAG, "VirGL server binary not found or not executable: ${bin.absolutePath}")
            return false
        }

        try {
            val socketPath = File(env.tmpDir, ".virgl_test")
            try { socketPath.delete() } catch (_: Exception) {}

            val pb = ProcessBuilder(
                bin.absolutePath,
                "--no-fork",
                "--multi-clients",
                "--use-egl-surfaceless",
                "--use-gles",
                "--socket-path", socketPath.absolutePath
            )
            pb.directory(env.tmpDir)
            pb.environment()["TMPDIR"] = env.tmpDir.absolutePath
            pb.redirectErrorStream(true)

            val proc = pb.start()
            process = proc
            isRunning = true
            Log.i(TAG, "VirGL 3D server started successfully at ${socketPath.absolutePath}.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VirGL server", e)
            isRunning = false
            return false
        }
    }

    fun stop() {
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {}
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f libvirgl_server.so 2>/dev/null || true")).waitFor()
        } catch (_: Exception) {}
        process = null
        isRunning = false
    }
}
