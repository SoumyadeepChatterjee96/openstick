package org.openstick.terminal

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.ProotRunner
import java.io.File
import java.io.OutputStream

enum class SplitMode {
    SINGLE,
    HORIZONTAL,
    VERTICAL
}

/**
 * A terminal tab backed by a real VT100 emulator.
 * Instead of stripping ANSI codes, the emulator parses them to produce
 * styled AnnotatedString rows with colour, bold, underline, etc.
 */
class TerminalTab(
    val id: String,
    val title: String,
    private val env: LinuxEnvironment,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TerminalTab"
    }

    private var process: Process? = null
    private var stdin: OutputStream? = null

    // The VT100 emulator: 80 cols × 40 rows (will be adjusted by UI)
    val emulator = TerminalEmulator(
        cols = 80,
        rows = 40,
        respond = { bytes -> write(bytes) },
        onTitle = { /* could update tab title */ },
        onBell = { /* could vibrate */ }
    )

    // Snapshot exposed to Compose UI
    var snapshot by mutableStateOf(emulator.snapshot())
        private set

    var isRunning by mutableStateOf(false)
        private set

    // Track whether the process has been started at all
    private var started = false

    fun start() {
        if (started) return
        if (!env.isInstalled()) return
        started = true

        val configuredUser = env.getConfiguredUsername()
        val home = "/home/$configuredUser"
        val cols = emulator.cols
        val rows = emulator.rows

        // Build shell command using `script` for PTY allocation (exactly like Breadstick)
        val shellCmd = if (File(env.rootfsDir, "usr/bin/script").exists()) {
            listOf(
                "/usr/bin/script", "-qfc",
                "stty cols $cols rows $rows 2>/dev/null; exec /bin/bash --login",
                "/dev/null"
            )
        } else {
            listOf("/bin/bash", "--login")
        }

        val fullCmd = env.buildProotCommand(home, false, shellCmd)
        val envVars = env.getEnvironmentVariables(false)

        scope.launch(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder(fullCmd)
                pb.directory(env.rootfsDir)
                pb.environment().putAll(env.prootEnv())
                pb.environment().putAll(envVars)
                pb.environment()["TERM"] = "xterm-256color"
                pb.redirectErrorStream(true)

                val proc = pb.start()
                process = proc
                stdin = proc.outputStream
                isRunning = true

                // Read loop: feed raw bytes into the VT100 emulator
                val buffer = ByteArray(8192)
                val stream = proc.inputStream
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    synchronized(emulator) {
                        emulator.feed(buffer, read)
                        snapshot = emulator.snapshot()
                    }
                }

                proc.waitFor()
            } catch (e: Throwable) {
                Log.e(TAG, "Process error", e)
                val msg = "Process error: ${e.message ?: e.javaClass.simpleName}\r\n"
                synchronized(emulator) {
                    emulator.feed(msg.toByteArray(), msg.length)
                    snapshot = emulator.snapshot()
                }
            } finally {
                isRunning = false
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

    fun resize(cols: Int, rows: Int) {
        if (cols < 1 || rows < 1) return
        synchronized(emulator) {
            emulator.resize(cols, rows)
            snapshot = emulator.snapshot()
        }
        // Signal the guest shell about the new size via stty
        write("stty cols $cols rows $rows 2>/dev/null\n")
    }

    fun stop() {
        try {
            write("exit\n")
            Thread.sleep(100)
            process?.destroy()
        } catch (_: Exception) {}
        isRunning = false
        process = null
        stdin = null
        started = false
    }
}

class TerminalSessionManager(
    private val env: LinuxEnvironment,
    private val scope: CoroutineScope
) {
    val tabs = mutableStateListOf<TerminalTab>()
    var activeTabIndex by mutableStateOf(0)
    var splitMode by mutableStateOf(SplitMode.SINGLE)
    var fontSize by mutableStateOf(13)

    // Sticky modifier states for the key bar
    var ctrlActive by mutableStateOf(false)
    var altActive by mutableStateOf(false)

    init {
        createTab("Terminal 1")
    }

    val activeTab: TerminalTab?
        get() = if (tabs.isNotEmpty() && activeTabIndex in tabs.indices) tabs[activeTabIndex] else null

    fun createTab(title: String = "Terminal ${tabs.size + 1}"): TerminalTab {
        val tab = TerminalTab(
            id = "tab_${System.currentTimeMillis()}",
            title = title,
            env = env,
            scope = scope
        )
        tabs.add(tab)
        activeTabIndex = tabs.size - 1
        tab.start()
        return tab
    }

    fun closeTab(index: Int) {
        if (index in tabs.indices) {
            val tab = tabs[index]
            tab.stop()
            tabs.removeAt(index)
            if (activeTabIndex >= tabs.size) {
                activeTabIndex = (tabs.size - 1).coerceAtLeast(0)
            }
        }
    }

    fun sendInput(text: String) {
        var data = text
        if (ctrlActive) {
            ctrlActive = false
            if (data.length == 1) {
                val ch = data[0]
                if (ch in 'a'..'z') {
                    data = (ch - 'a' + 1).toChar().toString()
                } else if (ch in 'A'..'Z') {
                    data = (ch - 'A' + 1).toChar().toString()
                }
            }
        }
        if (altActive) {
            altActive = false
            data = "\u001b$data"
        }
        activeTab?.write(data)
    }

    fun sendKey(key: String) {
        when (key) {
            "ESC" -> activeTab?.write("\u001b")
            "TAB" -> activeTab?.write("\t")
            "CTRL" -> { ctrlActive = !ctrlActive }
            "ALT" -> { altActive = !altActive }
            "UP" -> activeTab?.write(if (activeTab?.emulator?.applicationCursorKeys == true) "\u001bOA" else "\u001b[A")
            "DOWN" -> activeTab?.write(if (activeTab?.emulator?.applicationCursorKeys == true) "\u001bOB" else "\u001b[B")
            "RIGHT" -> activeTab?.write(if (activeTab?.emulator?.applicationCursorKeys == true) "\u001bOC" else "\u001b[C")
            "LEFT" -> activeTab?.write(if (activeTab?.emulator?.applicationCursorKeys == true) "\u001bOD" else "\u001b[D")
            "ENTER" -> activeTab?.write("\r")
            "CTRL+C" -> activeTab?.write("\u0003")
            "CTRL+D" -> activeTab?.write("\u0004")
            "CTRL+Z" -> activeTab?.write("\u001a")
            "CTRL+L" -> activeTab?.write("\u000c")
            "HOME" -> activeTab?.write("\u001b[H")
            "END" -> activeTab?.write("\u001b[F")
            "PGUP" -> activeTab?.write("\u001b[5~")
            "PGDN" -> activeTab?.write("\u001b[6~")
            else -> sendInput(key)
        }
    }
}
