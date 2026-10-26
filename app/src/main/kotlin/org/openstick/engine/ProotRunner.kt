package org.openstick.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class ProotRunner(
    private val env: LinuxEnvironment,
    private val scope: CoroutineScope
) {
    private var process: Process? = null
    private var stdin: OutputStream? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    fun start(
        command: List<String>? = null,
        workdir: String? = null,
        asRoot: Boolean = false,
        onOutput: ((String) -> Unit)? = null
    ) {
        if (_isRunning.value) return

        env.ensureDirectories()
        val configuredUser = env.getConfiguredUsername()
        val effectiveWorkdir = workdir ?: if (asRoot) "/root" else "/home/$configuredUser"

        val effectiveCommand = command ?: if (File(env.rootfsDir, "usr/bin/script").exists()) {
            listOf("/usr/bin/script", "-qfc", "exec /bin/bash --login", "/dev/null")
        } else {
            listOf("/bin/bash", "--login")
        }

        val fullCmd = env.buildProotCommand(effectiveWorkdir, asRoot, effectiveCommand)
        val envVars = env.getEnvironmentVariables(asRoot)

        scope.launch(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(fullCmd)
                pb.directory(env.rootfsDir)
                pb.environment().putAll(env.prootEnv())
                pb.environment().putAll(envVars)
                pb.redirectErrorStream(true)

                val proc = pb.start()
                process = proc
                stdin = proc.outputStream
                _isRunning.value = true

                val buffer = ByteArray(4096)
                val stream: InputStream = proc.inputStream
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    val text = String(buffer, 0, read)
                    _output.value += text
                    withContext(Dispatchers.Main) {
                        onOutput?.invoke(text)
                    }
                }

                proc.waitFor()
            } catch (e: Throwable) {
                val errorMsg = "Process error: ${e.message ?: e.javaClass.simpleName}\n"
                _output.value += errorMsg
                withContext(Dispatchers.Main) {
                    onOutput?.invoke(errorMsg)
                }
            } finally {
                _isRunning.value = false
                process = null
                stdin = null
            }
        }
    }

    fun write(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    fun write(bytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                stdin?.write(bytes)
                stdin?.flush()
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        scope.launch(Dispatchers.IO) {
            try {
                write("exit\n")
                kotlinx.coroutines.delay(200)
                process?.destroy()
            } catch (_: Exception) {}
        }
    }

    fun kill() {
        scope.launch(Dispatchers.IO) {
            try {
                process?.destroyForcibly()
            } catch (_: Exception) {}
            try {
                // Kill any orphaned proot processes
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "killall -9 proot 2>/dev/null || true")).waitFor()
            } catch (_: Exception) {}
            _isRunning.value = false
            process = null
            stdin = null
        }
    }
}
