package org.openstick.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.openstick.engine.HardwareBridges
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.ProotRunner
import org.openstick.engine.SessionService
import org.openstick.ui.theme.AmoledBackground
import org.openstick.ui.theme.AmoledContainer
import org.openstick.ui.theme.AmoledContainerHigh

@Composable
fun HomeScreen(
    env: LinuxEnvironment,
    runner: ProotRunner,
    onNavigateTerminal: () -> Unit,
    onNavigateDesktop: (isWindowed: Boolean) -> Unit,
    onNavigateApps: () -> Unit,
    onNavigateMonitor: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isInstalled by remember { mutableStateOf(env.isInstalled()) }
    var serviceActive by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf("") }

    if (!isInstalled) {
        SetupWizard(
            env = env,
            onSetupFinished = {
                isInstalled = env.isInstalled()
            },
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // App Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "OpenStick",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(10.dp))
                Badge(
                    containerColor = Color(0xFF1B5E20)
                ) {
                    Text(
                        text = "Ready",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = "100% FOSS",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        Text(
            text = "User: ${env.getConfiguredUsername()} • Debian Container",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Open Section (Exact Breadstick 3-tile launch bar)
        Text(
            text = "Open",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LaunchTile(
                imageVector = Icons.Default.DesktopWindows,
                title = "Desktop",
                subtitle = "Full screen",
                active = true,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateDesktop(false) }
            )

            LaunchTile(
                imageVector = Icons.Default.PictureInPicture,
                title = "Floating",
                subtitle = "One window per app",
                active = false,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateDesktop(true) }
            )

            LaunchTile(
                imageVector = Icons.Default.Terminal,
                title = "Terminal",
                subtitle = "Shell only",
                active = false,
                modifier = Modifier.weight(1f),
                onClick = onNavigateTerminal
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Device Access Section
        Text(
            text = "Device access",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AmoledContainer),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                DeviceAccessRow(
                    icon = Icons.Default.Folder,
                    title = "Device files",
                    detail = "Shared storage mounted at /mnt/sdcard",
                    status = "Available"
                )
                HorizontalDivider(color = AmoledContainerHigh, modifier = Modifier.padding(vertical = 10.dp))

                DeviceAccessRow(
                    icon = Icons.Default.Mic,
                    title = "Microphone",
                    detail = "Audio input piped through PulseAudio bridge",
                    status = "Active"
                )
                HorizontalDivider(color = AmoledContainerHigh, modifier = Modifier.padding(vertical = 10.dp))

                DeviceAccessRow(
                    icon = Icons.Default.PhotoCamera,
                    title = "Camera",
                    detail = "Hardware video stream at /run/bsl/camera",
                    status = "Active"
                )
                HorizontalDivider(color = AmoledContainerHigh, modifier = Modifier.padding(vertical = 10.dp))

                DeviceAccessRow(
                    icon = Icons.Default.Event,
                    title = "Calendar",
                    detail = "Android calendar synchronization into guest",
                    status = "Synced"
                )
                HorizontalDivider(color = AmoledContainerHigh, modifier = Modifier.padding(vertical = 10.dp))

                DeviceAccessRow(
                    icon = Icons.Default.Notifications,
                    title = "Android notifications",
                    detail = "Bidirectional notify-send desktop notification bridge",
                    status = "Active"
                )
                HorizontalDivider(color = AmoledContainerHigh, modifier = Modifier.padding(vertical = 10.dp))

                DeviceAccessRow(
                    icon = Icons.Default.Print,
                    title = "Printing",
                    detail = "CUPS / PDF direct printer backend service",
                    status = "Ready"
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Everything here is read on this device and written straight into your Linux system. Nothing is uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Apps Section
        Text(
            text = "Apps",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            onClick = onNavigateApps,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AmoledContainer),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Storefront,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Recommended apps",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Development tools, browsers, office and graphics, installed in one tap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Monitor Section
        Text(
            text = "Monitor",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            onClick = onNavigateMonitor,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AmoledContainer),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Resource monitor",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "CPU, memory, processes, power and temperature, live.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Environment Section
        Text(
            text = "Environment",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AmoledContainer),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Save your whole Linux setup to a file you keep, on this device or in your own cloud through the file picker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                actionMessage = "Creating system snapshot backup..."
                                val backupFile = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "openstick-backup.tar.zst")
                                try {
                                    env.createSnapshot(backupFile) { msg, _ -> actionMessage = msg }
                                    actionMessage = "Snapshot saved: ${backupFile.name}"
                                } catch (e: Throwable) {
                                    actionMessage = "Backup error: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Backup", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                actionMessage = "Restoring system snapshot..."
                                val backupFile = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "openstick-backup.tar.zst")
                                if (backupFile.exists()) {
                                    try {
                                        env.restoreSnapshot(backupFile) { msg, _ -> actionMessage = msg }
                                        actionMessage = "Snapshot restored successfully!"
                                    } catch (e: Throwable) {
                                        actionMessage = "Restore error: ${e.message}"
                                    }
                                } else {
                                    actionMessage = "No backup file found at ${backupFile.name}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Restore", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                actionMessage = "Repairing package database..."
                                runner.start(
                                    command = listOf("dpkg", "--configure", "-a"),
                                    onOutput = { actionMessage = it }
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Repair", fontSize = 12.sp)
                    }
                }

                if (actionMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = actionMessage,
                        fontSize = 11.sp,
                        color = Color(0xFF00FF66)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Clean Footer
        Text(
            text = "OpenStick Linux • 100% Free & Open Source",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LaunchTile(
    imageVector: ImageVector,
    title: String,
    subtitle: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(105.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else AmoledContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun DeviceAccessRow(
    icon: ImageVector,
    title: String,
    detail: String,
    status: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.White)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(status, style = MaterialTheme.typography.labelSmall, color = Color(0xFF00FF66))
    }
}
