package org.openstick

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import io.breadstick.x11.X11Server
import org.openstick.apps.AppsScreen
import org.openstick.desktop.DesktopScreen
import org.openstick.engine.HardwareBridges
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.ProotRunner
import org.openstick.engine.ResourceMonitor
import org.openstick.home.HomeScreen
import org.openstick.monitor.MonitorScreen
import org.openstick.settings.SettingsScreen
import org.openstick.terminal.TerminalScreen
import org.openstick.terminal.TerminalSessionManager
import org.openstick.ui.theme.AmoledBackground
import org.openstick.ui.theme.AmoledContainer
import org.openstick.ui.theme.AppThemeMode
import org.openstick.ui.theme.OpenStickTheme

enum class Screen(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    APPS("Apps", Icons.Default.Apps),
    TERMINAL("Terminal", Icons.Default.Terminal),
    DESKTOP("Desktop", Icons.Default.DesktopWindows),
    MONITOR("Monitor", Icons.Default.Speed),
    SETTINGS("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val env by lazy { LinuxEnvironment(this) }
    private val x11Server by lazy { X11Server(env) }
    private val mainRunner by lazy { ProotRunner(env, lifecycleScope) }
    private val terminalManager by lazy { TerminalSessionManager(env, lifecycleScope) }
    private val resourceMonitor by lazy { ResourceMonitor(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("openstick_prefs", MODE_PRIVATE)
        val initialThemeOrdinal = prefs.getInt("theme_mode", AppThemeMode.AMOLED_BLACK.ordinal)
        val initialTheme = AppThemeMode.values().getOrElse(initialThemeOrdinal) { AppThemeMode.AMOLED_BLACK }

        // Start hardware and software bridges
        try {
            HardwareBridges.startAll(this, env, lifecycleScope)
        } catch (_: Exception) {}

        setContent {
            var currentTheme by remember { mutableStateOf(initialTheme) }
            var currentScreen by remember { mutableStateOf(Screen.HOME) }


            // Predictive Back gesture handler (minimizes instead of killing session)
            BackHandler {
                if (currentScreen != Screen.HOME) {
                    currentScreen = Screen.HOME
                } else {
                    moveTaskToBack(true)
                }
            }

            OpenStickTheme(themeMode = currentTheme) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = AmoledBackground,
                    bottomBar = {
                        val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                        if (!isImeVisible && currentScreen != Screen.DESKTOP && currentScreen != Screen.TERMINAL) {
                            NavigationBar(
                                containerColor = AmoledContainer
                            ) {
                                Screen.values().forEach { screen ->
                                    val selected = currentScreen == screen
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { currentScreen = screen },
                                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                                        label = { Text(screen.title) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primary,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    }
                ) { paddingValues ->
                    val modifier = if (currentScreen == Screen.TERMINAL || currentScreen == Screen.DESKTOP) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.fillMaxSize().padding(paddingValues)
                    }
                    when (currentScreen) {
                        Screen.HOME -> HomeScreen(
                            env = env,
                            runner = mainRunner,
                            onNavigateTerminal = { currentScreen = Screen.TERMINAL },
                            onNavigateDesktop = {
                                currentScreen = Screen.DESKTOP
                            },
                            onNavigateApps = { currentScreen = Screen.APPS },
                            onNavigateMonitor = { currentScreen = Screen.MONITOR },
                            modifier = modifier
                        )
                        Screen.APPS -> AppsScreen(
                            env = env,
                            runner = mainRunner,
                            modifier = modifier
                        )
                        Screen.TERMINAL -> TerminalScreen(
                            manager = terminalManager,
                            modifier = modifier,
                            onNavigateHome = { currentScreen = Screen.HOME }
                        )
                        Screen.DESKTOP -> DesktopScreen(
                            env = env,
                            x11Server = x11Server,
                            runner = mainRunner,
                            modifier = modifier
                        )
                        Screen.MONITOR -> MonitorScreen(
                            monitor = resourceMonitor,
                            modifier = modifier
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            env = env,
                            runner = mainRunner,
                            currentTheme = currentTheme,
                            onThemeChange = { newTheme ->
                                currentTheme = newTheme
                                prefs.edit().putInt("theme_mode", newTheme.ordinal).apply()
                            },
                            modifier = modifier
                        )
                    }
                }
            }
        }
    }
}
