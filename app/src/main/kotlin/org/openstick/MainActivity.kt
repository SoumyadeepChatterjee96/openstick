package org.openstick

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.openstick.ui.theme.*

enum class Screen(val title: String, val subtitle: String, val icon: ImageVector) {
    HOME("Home", "Overview & Bridges", Icons.Default.Home),
    APPS("Apps", "1-Click Apps & Packages", Icons.Default.Apps),
    TERMINAL("Terminal", "Zsh / Bash Shell", Icons.Default.Terminal),
    DESKTOP("Desktop", "X11 XFCE GUI", Icons.Default.DesktopWindows),
    MONITOR("Monitor", "Live System Metrics", Icons.Default.Speed),
    SETTINGS("Settings", "Preferences & Scale", Icons.Default.Settings)
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
        val initialAccentKey = prefs.getString("accent_color", AppAccent.CYAN.name) ?: AppAccent.CYAN.name
        val initialAccent = try { AppAccent.valueOf(initialAccentKey) } catch (_: Exception) { AppAccent.CYAN }

        // Restore user-enabled bridges (strictly false by default)
        HardwareBridges.cameraBridgeEnabled = prefs.getBoolean("bridge_camera", false)
        HardwareBridges.micBridgeEnabled = prefs.getBoolean("bridge_mic", false)
        HardwareBridges.audioOutputEnabled = prefs.getBoolean("bridge_audio", false)
        HardwareBridges.notificationBridgeEnabled = prefs.getBoolean("bridge_notif", false)
        HardwareBridges.calendarSyncEnabled = prefs.getBoolean("bridge_calendar", false)
        HardwareBridges.clipboardSyncEnabled = prefs.getBoolean("bridge_clipboard", false)
        HardwareBridges.printingBridgeEnabled = prefs.getBoolean("bridge_printing", false)

        // Apply saved DNS choice
        val savedDns = prefs.getString("selected_dns", "Cloudflare") ?: "Cloudflare"
        val customDns = prefs.getString("custom_dns_ip", "") ?: ""
        HardwareBridges.applyDnsConfig(env, savedDns, customDns)

        // Start hardware and software bridges safely
        try {
            HardwareBridges.startAll(this, env, lifecycleScope)
        } catch (_: Exception) {}

        setContent {
            var currentTheme by remember { mutableStateOf(initialTheme) }
            var currentAccent by remember { mutableStateOf(initialAccent) }
            var currentScreen by remember { mutableStateOf(Screen.HOME) }
            var isNavMenuOpen by remember { mutableStateOf(false) }

            // Predictive Back gesture handler (minimizes instead of killing session)
            BackHandler {
                if (isNavMenuOpen) {
                    isNavMenuOpen = false
                } else if (currentScreen != Screen.HOME) {
                    currentScreen = Screen.HOME
                } else {
                    moveTaskToBack(true)
                }
            }

            OpenStickTheme(themeMode = currentTheme, accent = currentAccent) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = AmoledBackground,
                    bottomBar = {} // 100% free vertical screen real estate!
                ) { _ ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 1. Screen Content
                        val contentModifier = if (currentScreen == Screen.TERMINAL || currentScreen == Screen.DESKTOP) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .padding(top = 46.dp)
                        }

                        when (currentScreen) {
                            Screen.HOME -> HomeScreen(
                                env = env,
                                runner = mainRunner,
                                onNavigateTerminal = { currentScreen = Screen.TERMINAL },
                                onNavigateDesktop = { currentScreen = Screen.DESKTOP },
                                onNavigateApps = { currentScreen = Screen.APPS },
                                onNavigateMonitor = { currentScreen = Screen.MONITOR },
                                modifier = contentModifier
                            )
                            Screen.APPS -> AppsScreen(
                                env = env,
                                runner = mainRunner,
                                modifier = contentModifier
                            )
                            Screen.TERMINAL -> TerminalScreen(
                                manager = terminalManager,
                                modifier = contentModifier,
                                onNavigateHome = { currentScreen = Screen.HOME }
                            )
                            Screen.DESKTOP -> DesktopScreen(
                                env = env,
                                x11Server = x11Server,
                                runner = mainRunner,
                                modifier = contentModifier
                            )
                            Screen.MONITOR -> MonitorScreen(
                                monitor = resourceMonitor,
                                modifier = contentModifier
                            )
                            Screen.SETTINGS -> SettingsScreen(
                                env = env,
                                runner = mainRunner,
                                currentTheme = currentTheme,
                                onThemeChange = { newTheme ->
                                    currentTheme = newTheme
                                    prefs.edit().putInt("theme_mode", newTheme.ordinal).apply()
                                },
                                currentAccent = currentAccent,
                                onAccentChange = { newAccent ->
                                    currentAccent = newAccent
                                    prefs.edit().putString("accent_color", newAccent.name).apply()
                                },
                                modifier = contentModifier
                            )
                        }

                        // 2. Floating Navigation Controls
                        if (currentScreen == Screen.DESKTOP || currentScreen == Screen.TERMINAL) {
                            // Fullscreen mode: Compact floating circular hamburger button at top-start
                            Surface(
                                onClick = { isNavMenuOpen = !isNavMenuOpen },
                                shape = CircleShape,
                                color = AmoledGlassContainer,
                                border = BorderStroke(1.dp, AmoledGlassBorder),
                                shadowElevation = 8.dp,
                                modifier = Modifier
                                    .statusBarsPadding()
                                    .padding(start = 10.dp, top = 8.dp)
                                    .size(36.dp)
                                    .align(Alignment.TopStart)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Menu,
                                        contentDescription = "Open Navigation",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        } else {
                            // Normal mode: Sleek floating glass navigation pill across top
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = AmoledGlassContainer,
                                border = BorderStroke(1.dp, AmoledGlassBorder),
                                shadowElevation = 8.dp,
                                modifier = Modifier
                                    .statusBarsPadding()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .align(Alignment.TopCenter)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    // Left: Hamburger menu toggle + screen title
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { isNavMenuOpen = !isNavMenuOpen }
                                    ) {
                                        Icon(
                                            Icons.Default.Menu,
                                            contentDescription = "Navigation Menu",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "OpenStick",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = " › ",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = currentScreen.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    // Right: Quick Switch Icon Actions
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        IconButton(
                                            onClick = { currentScreen = Screen.HOME },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Home,
                                                contentDescription = "Home",
                                                tint = if (currentScreen == Screen.HOME) MaterialTheme.colorScheme.primary else Color.LightGray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { currentScreen = Screen.APPS },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Apps,
                                                contentDescription = "Apps",
                                                tint = if (currentScreen == Screen.APPS) MaterialTheme.colorScheme.primary else Color.LightGray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { currentScreen = Screen.TERMINAL },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Terminal,
                                                contentDescription = "Terminal",
                                                tint = if (currentScreen == Screen.TERMINAL) MaterialTheme.colorScheme.primary else Color.LightGray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { currentScreen = Screen.DESKTOP },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.DesktopWindows,
                                                contentDescription = "Desktop",
                                                tint = if (currentScreen == Screen.DESKTOP) MaterialTheme.colorScheme.primary else Color.LightGray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Floating Collapsible Glass Navigation Sheet / Modal
                        if (isNavMenuOpen) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x88000000))
                                    .clickable { isNavMenuOpen = false }
                            ) {
                                Card(
                                    shape = RoundedCornerShape(24.dp),
                                    colors = CardDefaults.cardColors(containerColor = AmoledGlassHigh),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .statusBarsPadding()
                                        .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                                        .fillMaxWidth()
                                        .clickable(enabled = false) {}
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp)
                                    ) {
                                        // Modal Header
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.MenuOpen,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Text(
                                                    text = "OpenStick Navigation",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = Color.White
                                                )
                                            }

                                            IconButton(
                                                onClick = { isNavMenuOpen = false },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Close Menu",
                                                    tint = Color.LightGray,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // 6 Screen Tiles
                                        for (screen in Screen.values()) {
                                            val isSelected = currentScreen == screen
                                            Surface(
                                                onClick = {
                                                    currentScreen = screen
                                                    isNavMenuOpen = false
                                                },
                                                shape = RoundedCornerShape(16.dp),
                                                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else AmoledGlassContainer,
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (isSelected) MaterialTheme.colorScheme.primary else AmoledGlassBorder
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(
                                                                if (isSelected) MaterialTheme.colorScheme.primary else AmoledGlassHigh
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            screen.icon,
                                                            contentDescription = null,
                                                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }

                                                    Spacer(modifier = Modifier.width(12.dp))

                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = screen.title,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 14.sp,
                                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                                                        )
                                                        Text(
                                                            text = screen.subtitle,
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }

                                                    if (isSelected) {
                                                        Surface(
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.height(20.dp)
                                                        ) {
                                                            Text(
                                                                text = "ACTIVE",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onPrimary,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        // Bottom System Note
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Debian GNU/Linux Trixie • ARM64",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = currentAccent.title,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = currentAccent.color
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
