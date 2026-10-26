package org.openstick.home

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openstick.engine.LinuxEnvironment
import org.openstick.engine.RootfsDownloader
import org.openstick.engine.RootfsInstaller
import org.openstick.ui.theme.AmoledBackground
import org.openstick.ui.theme.AmoledContainer
import org.openstick.ui.theme.AmoledContainerHigh

@Composable
fun SetupWizard(
    env: LinuxEnvironment,
    onSetupFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var isDesktopEdition by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("openstick") }
    var password by remember { mutableStateOf("openstick") }
    var isInstalling by remember { mutableStateOf(false) }
    var progressFraction by remember { mutableFloatStateOf(0.0f) }
    var statusText by remember { mutableStateOf("Ready to initialize") }
    var logText by remember { mutableStateOf("") }
    var installComplete by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBackground)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Welcome to OpenStick Linux",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Clean-room, 100% open-source Linux environment",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (!isInstalling && !installComplete) {
            // Step 1: Edition selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AmoledContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "1. Select Linux Edition",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    EditionRadioRow(
                        title = "Minimal Debian (Recommended)",
                        description = "~71 MB download. Debian Trixie base with core packages. Fast install, lower disk usage.",
                        selected = !isDesktopEdition,
                        onClick = { isDesktopEdition = false }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    EditionRadioRow(
                        title = "Full Desktop Edition",
                        description = "~315 MB download. Includes XFCE4, sound utilities, fonts, and graphical components ready out-of-the-box.",
                        selected = isDesktopEdition,
                        onClick = { isDesktopEdition = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step 2: Credentials
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AmoledContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "2. Linux Credentials",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' } },
                        label = { Text("Linux Username") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Linux Password") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Storage Check Indicator
            val stat = remember { android.os.StatFs(env.context.filesDir.path) }
            val availableGb = remember { (stat.availableBlocksLong * stat.blockSizeLong) / (1024.0 * 1024.0 * 1024.0) }
            val hasEnoughSpace = availableGb >= 1.5

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasEnoughSpace) Color(0xFF1B5E20).copy(alpha = 0.3f) else MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (hasEnoughSpace) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (hasEnoughSpace) Color(0xFF00FF66) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Storage Space: " + String.format(java.util.Locale.US, "%.2f GB available", availableGb),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Color.White
                        )
                        Text(
                            text = if (hasEnoughSpace) "Adequate storage available for Debian rootfs installation." else "Warning: Less than 1.5 GB available. Installation may fail due to low disk space.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    val safeUser = username.ifBlank { "openstick" }
                    val safePass = password.ifBlank { "openstick" }
                    isInstalling = true
                    errorMessage = null
                    logText = "Starting OpenStick provisioner...\n"

                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val downloader = RootfsDownloader(env.context, desktop = isDesktopEdition)
                                logText += "Fetching system image manifest...\n"
                                downloader.download { msg, frac ->
                                    statusText = msg
                                    progressFraction = frac * 0.5f
                                }

                                logText += "System image downloaded successfully.\nUnpacking rootfs...\n"
                                val installer = RootfsInstaller(env)
                                installer.installFromTar(downloader.imageFile, safeUser) { msg, frac ->
                                    statusText = msg
                                    progressFraction = 0.5f + (frac * 0.5f)
                                }
                            }
                            installComplete = true
                            isInstalling = false
                            statusText = "OpenStick Linux installed successfully!"
                            logText += "\nSetup completed! Linux environment ready."
                        } catch (e: Throwable) {
                            isInstalling = false
                            errorMessage = "Setup failed: ${e.message ?: e.javaClass.simpleName}"
                            logText += "\nERROR: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Initial Setup", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else if (isInstalling) {
            // Installing Progress View
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AmoledContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Setting Up OpenStick Linux",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = AmoledContainerHigh
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = statusText,
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Live Log Box
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black)
                    ) {
                        Text(
                            text = logText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF00FF66),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                        )
                    }
                }
            }
        } else if (installComplete) {
            // Completed View
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AmoledContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF00FF66),
                        modifier = Modifier.size(64.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "System Ready!",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Debian Linux is installed and configured with user '$username'. You can now use the Terminal, X11 Desktop, App Installer, and Resource Monitor.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onSetupFinished,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.ArrowForward, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enter OpenStick Linux")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditionRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AmoledContainerHigh else Color.Transparent
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                Spacer(modifier = Modifier.height(2.dp))
                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
