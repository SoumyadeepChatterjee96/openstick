package org.openstick.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.ProotRunner

enum class SplitMode {
    SINGLE,
    HORIZONTAL,
    VERTICAL
}

class TerminalTab(
    val id: String,
    val title: String,
    val runner: ProotRunner
) {
    companion object {
        private val ANSI_REGEX = Regex("\u001B\\[[;?0-9]*[a-zA-Z]")
    }

    var outputText by mutableStateOf("")
        private set

    fun appendOutput(text: String) {
        val clean = text.replace(ANSI_REGEX, "").replace("\r\n", "\n").replace("\r", "\n")
        if (outputText.length > 50000) {
            outputText = outputText.takeLast(30000) + clean
        } else {
            outputText += clean
        }
    }

    fun clear() {
        outputText = ""
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

    init {
        createTab("Terminal 1")
    }

    val activeTab: TerminalTab?
        get() = if (tabs.isNotEmpty() && activeTabIndex in tabs.indices) tabs[activeTabIndex] else null

    fun createTab(title: String = "Terminal ${tabs.size + 1}"): TerminalTab {
        val runner = ProotRunner(env, scope)
        val tab = TerminalTab(
            id = "tab_${System.currentTimeMillis()}",
            title = title,
            runner = runner
        )
        tabs.add(tab)
        activeTabIndex = tabs.size - 1

        if (env.isInstalled()) {
            runner.start { text ->
                tab.appendOutput(text)
            }
        }
        return tab
    }

    fun restartActive() {
        activeTab?.let { tab ->
            tab.runner.stop()
            tab.clear()
            if (env.isInstalled()) {
                tab.runner.start { text ->
                    tab.appendOutput(text)
                }
            }
        }
    }

    fun closeTab(index: Int) {
        if (index in tabs.indices) {
            val tab = tabs[index]
            tab.runner.stop()
            tabs.removeAt(index)
            if (activeTabIndex >= tabs.size) {
                activeTabIndex = (tabs.size - 1).coerceAtLeast(0)
            }
        }
    }

    fun sendInput(input: String) {
        activeTab?.runner?.write(input)
    }

    fun sendKey(key: String) {
        when (key) {
            "ESC" -> sendInput("\u001b")
            "TAB" -> sendInput("\t")
            "UP" -> sendInput("\u001b[A")
            "DOWN" -> sendInput("\u001b[B")
            "RIGHT" -> sendInput("\u001b[C")
            "LEFT" -> sendInput("\u001b[D")
            "ENTER" -> sendInput("\n")
            "CTRL+C" -> sendInput("\u0003")
            "CTRL+D" -> sendInput("\u0004")
            "CTRL+Z" -> sendInput("\u001a")
            "CTRL+L" -> {
                sendInput("\u000c")
                activeTab?.clear()
            }
            else -> sendInput(key)
        }
    }
}
