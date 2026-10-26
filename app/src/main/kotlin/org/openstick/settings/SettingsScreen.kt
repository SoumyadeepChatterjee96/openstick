package org.openstick.settings

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openstick.engine.*
import org.openstick.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    env: LinuxEnvironment,
    runner: ProotRunner,
    currentTheme: AppThemeMode,
    onThemeChange: (AppThemeMode) -> Unit,
    currentAccent: AppAccent = AppAccent.CYAN,
    onAccentChange: (AppAccent) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("openstick_prefs", Context.MODE_PRIVATE) }

    // State
    var serviceActive by remember { mutableStateOf(false) }
    var selectedDns by remember { mutableStateOf(prefs.getString("selected_dns", "Cloudflare") ?: "Cloudflare") }
    var customDnsIp by remember { mutableStateOf(prefs.getString("custom_dns_ip", "") ?: "") }
    var actionMessage by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var workFraction by remember { mutableFloatStateOf(0f) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Desktop Scale (DPI)
    var selectedDpi by remember { mutableIntStateOf(prefs.getInt("desktop_dpi", 192)) }

    // Dropdown expanded states
    var themeDropdownExpanded by remember { mutableStateOf(false) }
    var dpiDropdownExpanded by remember { mutableStateOf(false) }
    var dnsDropdownExpanded by remember { mutableStateOf(false) }

    // Bridges State - initialized from preferences clamped with runtime permissions
    var cameraEnabled by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            prefs.getBoolean("bridge_camera", false)
        )
    }
    var micEnabled by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            prefs.getBoolean("bridge_mic", false)
        )
    }
    var audioOutputEnabled by remember { mutableStateOf(prefs.getBoolean("bridge_audio", false)) }
    var notifEnabled by remember {
        mutableStateOf(
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true) && prefs.getBoolean("bridge_notif", false)
        )
    }
    var calendarEnabled by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            prefs.getBoolean("bridge_calendar", false)
        )
    }
    var clipboardEnabled by remember { mutableStateOf(prefs.getBoolean("bridge_clipboard", false)) }
    var printingEnabled by remember { mutableStateOf(prefs.getBoolean("bridge_printing", false)) }
    var sshEnabled by remember { mutableStateOf(HardwareBridges.sshServerEnabled) }
    var vncEnabled by remember { mutableStateOf(HardwareBridges.vncServerEnabled) }
    var gpuEnabled by remember { mutableStateOf(GpuServer.isRunning) }
    var processBudget by remember { mutableFloatStateOf(HardwareBridges.processLimit.toFloat()) }

    // Keep settings synchronized across app resume and preference updates
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                val hasCal = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true
                cameraEnabled = hasCam && prefs.getBoolean("bridge_camera", false)
                micEnabled = hasMic && prefs.getBoolean("bridge_mic", false)
                calendarEnabled = hasCal && prefs.getBoolean("bridge_calendar", false)
                notifEnabled = hasNotif && prefs.getBoolean("bridge_notif", false)
                audioOutputEnabled = prefs.getBoolean("bridge_audio", false)
                clipboardEnabled = prefs.getBoolean("bridge_clipboard", false)
                printingEnabled = prefs.getBoolean("bridge_printing", false)
                selectedDpi = prefs.getInt("desktop_dpi", 192)
                selectedDns = prefs.getString("selected_dns", "Cloudflare") ?: "Cloudflare"
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "bridge_camera" -> {
                    val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    cameraEnabled = hasCam && prefs.getBoolean("bridge_camera", false)
                }
                "bridge_mic" -> {
                    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    micEnabled = hasMic && prefs.getBoolean("bridge_mic", false)
                }
                "bridge_calendar" -> {
                    val hasCal = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                    calendarEnabled = hasCal && prefs.getBoolean("bridge_calendar", false)
                }
                "bridge_notif" -> {
                    val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    } else true
                    notifEnabled = hasNotif && prefs.getBoolean("bridge_notif", false)
                }
                "bridge_audio" -> audioOutputEnabled = prefs.getBoolean("bridge_audio", false)
                "bridge_clipboard" -> clipboardEnabled = prefs.getBoolean("bridge_clipboard", false)
                "bridge_printing" -> printingEnabled = prefs.getBoolean("bridge_printing", false)
                "desktop_dpi" -> selectedDpi = prefs.getInt("desktop_dpi", 192)
                "selected_dns" -> selectedDns = prefs.getString("selected_dns", "Cloudflare") ?: "Cloudflare"
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Permission Launchers for runtime consent
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            cameraEnabled = true
            HardwareBridges.toggleCamera(context, env.rootfsDir, scope, true)
            prefs.edit().putBoolean("bridge_camera", true).apply()
            actionMessage = "Camera permission granted • Bridge active"
        } else {
            cameraEnabled = false
            HardwareBridges.toggleCamera(context, env.rootfsDir, scope, false)
            prefs.edit().putBoolean("bridge_camera", false).apply()
            actionMessage = "Camera permission denied"
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            micEnabled = true
            HardwareBridges.toggleMic(context, env.rootfsDir, scope, true)
            prefs.edit().putBoolean("bridge_mic", true).apply()
            actionMessage = "Microphone permission granted • Audio input active"
        } else {
            micEnabled = false
            HardwareBridges.toggleMic(context, env.rootfsDir, scope, false)
            prefs.edit().putBoolean("bridge_mic", false).apply()
            actionMessage = "Microphone permission denied"
        }
    }

    val calendarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            calendarEnabled = true
            HardwareBridges.toggleCalendar(context, env.rootfsDir, true)
            prefs.edit().putBoolean("bridge_calendar", true).apply()
            actionMessage = "Calendar permission granted • Sync active"
        } else {
            calendarEnabled = false
            HardwareBridges.toggleCalendar(context, env.rootfsDir, false)
            prefs.edit().putBoolean("bridge_calendar", false).apply()
            actionMessage = "Calendar permission denied"
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            notifEnabled = true
            HardwareBridges.toggleNotifications(context, env.rootfsDir, true)
            prefs.edit().putBoolean("bridge_notif", true).apply()
            actionMessage = "Notification permission granted • Alerts active"
        } else {
            notifEnabled = false
            HardwareBridges.toggleNotifications(context, env.rootfsDir, false)
            prefs.edit().putBoolean("bridge_notif", false).apply()
            actionMessage = "Notification permission denied"
        }
    }

    // Snapshot restore picker
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isWorking = true
                actionMessage = "Restoring snapshot from storage..."
                try {
                    withContext(Dispatchers.IO) {
                        val tempArchive = File(env.tmpDir, "restore_snapshot.tar.zst")
                        context.contentResolver.openInputStream(it)?.use { input ->
                            FileOutputStream(tempArchive).use { output ->
                                input.copyTo(output)
                            }
                        }
                        env.restoreSnapshot(tempArchive) { msg, frac ->
                            actionMessage = msg
                            workFraction = frac
                        }
                        tempArchive.delete()
                    }
                    actionMessage = "Snapshot successfully restored!"
                } catch (e: Exception) {
                    actionMessage = "Restore failed: ${e.message}"
                } finally {
                    isWorking = false
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Compact Header with Quick Action Menu
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Preferences & Control Center",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Quick Options",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier
                        .background(AmoledGlassHigh)
                        .border(1.dp, AmoledGlassBorder, RoundedCornerShape(8.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Restart PulseAudio Server", color = Color.White, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                runner.executeOneShot(
                                    command = listOf("/bin/sh", "-c", "pulseaudio -k 2>/dev/null; pulseaudio --start --exit-idle-time=-1 2>/dev/null || true"),
                                    onOutput = { actionMessage = it }
                                )
                                actionMessage = "PulseAudio sound server restarted"
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear Temporary Cache", color = Color.White, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuOpen = false
                            val count = env.tmpDir.listFiles()?.count { f ->
                                if (!f.name.startsWith(".X11-unix")) f.deleteRecursively() else false
                            } ?: 0
                            actionMessage = "Cleaned $count temporary cache files"
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Reset Theme to AMOLED Cyan", color = Color.White, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuOpen = false
                            onThemeChange(AppThemeMode.AMOLED_BLACK)
                            onAccentChange(AppAccent.CYAN)
                            actionMessage = "Theme reset to AMOLED Pitch Black with Electric Cyan"
                        }
                    )
                }
            }
        }

        if (actionMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AmoledContainerHigh)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(actionMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    if (isWorking) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { workFraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 1: Display & Appearance
        SettingsSection(
            title = "Display & Appearance",
            icon = Icons.Default.Palette,
            summary = "${currentAccent.title} • ${selectedDpi} DPI",
            initiallyExpanded = true
        ) {
            Text("Application Theme", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { themeDropdownExpanded = !themeDropdownExpanded },
                    color = AmoledContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (themeColor, themeBorder) = when (currentTheme) {
                            AppThemeMode.AMOLED_BLACK -> Color.Black to Color.White.copy(alpha = 0.6f)
                            AppThemeMode.DARK -> Color(0xFF1E1E1E) to Color(0xFF757575)
                            AppThemeMode.LIGHT -> Color.White to Color(0xFFCCCCCC)
                            AppThemeMode.SYSTEM -> Color(0xFF2E7D32) to Color(0xFF81C784)
                        }
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(themeColor)
                                .border(1.5.dp, themeBorder, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val themeTitle = when (currentTheme) {
                                AppThemeMode.AMOLED_BLACK -> "AMOLED Pitch Black"
                                AppThemeMode.DARK -> "Charcoal Dark"
                                AppThemeMode.LIGHT -> "Light Theme"
                                AppThemeMode.SYSTEM -> "Follow System Mode"
                            }
                            val themeSubtitle = when (currentTheme) {
                                AppThemeMode.AMOLED_BLACK -> "Pure black #000000 • Best battery life"
                                AppThemeMode.DARK -> "Subtle dark gray tone"
                                AppThemeMode.LIGHT -> "Clean daylight appearance"
                                AppThemeMode.SYSTEM -> "Matches Android system dark/light mode"
                            }
                            Text(themeTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                            Text(themeSubtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            if (themeDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
                DropdownMenu(
                    expanded = themeDropdownExpanded,
                    onDismissRequest = { themeDropdownExpanded = false },
                    modifier = Modifier
                        .background(AmoledContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                ) {
                    listOf(
                        Triple(AppThemeMode.AMOLED_BLACK, "AMOLED Pitch Black", "Pure black #000000 • Best battery life"),
                        Triple(AppThemeMode.DARK, "Charcoal Dark", "Subtle dark gray tone"),
                        Triple(AppThemeMode.LIGHT, "Light Theme", "Clean daylight appearance"),
                        Triple(AppThemeMode.SYSTEM, "Follow System", "Matches Android system dark/light mode")
                    ).forEach { (mode, name, desc) ->
                        val isSelected = currentTheme == mode
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White)
                                    Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                onThemeChange(mode)
                                themeDropdownExpanded = false
                            },
                            leadingIcon = {
                                val (c, b) = when (mode) {
                                    AppThemeMode.AMOLED_BLACK -> Color.Black to Color.White.copy(alpha = 0.6f)
                                    AppThemeMode.DARK -> Color(0xFF1E1E1E) to Color(0xFF757575)
                                    AppThemeMode.LIGHT -> Color.White to Color(0xFFCCCCCC)
                                    AppThemeMode.SYSTEM -> Color(0xFF2E7D32) to Color(0xFF81C784)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(1.5.dp, b, CircleShape)
                                )
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text("Desktop UI Scale (DPI)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { dpiDropdownExpanded = !dpiDropdownExpanded },
                    color = AmoledContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.AspectRatio,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val (dpiTitle, dpiSubtitle) = when (selectedDpi) {
                                96 -> "96 DPI (Desktop 1:1)" to "Classic desktop scale • Maximum workspace"
                                144 -> "144 DPI (Medium Scale)" to "Balanced scale for foldables & small tablets"
                                240 -> "240 DPI (Large UI)" to "Larger buttons and text for high-res screens"
                                else -> "192 DPI (Recommended)" to "Optimal default scale for mobile touchscreens"
                            }
                            Text(dpiTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                            Text(dpiSubtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            if (dpiDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
                DropdownMenu(
                    expanded = dpiDropdownExpanded,
                    onDismissRequest = { dpiDropdownExpanded = false },
                    modifier = Modifier
                        .background(AmoledContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                ) {
                    listOf(
                        Triple(192, "192 DPI (Recommended)", "Optimal default scale for mobile touchscreens"),
                        Triple(144, "144 DPI (Medium Scale)", "Balanced scale for foldables & small tablets"),
                        Triple(96, "96 DPI (Desktop 1:1)", "Classic desktop scale • Maximum workspace"),
                        Triple(240, "240 DPI (Large UI)", "Larger buttons and text for high-res screens")
                    ).forEach { (dpi, name, desc) ->
                        val isSelected = selectedDpi == dpi
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White)
                                    Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                selectedDpi = dpi
                                prefs.edit().putInt("desktop_dpi", dpi).apply()
                                actionMessage = "Desktop scale set to $dpi DPI (applies on next desktop launch)"
                                dpiDropdownExpanded = false
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text("Accent Color Palette", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (accent in AppAccent.values()) {
                    val isSelected = currentAccent == accent
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onAccentChange(accent) },
                        color = if (isSelected) AmoledGlassHigh else AmoledGlassContainer,
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) accent.color else AmoledGlassBorder
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(accent.color)
                                    .border(
                                        1.5.dp,
                                        if (isSelected) Color.White else Color.Transparent,
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = accent.title.split(" ").first(),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) accent.color else Color.LightGray
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 2: Hardware Bridges
        SettingsSection(
            title = "Hardware Bridges",
            icon = Icons.Default.Sensors,
            summary = if (cameraEnabled || micEnabled) "Active" else "Standby",
            initiallyExpanded = true
        ) {
            SwitchRow(
                title = "Camera Bridge",
                subtitle = "Expose Android camera feed to guest Linux (/run/openstick/camera)",
                checked = cameraEnabled,
                onCheckedChange = { enable ->
                    if (enable) {
                        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) {
                            cameraEnabled = true
                            HardwareBridges.toggleCamera(context, env.rootfsDir, scope, true)
                            prefs.edit().putBoolean("bridge_camera", true).apply()
                            actionMessage = "Camera bridge active"
                        } else {
                            cameraLauncher.launch(Manifest.permission.CAMERA)
                        }
                    } else {
                        cameraEnabled = false
                        HardwareBridges.toggleCamera(context, env.rootfsDir, scope, false)
                        prefs.edit().putBoolean("bridge_camera", false).apply()
                        actionMessage = "Camera bridge stopped"
                    }
                }
            )
            SwitchRow(
                title = "Microphone Bridge",
                subtitle = "Route Android audio input into PulseAudio record stream",
                checked = micEnabled,
                onCheckedChange = { enable ->
                    if (enable) {
                        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) {
                            micEnabled = true
                            HardwareBridges.toggleMic(context, env.rootfsDir, scope, true)
                            prefs.edit().putBoolean("bridge_mic", true).apply()
                            actionMessage = "Microphone audio bridge active"
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        micEnabled = false
                        HardwareBridges.toggleMic(context, env.rootfsDir, scope, false)
                        prefs.edit().putBoolean("bridge_mic", false).apply()
                        actionMessage = "Microphone bridge stopped"
                    }
                }
            )
            SwitchRow(
                title = "PulseAudio Sound Output",
                subtitle = "TCP pulse server listening on 127.0.0.1:4713",
                checked = audioOutputEnabled,
                onCheckedChange = { enable ->
                    audioOutputEnabled = enable
                    HardwareBridges.toggleAudio(scope, enable)
                    prefs.edit().putBoolean("bridge_audio", enable).apply()
                    actionMessage = if (enable) "PulseAudio playback bridge active" else "Sound output disabled"
                }
            )
            SwitchRow(
                title = "Bi-directional Notifications",
                subtitle = "Forward Linux notify-send alerts to Android notification tray",
                checked = notifEnabled,
                onCheckedChange = { enable ->
                    if (enable) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                notifEnabled = true
                                HardwareBridges.toggleNotifications(context, env.rootfsDir, true)
                                prefs.edit().putBoolean("bridge_notif", true).apply()
                                actionMessage = "Notification sync active"
                            } else {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else {
                            notifEnabled = true
                            HardwareBridges.toggleNotifications(context, env.rootfsDir, true)
                            prefs.edit().putBoolean("bridge_notif", true).apply()
                            actionMessage = "Notification sync active"
                        }
                    } else {
                        notifEnabled = false
                        HardwareBridges.toggleNotifications(context, env.rootfsDir, false)
                        prefs.edit().putBoolean("bridge_notif", false).apply()
                        actionMessage = "Notification sync stopped"
                    }
                }
            )
            SwitchRow(
                title = "Calendar Sync",
                subtitle = "Bridge Android device calendar events to /run/openstick/calendar",
                checked = calendarEnabled,
                onCheckedChange = { enable ->
                    if (enable) {
                        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) {
                            calendarEnabled = true
                            HardwareBridges.toggleCalendar(context, env.rootfsDir, true)
                            prefs.edit().putBoolean("bridge_calendar", true).apply()
                            actionMessage = "Calendar sync active"
                        } else {
                            calendarLauncher.launch(Manifest.permission.READ_CALENDAR)
                        }
                    } else {
                        calendarEnabled = false
                        HardwareBridges.toggleCalendar(context, env.rootfsDir, false)
                        prefs.edit().putBoolean("bridge_calendar", false).apply()
                        actionMessage = "Calendar sync stopped"
                    }
                }
            )
            SwitchRow(
                title = "Clipboard Sync",
                subtitle = "Seamless clipboard sharing between Android and X11/Linux",
                checked = clipboardEnabled,
                onCheckedChange = { enable ->
                    clipboardEnabled = enable
                    HardwareBridges.toggleClipboard(context, env.rootfsDir, enable)
                    prefs.edit().putBoolean("bridge_clipboard", enable).apply()
                    actionMessage = if (enable) "Clipboard synchronization active" else "Clipboard sync disabled"
                }
            )
            SwitchRow(
                title = "Printing Bridge",
                subtitle = "CUPS / PDF direct printer backend service (/run/openstick/printing)",
                checked = printingEnabled,
                onCheckedChange = { enable ->
                    printingEnabled = enable
                    HardwareBridges.togglePrinting(context, env.rootfsDir, enable)
                    prefs.edit().putBoolean("bridge_printing", enable).apply()
                    actionMessage = if (enable) "Printing spooler active" else "Printing bridge stopped"
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    scope.launch {
                        runner.executeOneShot(
                            command = listOf("/bin/sh", "-c", "pulseaudio -k 2>/dev/null; pulseaudio --start --exit-idle-time=-1 2>/dev/null || true"),
                            onOutput = { actionMessage = it }
                        )
                        actionMessage = "PulseAudio sound server restarted"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Restart PulseAudio Server")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 3: Network & Remote Access
        SettingsSection(
            title = "Network & Remote Access",
            icon = Icons.Default.Wifi,
            summary = "$selectedDns • SSH ${if (sshEnabled) "2222" else "Off"}",
            initiallyExpanded = false
        ) {
            Text("DNS Server Configuration", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { dnsDropdownExpanded = !dnsDropdownExpanded },
                    color = AmoledContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val (dnsTitle, dnsSubtitle) = when (selectedDns) {
                                "Cloudflare" -> "Cloudflare DNS" to "1.1.1.1 / 1.0.0.1 • Fast & Privacy-first"
                                "Google" -> "Google Public DNS" to "8.8.8.8 / 8.8.4.4 • Global High Availability"
                                "OpenDNS" -> "OpenDNS" to "208.67.222.222 • Security & Anti-Phishing"
                                "Custom" -> "Custom DNS" to (if (customDnsIp.isNotEmpty()) customDnsIp else "Manual nameserver IP")
                                else -> selectedDns to "Custom configuration"
                            }
                            Text(dnsTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                            Text(dnsSubtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            if (dnsDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
                DropdownMenu(
                    expanded = dnsDropdownExpanded,
                    onDismissRequest = { dnsDropdownExpanded = false },
                    modifier = Modifier
                        .background(AmoledContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                ) {
                    listOf(
                        Triple("Cloudflare", "Cloudflare DNS", "1.1.1.1 / 1.0.0.1 • Fast & Privacy-first"),
                        Triple("Google", "Google Public DNS", "8.8.8.8 / 8.8.4.4 • Global High Availability"),
                        Triple("OpenDNS", "OpenDNS", "208.67.222.222 • Security & Anti-Phishing"),
                        Triple("Custom", "Custom DNS", "Enter custom DNS server IP address")
                    ).forEach { (dns, name, desc) ->
                        val isSelected = selectedDns == dns
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White)
                                    Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                selectedDns = dns
                                prefs.edit().putString("selected_dns", dns).apply()
                                HardwareBridges.applyDnsConfig(env, dns, customDnsIp)
                                actionMessage = "Applied $dns DNS to Linux environment"
                                dnsDropdownExpanded = false
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }
            }

            if (selectedDns == "Custom") {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = customDnsIp,
                    onValueChange = {
                        customDnsIp = it
                        prefs.edit().putString("custom_dns_ip", it).apply()
                        HardwareBridges.applyDnsConfig(env, "Custom", it)
                    },
                    label = { Text("Custom DNS IP (e.g. 9.9.9.9)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SwitchRow(
                title = "SSH Server (Port 2222)",
                subtitle = "Run ssh server for remote terminal access: ssh -p 2222 ${env.getConfiguredUsername()}@localhost",
                checked = sshEnabled,
                onCheckedChange = {
                    sshEnabled = it
                    if (it) {
                        HardwareBridges.startSshServer(runner, scope)
                        actionMessage = "SSH Server started on port 2222"
                    } else {
                        HardwareBridges.stopSshServer(runner, scope)
                        actionMessage = "SSH Server stopped"
                    }
                }
            )

            SwitchRow(
                title = "VNC Server (Port 5900)",
                subtitle = "Allow external VNC clients to connect to the X11 desktop display",
                checked = vncEnabled,
                onCheckedChange = {
                    vncEnabled = it
                    if (it) {
                        HardwareBridges.startVncServer(runner, scope)
                        actionMessage = "VNC Server listening on localhost:5900"
                    } else {
                        HardwareBridges.stopVncServer(runner, scope)
                        actionMessage = "VNC Server stopped"
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 4: Engine & Performance
        SettingsSection(
            title = "Engine & Performance",
            icon = Icons.Default.Speed,
            summary = if (gpuEnabled) "3D GPU On" else "Software",
            initiallyExpanded = false
        ) {
            SwitchRow(
                title = "VirGL 3D GPU Acceleration",
                subtitle = "Hardware 3D rendering bridge via virpipe/GLES socket",
                checked = gpuEnabled,
                onCheckedChange = {
                    gpuEnabled = it
                    if (it) {
                        GpuServer.start(env)
                        actionMessage = "VirGL 3D GPU acceleration enabled"
                    } else {
                        GpuServer.stop()
                        actionMessage = "GPU acceleration disabled (software fallback)"
                    }
                }
            )

            SwitchRow(
                title = "Keep Running in Background",
                subtitle = "Persistent foreground service & wake lock prevents Android OS sleeping Linux",
                checked = serviceActive,
                onCheckedChange = {
                    serviceActive = it
                    if (it) {
                        SessionService.start(context)
                        actionMessage = "Background persistence service started"
                    } else {
                        SessionService.stop(context)
                        actionMessage = "Background service stopped"
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Android Process Limit Budget: ${processBudget.toInt()} max",
                fontSize = 13.sp,
                color = Color.White
            )
            Slider(
                value = processBudget,
                onValueChange = {
                    processBudget = it
                    HardwareBridges.processLimit = it.toInt()
                },
                valueRange = 16f..64f,
                steps = 5
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 5: Maintenance & Recovery
        SettingsSection(
            title = "Maintenance & Recovery",
            icon = Icons.Default.Build,
            summary = "Repair & Cache",
            initiallyExpanded = false
        ) {
            OutlinedButton(
                onClick = {
                    actionMessage = "Repairing packages with dpkg & apt-get -f install..."
                    HardwareBridges.repairPackages(runner, scope) { log ->
                        actionMessage = log
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Healing, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Repair Broken Packages (dpkg --configure -a)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    HardwareBridges.forceStopSession(runner) {
                        actionMessage = "All sessions forcefully stopped"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Force Stop All Sessions & Clear Locks")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val count = env.tmpDir.listFiles()?.count { f ->
                        if (!f.name.startsWith(".X11-unix")) f.deleteRecursively() else false
                    } ?: 0
                    actionMessage = "Cleaned $count temporary cache files"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clean Temporary & Socket Files")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 6: Snapshots & Backup
        SettingsSection(
            title = "Snapshots & Backup",
            icon = Icons.Default.Backup,
            summary = ".tar.zst Backups",
            initiallyExpanded = false
        ) {
            Text(
                text = "Create compressed .tar.zst image snapshots of your full Linux system for safe keeping or transfer.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            isWorking = true
                            actionMessage = "Exporting rootfs snapshot..."
                            try {
                                withContext(Dispatchers.IO) {
                                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
                                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                    val backupFile = File(downloadDir, "openstick-backup-$timestamp.tar.zst")
                                    env.createSnapshot(backupFile) { msg, frac ->
                                        actionMessage = msg
                                        workFraction = frac
                                    }
                                }
                                actionMessage = "Snapshot saved to Downloads!"
                            } catch (e: Exception) {
                                actionMessage = "Backup failed: ${e.message}"
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Create Snapshot")
                }

                OutlinedButton(
                    onClick = {
                        restoreLauncher.launch(arrayOf("application/zstd", "application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restore Snapshot")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 7: Danger Zone
        SettingsSection(
            title = "Danger Zone",
            icon = Icons.Default.Warning,
            summary = "System Reset",
            initiallyExpanded = false
        ) {
            Text(
                text = "Irreversible actions that modify or delete your containerized distribution.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { showDeleteConfirmDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete Linux System & Reset")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 8: About
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            color = AmoledGlassContainer,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, AmoledGlassBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "OpenStick Linux",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Version 1.0.0 (Clean-Room Community Edition)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "OpenStick Linux is 100% free and open-source software with zero paywalls, zero license checks, and zero feature gates. All capabilities are fully available to every user.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Confirmation Dialog for Delete Linux
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Linux Environment?", fontWeight = FontWeight.Bold) },
            text = {
                Text("This will permanently remove the Debian rootfs directory and all user files within it. This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        val ok = env.deleteLinuxSystem()
                        actionMessage = if (ok) "Linux system deleted. You can re-run initial setup." else "Failed to delete rootfs."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = AmoledContainer
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    summary: String = "",
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)),
        color = AmoledGlassContainer,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, AmoledGlassBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                if (summary.isNotEmpty() && !isExpanded) {
                    Text(
                        text = summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(AmoledGlassHigh, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
