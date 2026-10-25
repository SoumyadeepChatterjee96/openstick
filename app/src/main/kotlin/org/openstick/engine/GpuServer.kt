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
            socketPath.delete()

            val pb = ProcessBuilder(bin.absolutePath)
            pb.directory(env.tmpDir)
            pb.environment()["TMPDIR"] = env.tmpDir.absolutePath
            pb.redirectErrorStream(true)

            process = pb.start()
            isRunning = true
            Log.i(TAG, "VirGL 3D server started successfully.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VirGL server", e)
            isRunning = false
            return false
        }
    }

    fun stop() {
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        isRunning = false
    }
}
