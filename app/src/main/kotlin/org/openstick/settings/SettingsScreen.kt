package org.openstick.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openstick.engine.*
import org.openstick.ui.theme.AmoledBackground
import org.openstick.ui.theme.AmoledContainer
import org.openstick.ui.theme.AmoledContainerHigh
import org.openstick.ui.theme.AppThemeMode
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

    // Bridges State - initialized from preferences (default: false)
    var cameraEnabled by remember { mutableStateOf(prefs.getBoolean("bridge_camera", false)) }
    var micEnabled by remember { mutableStateOf(prefs.getBoolean("bridge_mic", false)) }
    var audioOutputEnabled by remember { mutableStateOf(prefs.getBoolean("bridge_audio", false)) }
    var notifEnabled by remember { mutableStateOf(prefs.getBoolean("bridge_notif", false)) }
    var calendarEnabled by remember { mutableStateOf(prefs.getBoolean("bridge_calendar", false)) }
    var clipboardEnabled by remember { mutableStateOf(prefs.getBoolean("bridge_clipboard", false)) }
    var printingEnabled by remember { mutableStateOf(prefs.getBoolean("bridge_printing", false)) }
    var sshEnabled by remember { mutableStateOf(HardwareBridges.sshServerEnabled) }
    var vncEnabled by remember { mutableStateOf(HardwareBridges.vncServerEnabled) }
    var gpuEnabled by remember { mutableStateOf(GpuServer.isRunning) }
    var processBudget by remember { mutableFloatStateOf(HardwareBridges.processLimit.toFloat()) }

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
        // Title
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Hardware bridges, network, virtualization, and backup",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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

        // Section 1: Display Theme
        SettingsSection(title = "Display Theme", icon = Icons.Default.Palette) {
            ThemeOption(
                title = "AMOLED Pitch Black (Pure Black #000000)",
                subtitle = "Maximizes battery life on OLED displays",
                selected = currentTheme == AppThemeMode.AMOLED_BLACK,
                onClick = { onThemeChange(AppThemeMode.AMOLED_BLACK) }
            )
            ThemeOption(
                title = "Charcoal Dark",
                subtitle = "Subtle dark gray tone",
                selected = currentTheme == AppThemeMode.DARK,
                onClick = { onThemeChange(AppThemeMode.DARK) }
            )
            ThemeOption(
                title = "Light Theme",
                subtitle = "Clean daylight appearance",
                selected = currentTheme == AppThemeMode.LIGHT,
                onClick = { onThemeChange(AppThemeMode.LIGHT) }
            )
            ThemeOption(
                title = "Follow System",
                subtitle = "Matches Android system dark/light mode",
                selected = currentTheme == AppThemeMode.SYSTEM,
                onClick = { onThemeChange(AppThemeMode.SYSTEM) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 2: Hardware Bridges
        SettingsSection(title = "Hardware Bridges", icon = Icons.Default.Sensors) {
            SwitchRow(
                title = "Camera Bridge",
                subtitle = "Expose Android camera feed to guest Linux (/run/bsl/camera)",
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
                subtitle = "Bridge Android device calendar events to /run/bsl/calendar",
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
                subtitle = "CUPS / PDF direct printer backend service (/run/bsl/printing)",
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
                Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Restart PulseAudio Server")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 3: Network & Remote Access
        SettingsSection(title = "Network & Remote Access", icon = Icons.Default.Wifi) {
            Text("DNS Server Configuration", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Cloudflare", "Google", "OpenDNS", "Custom").forEach { dns ->
                    FilterChip(
                        selected = selectedDns == dns,
                        onClick = {
                            selectedDns = dns
                            prefs.edit().putString("selected_dns", dns).apply()
                            HardwareBridges.applyDnsConfig(env, dns, customDnsIp)
                            actionMessage = "Applied $dns DNS to Linux environment"
                        },
                        label = { Text(dns, fontSize = 11.sp) }
                    )
                }
            }

            if (selectedDns == "Custom") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customDnsIp,
                    onValueChange = {
                        customDnsIp = it
                        prefs.edit().putString("custom_dns_ip", it).apply()
                        HardwareBridges.applyDnsConfig(env, "Custom", it)
                    },
                    label = { Text("Custom DNS IP (e.g., 9.9.9.9)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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
        SettingsSection(title = "Engine & Performance", icon = Icons.Default.Speed) {
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
        SettingsSection(title = "Maintenance & Recovery", icon = Icons.Default.Build) {
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
        SettingsSection(title = "Snapshots & Backup", icon = Icons.Default.Backup) {
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
        SettingsSection(title = "Danger Zone", icon = Icons.Default.Warning) {
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AmoledContainer),
            shape = RoundedCornerShape(12.dp)
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
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AmoledContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
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

@Composable
private fun ThemeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
